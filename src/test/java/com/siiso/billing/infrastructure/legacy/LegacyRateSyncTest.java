package com.siiso.billing.infrastructure.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.siiso.billing.application.RateService;
import com.siiso.billing.application.RateService.PriceChange;
import com.siiso.billing.application.StaffIdentity;
import com.siiso.billing.infrastructure.persistence.JdbcRateStore;
import com.siiso.billing.support.FixedClock;
import com.siiso.businessdb.BusinessSchema;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Import and write-back end to end: the real legacy rate tables on MySQL 5.7 (synthetic rows) and the
 * business schema on MySQL 9.7 (siiso-business-db plus test data). Today: 2026-12-01. Ordered.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyRateSyncTest {

    @Container
    static final MySQLContainer LEGACY = new MySQLContainer("mysql:5.7")
            .withInitScripts("legacy/schema.sql", "legacy/data.sql");

    // root: the business migrations also create the service roles, as an installation administrator does.
    @Container
    static final MySQLContainer TARGET = new MySQLContainer("mysql:9.7.0").withUsername("root");

    static HikariDataSource legacyDataSource;
    static HikariDataSource targetDataSource;
    static JdbcClient legacy;
    static JdbcClient target;
    static LegacyRateImport legacyImport;
    static LegacyRateWriteBack writeBack;
    static RateService rates;
    static TransactionTemplate transaction;

    @BeforeAll
    static void migrateAndImport() {
        legacyDataSource = dataSource(LEGACY);
        targetDataSource = dataSource(TARGET);
        Flyway.configure().dataSource(targetDataSource).locations(BusinessSchema.MIGRATIONS, "classpath:db/test-data")
                .load().migrate();
        legacy = JdbcClient.create(legacyDataSource);
        target = JdbcClient.create(targetDataSource);
        JdbcRateStore store = new JdbcRateStore(target);
        transaction = new TransactionTemplate(new JdbcTransactionManager(targetDataSource));
        legacyImport = new LegacyRateImport(legacy, target, store, transaction, FixedClock.clock());
        // The container's test user can write; in production a least-privilege user does.
        writeBack = new LegacyRateWriteBack(legacy, target, FixedClock.clock());
        rates = new RateService(store, FixedClock.clock());
        legacyImport.run(null);
    }

    @AfterAll
    static void close() {
        legacyDataSource.close();
        targetDataSource.close();
    }

    private static HikariDataSource dataSource(MySQLContainer container) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(container.getJdbcUrl());
        dataSource.setUsername(container.getUsername());
        dataSource.setPassword(container.getPassword());
        return dataSource;
    }

    private static List<String> planOf(String company) {
        return target.sql("""
                        SELECT p.code FROM billing_rate_plan_assignment a
                          JOIN billing_rate_plan p ON p.id = a.rate_plan_id
                          JOIN organization o ON o.id = a.organization_id
                         WHERE o.code = :code AND a.valid_until IS NULL""")
                .param("code", company).query(String.class).list();
    }

    private static List<String> prices(String plan) {
        return target.sql("""
                        SELECT CONCAT(s.code, '=', FORMAT(pr.price, 0, 'en_US')) FROM billing_rate_plan_price pr
                          JOIN billing_rate_plan p ON p.id = pr.rate_plan_id
                          JOIN service_offering s ON s.id = pr.service_offering_id
                         WHERE p.code = :plan AND pr.valid_until IS NULL ORDER BY s.code""")
                .param("plan", plan).query(String.class).list();
    }

    private static List<String> legacyRates(String company) {
        return legacy.sql("SELECT CDGEXAMEN, VALORTARIFA, HABI FROM tarifa WHERE CDGEMPRESA = :code ORDER BY CDGEXAMEN")
                .param("code", company)
                .query((rs, n) -> rs.getString(1) + "=" + String.format(java.util.Locale.US, "%,.0f", rs.getBigDecimal(2))
                        + ":" + rs.getString(3))
                .list();
    }

    private static String dependencyOf(String company) {
        return legacy.sql("SELECT DEPENDENCIA_EMPRESA FROM empresa WHERE CDGEMPRESA = :code").param("code", company)
                .query(String.class).single();
    }

    @Test
    @Order(1)
    void eachGroupOfDependentCompaniesBecomesASharedPlan() {
        assertThat(prices("LEGACY-0001")).as("only enabled rows of the company that owns the list")
                .containsExactly("1001=18,000", "4001=40,000");
        assertThat(prices("LEGACY-0080")).as("unknown services are left out").containsExactly("4001=35,000");
        assertThat(planOf("0001")).containsExactly("LEGACY-0001");
        assertThat(planOf("0002")).as("priced as the desktop app does: by its dependency").containsExactly("LEGACY-0001");
        assertThat(planOf("0003")).containsExactly("LEGACY-0001");
        assertThat(planOf("0081")).as("no rates in the legacy database").isEmpty();
        assertThat(target.sql("SELECT COUNT(*) FROM billing_legacy_outbox").query(Integer.class).single())
                .as("the import leaves the legacy database as it is").isZero();
    }

    @Test
    @Order(1)
    void descuentoBecomesCostSharingRules() {
        assertThat(target.sql("""
                        SELECT CONCAT_WS('|', r.cost_sharing_level, r.moderator_fee, r.copay_percent)
                          FROM billing_cost_sharing_rule r JOIN organization o ON o.id = r.organization_id
                         WHERE o.code = '0080' ORDER BY r.cost_sharing_level""").query(String.class).list())
                .containsExactly("1|3200.00|2.00", "2|6100.00|4.00");
    }

    @Test
    @Order(2)
    void importingAgainChangesNothing() {
        int before = target.sql("SELECT COUNT(*) FROM billing_rate_plan_price").query(Integer.class).single();
        target.sql("UPDATE billing_cost_sharing_rule SET moderator_fee = 3500 WHERE cost_sharing_level = 1").update();
        legacyImport.run(null);
        assertThat(target.sql("SELECT moderator_fee FROM billing_cost_sharing_rule WHERE cost_sharing_level = 1")
                .query(java.math.BigDecimal.class).single()).as("a rule changed here stays").isEqualByComparingTo("3500");
        assertThat(target.sql("SELECT COUNT(*) FROM billing_rate_plan_price").query(Integer.class).single())
                .isEqualTo(before);
    }

    @Test
    @Order(3)
    void aChangeHereGivesTheCompanyAndItsLegacyDependentsTheirOwnRows() {
        StaffIdentity staff = new StaffIdentity(UUID.randomUUID(), Set.of("ORGANIZATION_RATES:UPDATE", "RATE_PLANS:UPDATE"));
        transaction.executeWithoutResult(status -> rates.changeOwnPrices(staff, 2, FixedClock.TODAY,
                List.of(new PriceChange("1001", new BigDecimal("15000")))));
        transaction.executeWithoutResult(status -> rates.changePlanPrices(staff, "LEGACY-0001", FixedClock.TODAY,
                List.of(new PriceChange("4001", new BigDecimal("42000")))));

        writeBack.publishPending();

        assertThat(dependencyOf("0002")).isEqualTo("0002");
        assertThat(legacyRates("0002")).as("own price, the rest from its plan").containsExactly("1001=15,000:1", "4001=42,000:1");
        assertThat(dependencyOf("0003")).as("it depended on 0001, which changed").isEqualTo("0003");
        assertThat(legacyRates("0003")).containsExactly("1001=18,000:1", "4001=42,000:1");
        assertThat(legacyRates("0001")).as("rows it no longer has are disabled")
                .containsExactly("1001=18,000:1", "1002=9,000:0", "4001=42,000:1");
        assertThat(target.sql("SELECT COUNT(*) FROM billing_legacy_outbox WHERE processed_at IS NULL")
                .query(Integer.class).single()).isZero();
    }

    @Test
    @Order(4)
    void theImportKeepsWhatStaffChangedHere() {
        legacyImport.run(null);
        assertThat(prices("LEGACY-0001")).as("plan edited here").contains("4001=42,000");
        assertThat(target.sql("SELECT code FROM billing_rate_plan WHERE organization_id = 2").query(String.class).single())
                .isEqualTo("ORG-0002");
    }

    @Test
    @Order(5)
    void aRunningCampaignIsWrittenAsTheCompanyPrice() {
        StaffIdentity staff = new StaffIdentity(UUID.randomUUID(), Set.of("CAMPAIGNS:CREATE", "CAMPAIGNS:UPDATE"));
        transaction.executeWithoutResult(status -> {
            String code = rates.createCampaign(staff, new RateService.CampaignInput("Diciembre", null, FixedClock.TODAY,
                    FixedClock.TODAY.plusDays(30), com.siiso.billing.domain.PricingMode.FIXED_PRICES, null, null, null)).code();
            rates.changeCampaignPrices(staff, code, null, List.of(new PriceChange("4001", new BigDecimal("1000"))));
            rates.addCampaignCompany(staff, code, 80);
        });

        writeBack.publishPending();

        assertThat(legacyRates("0080")).contains("4001=1,000:1");
    }
}
