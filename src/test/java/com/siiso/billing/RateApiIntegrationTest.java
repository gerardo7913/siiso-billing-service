package com.siiso.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.siiso.billing.support.FixedClock;
import com.siiso.billing.support.TestTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** The whole service against MySQL 9.7 with the business schema of siiso-business-db and test data. Today: 2026-12-01. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(FixedClock.class)
class RateApiIntegrationTest {

    // root: the business migrations also create the service roles, as an installation administrator does.
    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0").withUsername("root");

    static final TestTokens TOKENS = new TestTokens();
    static final String MANAGER = TOKENS.staffToken(List.of("RATE_PLANS:READ", "RATE_PLANS:CREATE", "RATE_PLANS:UPDATE",
            "ORGANIZATION_RATES:READ", "ORGANIZATION_RATES:UPDATE", "CAMPAIGNS:READ", "CAMPAIGNS:CREATE",
            "CAMPAIGNS:UPDATE"));
    static final String READER = TOKENS.staffToken(List.of("RATE_PLANS:READ", "ORGANIZATION_RATES:READ"));
    static final String BASE = "/api/v1/billing";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("siiso.security.jwt.public-key-location", TOKENS::publicKeyLocation);
        registry.add("BILLING_DB_PASSWORD", MYSQL::getPassword);
        // Ignore a developer's local .env: no legacy database here.
        registry.add("siiso.legacy.datasource.url", () -> "");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/business/migration,classpath:db/test-data");
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    private MvcTestResult send(String method, String token, String uri, String json) {
        var request = switch (method) {
            case "POST" -> mvc.post();
            case "PUT" -> mvc.put();
            case "DELETE" -> mvc.delete();
            default -> mvc.get();
        };
        var builder = request.uri(BASE + uri).header("Authorization", "Bearer " + token);
        return json == null ? builder.exchange() : builder.contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private void createPlan(String code) {
        assertThat(send("POST", MANAGER, "/rate-plans", """
                {"code": "%s", "name": "Plan %s", "rate_schedule_code": "1"}""".formatted(code, code))).hasStatus(201);
    }

    private int queued(long organizationId) {
        return jdbc.sql("SELECT COUNT(*) FROM billing_legacy_outbox WHERE organization_id = :id")
                .param("id", organizationId).query(Integer.class).single();
    }

    @Test
    void aCompanyPaysItsOwnPriceOtherwiseTheOneOfItsPlan() {
        createPlan("DIAMANTE");
        assertThat(send("PUT", MANAGER, "/rate-plans/DIAMANTE/prices", """
                {"valid_from": "2026-12-01", "changes": [{"service_code": "4001", "price": 50000},
                                                         {"service_code": "1001", "price": 20000.50}]}""")).hasStatus(200)
                .bodyJson().extractingPath("$.changed").isEqualTo(2);
        assertThat(send("PUT", MANAGER, "/companies/80/plan", """
                {"plan_code": "DIAMANTE", "valid_from": "2026-12-01"}""")).hasStatus(200);
        assertThat(send("PUT", MANAGER, "/companies/80/own-prices", """
                {"valid_from": "2026-12-01", "changes": [{"service_code": "1001", "price": 15000}]}""")).hasStatus(200);

        MvcTestResult effective = send("GET", READER, "/companies/80/effective-prices", null);
        assertThat(effective).bodyJson().extractingPath("$[?(@.service_code == '1001')].source").asArray().containsExactly("OWN");
        assertThat(effective).bodyJson().extractingPath("$[?(@.service_code == '1001')].price").asArray().containsExactly(15000.0);
        assertThat(effective).bodyJson().extractingPath("$[?(@.service_code == '4001')].plan_code").asArray().containsExactly("DIAMANTE");
        assertThat(send("GET", READER, "/companies/80", null)).bodyJson().extractingPath("$.own_plan_code").isEqualTo("ORG-0080");
        assertThat(queued(80)).as("its plan and its own prices of today go to the legacy write-back").isEqualTo(2);
    }

    @Test
    void pricesAreDatedTheHistoryIsKeptAndThePastNeverChanges() {
        createPlan("ORO");
        send("PUT", MANAGER, "/rate-plans/ORO/prices", """
                {"valid_from": "2026-12-01", "changes": [{"service_code": "4001", "price": 50000}]}""");
        send("PUT", MANAGER, "/companies/81/plan", """
                {"plan_code": "ORO", "valid_from": "2026-12-01"}""");
        int queuedBefore = queued(81);

        assertThat(send("PUT", MANAGER, "/rate-plans/ORO/prices", """
                {"valid_from": "2027-01-01", "changes": [{"service_code": "4001", "price": 60000}]}""")).hasStatus(200);

        MvcTestResult today = send("GET", READER, "/rate-plans/ORO/prices?in_plan=true", null);
        assertThat(today).bodyJson().extractingPath("$.items[0].price").isEqualTo(50000.0);
        assertThat(today).bodyJson().extractingPath("$.items[0].next_price").isEqualTo(60000.0);
        assertThat(today).bodyJson().extractingPath("$.items[0].next_from").isEqualTo("2027-01-01");
        assertThat(send("GET", READER, "/rate-plans/ORO/prices?in_plan=true&date=2027-01-15", null)).bodyJson()
                .extractingPath("$.items[0].price").isEqualTo(60000.0);
        assertThat(send("GET", READER, "/rate-plans/ORO/prices/4001/history", null)).bodyJson()
                .extractingPath("$.length()").isEqualTo(2);
        assertThat(queued(81)).as("a future change waits for the daily job").isEqualTo(queuedBefore);
        assertThat(send("PUT", MANAGER, "/rate-plans/ORO/prices", """
                {"valid_from": "2026-11-30", "changes": [{"service_code": "4001", "price": 1}]}""")).hasStatus(400)
                .bodyJson().extractingPath("$.errors.valid_from").isNotNull();

        assertThat(send("PUT", MANAGER, "/rate-plans/ORO/prices", """
                {"valid_from": "2026-12-01", "changes": [
                    {"service_code": "1001", "price": 20000},
                    {"service_code": "1002", "price": 8000, "valid_from": "2027-03-01"}]}""")).hasStatus(200);
        assertThat(send("GET", READER, "/rate-plans/ORO/prices?in_plan=true&search=100", null)).bodyJson()
                .extractingPath("$.items[?(@.service_code == '1002')].next_from").asArray().containsExactly("2027-03-01");
        assertThat(send("PUT", MANAGER, "/rate-plans/ORO/prices", """
                {"valid_from": "2026-12-01", "changes": [{"service_code": "1002", "price": 1, "valid_from": "2026-01-01"}]}"""))
                .hasStatus(400).bodyJson().extractingPath("$.errors['changes.1002']").isNotNull();
    }

    @Test
    void aBulkIncreaseRoundsAndAnInactivePlanPricesNothing() {
        createPlan("PLATA");
        send("PUT", MANAGER, "/rate-plans/PLATA/prices", """
                {"valid_from": "2026-12-01", "changes": [{"service_code": "4001", "price": 43750},
                                                         {"service_code": "1002", "price": 9990}]}""");
        assertThat(send("POST", MANAGER, "/rate-plans/PLATA/increase", """
                {"valid_from": "2026-12-02", "percent": 10, "rounding": "HUNDRED"}""")).hasStatus(200)
                .bodyJson().extractingPath("$.changed").isEqualTo(2);
        assertThat(send("GET", READER, "/rate-plans/PLATA/prices?in_plan=true&date=2026-12-02&search=4001", null))
                .bodyJson().extractingPath("$.items[0].price").isEqualTo(48100.0);

        send("PUT", MANAGER, "/companies/3/plan", """
                {"plan_code": "PLATA", "valid_from": "2026-12-01"}""");
        assertThat(send("GET", READER, "/companies/3/effective-prices", null)).bodyJson()
                .extractingPath("$.length()").isEqualTo(2);
        MvcTestResult plan = send("GET", READER, "/rate-plans/PLATA", null);
        assertThat(plan).bodyJson().extractingPath("$.companies_count").isEqualTo(1);
        assertThat(send("PUT", MANAGER, "/rate-plans/PLATA", """
                {"name": "Plan PLATA", "rate_schedule_code": "1", "status": "INACTIVE", "version": 0}""")).hasStatus(200);
        assertThat(send("GET", READER, "/companies/3/effective-prices", null)).bodyJson()
                .extractingPath("$.length()").isEqualTo(0);
    }

    @Test
    void rulesAndPermissions() {
        createPlan("BRONCE");
        assertThat(send("POST", MANAGER, "/rate-plans", """
                {"code": "BRONCE", "name": "Otro", "rate_schedule_code": "1"}""")).hasStatus(409);
        assertThat(send("POST", MANAGER, "/rate-plans", """
                {"code": "ORG-0001", "name": "X", "rate_schedule_code": "1"}""")).hasStatus(400);
        assertThat(send("PUT", READER, "/rate-plans/BRONCE/prices", """
                {"valid_from": "2026-12-01", "changes": [{"service_code": "4001", "price": 1}]}""")).hasStatus(403);
        assertThat(send("PUT", MANAGER, "/rate-plans/BRONCE/prices", """
                {"valid_from": "2026-12-01", "changes": [{"service_code": "1003", "price": 1}]}""")).hasStatus(400)
                .bodyJson().extractingPath("$.errors['changes.1003']").isNotNull();
        assertThat(send("GET", READER, "/rate-plans/NOEXISTE", null)).hasStatus(404);
        assertThat(send("GET", TOKENS.staffToken(List.of()), "/rate-plans", null)).hasStatus(403);
    }

    @Test
    void aCampaignAlwaysWinsDuringItsPeriodAndACompanyHasOneAtATime() throws Exception {
        createPlan("ANUAL");
        send("PUT", MANAGER, "/rate-plans/ANUAL/prices", """
                {"valid_from": "2026-12-01", "changes": [{"service_code": "4001", "price": 50000},
                                                         {"service_code": "1001", "price": 20000}]}""");
        send("PUT", MANAGER, "/companies/2/plan", """
                {"plan_code": "ANUAL", "valid_from": "2026-12-01"}""");
        send("PUT", MANAGER, "/companies/1/plan", """
                {"plan_code": "ANUAL", "valid_from": "2026-12-01"}""");

        MvcTestResult created = send("POST", MANAGER, "/campaigns", """
                {"name": "Día de la mujer", "valid_from": "2026-12-01", "valid_until": "2026-12-31",
                 "pricing_mode": "FIXED_PRICES"}""");
        assertThat(created).hasStatus(201).bodyJson().extractingPath("$.code").asString().startsWith("CAMP-");
        String fixed = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.code");
        assertThat(send("PUT", MANAGER, "/campaigns/" + fixed + "/prices", """
                {"changes": [{"service_code": "4001", "price": 30000}, {"service_code": "1001", "price": 25000}]}"""))
                .hasStatus(200);
        assertThat(send("POST", MANAGER, "/campaigns/" + fixed + "/companies", """
                {"organization_id": 2}""")).hasStatus(200);

        MvcTestResult during = send("GET", READER, "/companies/2/effective-prices", null);
        assertThat(during).bodyJson().extractingPath("$[?(@.service_code == '4001')].price").asArray().containsExactly(30000.0);
        assertThat(during).bodyJson().extractingPath("$[?(@.service_code == '1001')].price").asArray()
                .as("the campaign wins even when it is higher").containsExactly(25000.0);
        assertThat(during).bodyJson().extractingPath("$[?(@.service_code == '4001')].source").asArray().containsExactly("CAMPAIGN");
        assertThat(send("GET", READER, "/companies/2/effective-prices?date=2027-01-05", null)).bodyJson()
                .extractingPath("$[?(@.service_code == '4001')].price").asArray().containsExactly(50000.0);

        MvcTestResult discount = send("POST", MANAGER, "/campaigns", """
                {"name": "Diez por ciento", "valid_from": "2026-12-15", "valid_until": "2026-12-20",
                 "pricing_mode": "DISCOUNT", "discount_percent": 10, "discount_rounding": "HUNDRED"}""");
        String discountCode = com.jayway.jsonpath.JsonPath.read(discount.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.code");
        assertThat(send("POST", MANAGER, "/campaigns/" + discountCode + "/companies", """
                {"organization_id": 2}""")).as("two campaigns at the same time").hasStatus(409);
        assertThat(send("POST", MANAGER, "/campaigns/" + discountCode + "/companies", """
                {"organization_id": 1}""")).hasStatus(200);
        assertThat(send("GET", READER, "/companies/1/effective-prices?date=2026-12-16", null)).bodyJson()
                .extractingPath("$[?(@.service_code == '4001')].price").asArray().containsExactly(45000.0);
        assertThat(send("GET", READER, "/companies/1/effective-prices?date=2026-12-21", null)).bodyJson()
                .extractingPath("$[?(@.service_code == '4001')].price").asArray().containsExactly(50000.0);
        assertThat(send("DELETE", MANAGER, "/campaigns/" + discountCode + "/companies/1", null)).hasStatus(200);
        assertThat(send("POST", MANAGER, "/campaigns", """
                {"name": "Pasada", "valid_from": "2026-11-01", "valid_until": "2026-11-30", "pricing_mode": "FIXED_PRICES"}"""))
                .hasStatus(400);
    }
}
