package com.siiso.billing.infrastructure.persistence;

import com.siiso.billing.application.RateStore;
import com.siiso.billing.domain.Company;
import com.siiso.billing.domain.EffectivePrice;
import com.siiso.billing.domain.PeriodPlanner.Operation;
import com.siiso.billing.domain.PeriodPlanner.Period;
import com.siiso.billing.domain.PlanAssignment;
import com.siiso.billing.domain.PlanPrice;
import com.siiso.billing.domain.PriceHistoryEntry;
import com.siiso.billing.domain.PricingMode;
import com.siiso.billing.domain.Rounding;
import com.siiso.billing.domain.RatePlan;
import com.siiso.billing.domain.RatePlanType;
import com.siiso.billing.domain.RecordStatus;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * The billing_* tables of siiso_business (siiso-docs ADR 0016, 0022). Companies and services are read
 * through the views their owners publish; ids never leave this class except company ids.
 */
@Repository
public class JdbcRateStore implements RateStore {

    /** Plan columns, with the counts of today ({@code :today}). */
    private static final String PLAN_SELECT = """
            SELECT p.id, p.code, p.name, p.description, p.plan_type, p.organization_id, p.status, p.version,
                   rs.code AS rate_schedule_code, rs.name AS rate_schedule_name, p.valid_from, p.valid_until,
                   p.pricing_mode, p.discount_percent, p.discount_rounding, dc.code AS discount_category_code,
                   (SELECT COUNT(*) FROM billing_rate_plan_price pr
                     WHERE pr.rate_plan_id = p.id AND pr.valid_from <= :today
                       AND (pr.valid_until IS NULL OR pr.valid_until >= :today)) AS services_count,
                   IF(p.plan_type = 'CAMPAIGN',
                      (SELECT COUNT(*) FROM billing_campaign_organization m WHERE m.rate_plan_id = p.id),
                      (SELECT COUNT(*) FROM billing_rate_plan_assignment a
                        WHERE a.rate_plan_id = p.id AND a.valid_from <= :today
                          AND (a.valid_until IS NULL OR a.valid_until >= :today))) AS companies_count
              FROM billing_rate_plan p
              JOIN catalog_rate_schedule rs ON rs.id = p.rate_schedule_id
              LEFT JOIN catalog_procedure_category dc ON dc.id = p.discount_category_id
            """;

    /** Company columns with its shared plan and own plan of today. */
    private static final String COMPANY_SELECT = """
            SELECT o.id, o.code, o.tax_id, o.tax_id_check_digit, o.legal_name, o.municipality_name, o.status,
                   sp.code AS plan_code, sp.name AS plan_name, op.code AS own_plan_code,
                   (SELECT COUNT(*) FROM billing_rate_plan_price pr
                     WHERE pr.rate_plan_id = op.id AND pr.valid_from <= :today
                       AND (pr.valid_until IS NULL OR pr.valid_until >= :today)) AS own_prices_count
              FROM organization_directory_view o
              LEFT JOIN billing_rate_plan_assignment a ON a.organization_id = o.id AND a.valid_from <= :today
                    AND (a.valid_until IS NULL OR a.valid_until >= :today)
              LEFT JOIN billing_rate_plan sp ON sp.id = a.rate_plan_id
              LEFT JOIN billing_rate_plan op ON op.organization_id = o.id
            """;

    private final JdbcClient jdbc;

    public JdbcRateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- plans

    @Override
    public Page<RatePlan> plans(RatePlanType type, String search, RecordStatus status, int page, int size,
                                LocalDate today) {
        String where = " WHERE p.plan_type = :type"
                + (search == null ? "" : " AND (p.code LIKE :search OR p.name LIKE :search)")
                + (status == null ? "" : " AND p.status = :status");
        Map<String, Object> params = new HashMap<>();
        params.put("today", Date.valueOf(today));
        params.put("type", type.name());
        if (search != null) {
            params.put("search", "%" + search + "%");
        }
        if (status != null) {
            params.put("status", status.name());
        }
        long total = jdbc.sql("SELECT COUNT(*) FROM billing_rate_plan p" + where).params(params)
                .query(Long.class).single();
        params.put("limit", size);
        params.put("offset", page * size);
        String order = type == RatePlanType.CAMPAIGN ? " ORDER BY p.valid_from DESC, p.name" : " ORDER BY p.name, p.code";
        List<RatePlan> items = jdbc.sql(PLAN_SELECT + where + order + " LIMIT :limit OFFSET :offset")
                .params(params).query((rs, row) -> plan(rs)).list();
        return new Page<>(items, page, size, total);
    }

    @Override
    public Optional<RatePlan> plan(String code, LocalDate today) {
        return jdbc.sql(PLAN_SELECT + " WHERE p.code = :code").param("code", code)
                .param("today", Date.valueOf(today)).query((rs, row) -> plan(rs)).optional();
    }

    @Override
    public Optional<RatePlan> ownPlan(long organizationId, LocalDate today) {
        return jdbc.sql(PLAN_SELECT + " WHERE p.organization_id = :organization").param("organization", organizationId)
                .param("today", Date.valueOf(today)).query((rs, row) -> plan(rs)).optional();
    }

    @Override
    public boolean planCodeExists(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM billing_rate_plan WHERE code = :code").param("code", code)
                .query(Integer.class).single() > 0;
    }

    @Override
    public boolean rateScheduleExists(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM catalog_rate_schedule WHERE code = :code AND is_active")
                .param("code", code).query(Integer.class).single() > 0;
    }

    @Override
    public boolean categoryExists(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM catalog_procedure_category WHERE code = :code").param("code", code)
                .query(Integer.class).single() > 0;
    }

    @Override
    public String defaultRateSchedule() {
        return jdbc.sql("SELECT code FROM catalog_rate_schedule WHERE is_active ORDER BY code LIMIT 1")
                .query(String.class).single();
    }

    @Override
    public long insertPlan(String code, String name, String description, RatePlanType type, Long organizationId,
                           String rateScheduleCode, UUID actor) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO billing_rate_plan (code, name, description, plan_type, organization_id,
                                                       rate_schedule_id, created_by)
                        SELECT :code, :name, :description, :type, :organization, rs.id, :actor
                          FROM catalog_rate_schedule rs WHERE rs.code = :schedule""")
                .param("code", code).param("name", name).param("description", description).param("type", type.name())
                .param("organization", organizationId).param("schedule", rateScheduleCode)
                .param("actor", UuidBytes.of(actor)).update(keys);
        return keys.getKey().longValue();
    }

    @Override
    public boolean updatePlan(long id, String name, String description, String rateScheduleCode, RecordStatus status,
                              int version, UUID actor) {
        return jdbc.sql("""
                        UPDATE billing_rate_plan
                           SET name = :name, description = :description, status = :status, updated_by = :actor,
                               rate_schedule_id = (SELECT rs.id FROM catalog_rate_schedule rs WHERE rs.code = :schedule),
                               version = version + 1
                         WHERE id = :id AND version = :version""")
                .param("name", name).param("description", description).param("status", status.name())
                .param("actor", UuidBytes.of(actor)).param("schedule", rateScheduleCode).param("id", id)
                .param("version", version).update() == 1;
    }

    @Override
    public void touchPlan(long id, UUID actor) {
        jdbc.sql("UPDATE billing_rate_plan SET updated_by = :actor WHERE id = :id")
                .param("actor", UuidBytes.of(actor)).param("id", id).update();
    }

    // ---------------------------------------------------------------- prices

    @Override
    public Map<String, Long> serviceIds(Collection<String> codes) {
        Map<String, Long> ids = new HashMap<>();
        List<String> wanted = codes.stream().filter(code -> code != null && !code.isBlank()).distinct().toList();
        if (wanted.isEmpty()) {
            return ids;
        }
        jdbc.sql("SELECT code, id FROM service_offering_directory_view WHERE code IN (:codes)").param("codes", wanted)
                .query(rs -> {
                    ids.put(rs.getString(1), rs.getLong(2));
                });
        return ids;
    }

    @Override
    public Set<Long> activeServiceIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT id FROM service_offering_directory_view WHERE id IN (:ids) AND status = 'ACTIVE'")
                .param("ids", List.copyOf(ids)).query(Long.class).list());
    }

    @Override
    public List<Period<BigDecimal>> lockPricePeriods(long planId, long serviceId) {
        return jdbc.sql("""
                        SELECT id, valid_from, valid_until, price FROM billing_rate_plan_price
                         WHERE rate_plan_id = :plan AND service_offering_id = :service FOR UPDATE""")
                .param("plan", planId).param("service", serviceId)
                .query((rs, row) -> new Period<>(rs.getLong("id"), rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_until", LocalDate.class), rs.getBigDecimal("price")))
                .list();
    }

    @Override
    public void applyPrices(long planId, long serviceId, List<Operation<BigDecimal>> operations, UUID actor) {
        byte[] by = UuidBytes.of(actor);
        for (Operation<BigDecimal> operation : operations) {
            switch (operation) {
                case Operation.Close<BigDecimal> close -> jdbc.sql(
                                "UPDATE billing_rate_plan_price SET valid_until = :until, updated_by = :by WHERE id = :id")
                        .param("until", Date.valueOf(close.validUntil())).param("by", by).param("id", close.id()).update();
                case Operation.Correct<BigDecimal> correct -> jdbc.sql(
                                "UPDATE billing_rate_plan_price SET price = :price, updated_by = :by WHERE id = :id")
                        .param("price", correct.value()).param("by", by).param("id", correct.id()).update();
                case Operation.Cancel<BigDecimal> cancel -> jdbc.sql("DELETE FROM billing_rate_plan_price WHERE id = :id")
                        .param("id", cancel.id()).update();
                case Operation.Open<BigDecimal> open -> jdbc.sql("""
                                INSERT INTO billing_rate_plan_price (rate_plan_id, service_offering_id, price, valid_from,
                                                                     valid_until, created_by)
                                VALUES (:plan, :service, :price, :from, :until, :by)""")
                        .param("plan", planId).param("service", serviceId).param("price", open.value())
                        .param("from", Date.valueOf(open.validFrom()))
                        .param("until", open.validUntil() == null ? null : Date.valueOf(open.validUntil()))
                        .param("by", by).update();
            }
        }
    }

    @Override
    public Page<PlanPrice> planPrices(long planId, PriceFilter filter) {
        String where = """
                 WHERE (s.status = 'ACTIVE' OR cur.id IS NOT NULL OR nxt.id IS NOT NULL)"""
                + (filter.onlyInPlan() ? " AND (cur.id IS NOT NULL OR nxt.id IS NOT NULL)" : "")
                + (filter.search() == null ? "" : " AND (s.code LIKE :search OR s.name LIKE :search OR s.cups_code LIKE :search)")
                + (filter.categoryCode() == null ? "" : " AND s.category_code = :category");
        String from = """
                  FROM service_offering_directory_view s
                  JOIN catalog_procedure_category c ON c.code = s.category_code
                  LEFT JOIN billing_rate_plan_price cur ON cur.rate_plan_id = :plan AND cur.service_offering_id = s.id
                        AND cur.valid_from <= :date AND (cur.valid_until IS NULL OR cur.valid_until >= :date)
                  LEFT JOIN billing_rate_plan_price nxt ON nxt.id = (
                        SELECT n.id FROM billing_rate_plan_price n
                         WHERE n.rate_plan_id = :plan AND n.service_offering_id = s.id AND n.valid_from > :date
                         ORDER BY n.valid_from LIMIT 1)
                """;
        Map<String, Object> params = new HashMap<>();
        params.put("plan", planId);
        params.put("date", Date.valueOf(filter.date()));
        if (filter.search() != null) {
            params.put("search", "%" + filter.search() + "%");
        }
        if (filter.categoryCode() != null) {
            params.put("category", filter.categoryCode());
        }
        long total = jdbc.sql("SELECT COUNT(*)" + from + where).params(params).query(Long.class).single();
        params.put("limit", filter.size());
        params.put("offset", filter.page() * filter.size());
        List<PlanPrice> items = jdbc.sql("""
                        SELECT s.code, s.name, s.category_code, c.name AS category_name, s.status,
                               cur.price, cur.valid_from, cur.valid_until, nxt.price AS next_price,
                               nxt.valid_from AS next_from
                        """ + from + where + " ORDER BY c.name, s.name, s.code LIMIT :limit OFFSET :offset")
                .params(params).query((rs, row) -> {
                    LocalDate until = rs.getObject("valid_until", LocalDate.class);
                    LocalDate nextFrom = rs.getObject("next_from", LocalDate.class);
                    BigDecimal nextPrice = rs.getBigDecimal("next_price");
                    if (nextFrom == null && until != null) {
                        nextFrom = until.plusDays(1);
                    } else if (nextFrom != null && until != null && nextFrom.isAfter(until.plusDays(1))) {
                        nextFrom = until.plusDays(1);
                        nextPrice = null;
                    }
                    return new PlanPrice(rs.getString("code"), rs.getString("name"), rs.getString("category_code"),
                            rs.getString("category_name"), rs.getString("status"), rs.getBigDecimal("price"),
                            rs.getObject("valid_from", LocalDate.class), until, nextPrice, nextFrom);
                }).list();
        return new Page<>(items, filter.page(), filter.size(), total);
    }

    @Override
    public Map<Long, BigDecimal> pricesOn(long planId, LocalDate date, String categoryCode) {
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT pr.service_offering_id, pr.price FROM billing_rate_plan_price pr
                          JOIN service_offering_directory_view s ON s.id = pr.service_offering_id
                         WHERE pr.rate_plan_id = :plan AND pr.valid_from <= :date
                           AND (pr.valid_until IS NULL OR pr.valid_until >= :date) AND s.status = 'ACTIVE'
                        """ + (categoryCode == null ? "" : " AND s.category_code = :category")
                        + " ORDER BY pr.service_offering_id")
                .param("plan", planId).param("date", Date.valueOf(date))
                .params(categoryCode == null ? Map.of() : Map.of("category", categoryCode))
                .query(rs -> {
                    prices.put(rs.getLong(1), rs.getBigDecimal(2));
                });
        return prices;
    }

    @Override
    public List<PriceHistoryEntry> priceHistory(long planId, long serviceId) {
        return jdbc.sql("""
                        SELECT price, valid_from, valid_until, created_at FROM billing_rate_plan_price
                         WHERE rate_plan_id = :plan AND service_offering_id = :service ORDER BY valid_from DESC""")
                .param("plan", planId).param("service", serviceId)
                .query((rs, row) -> new PriceHistoryEntry(rs.getBigDecimal("price"),
                        rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_until", LocalDate.class),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    // ---------------------------------------------------------------- companies and assignments

    @Override
    public Page<Company> companies(String search, int page, int size, LocalDate today) {
        String where = search == null ? ""
                : " WHERE (o.legal_name LIKE :search OR o.code LIKE :search OR o.tax_id LIKE :search)";
        Map<String, Object> params = new HashMap<>();
        params.put("today", Date.valueOf(today));
        if (search != null) {
            params.put("search", "%" + search + "%");
        }
        long total = jdbc.sql("SELECT COUNT(*) FROM organization_directory_view o" + where).params(params)
                .query(Long.class).single();
        params.put("limit", size);
        params.put("offset", page * size);
        List<Company> items = jdbc.sql(COMPANY_SELECT + where + " ORDER BY o.legal_name, o.code LIMIT :limit OFFSET :offset")
                .params(params).query((rs, row) -> company(rs)).list();
        return new Page<>(items, page, size, total);
    }

    @Override
    public Optional<Company> company(long id, LocalDate today) {
        return jdbc.sql(COMPANY_SELECT + " WHERE o.id = :id").param("id", id).param("today", Date.valueOf(today))
                .query((rs, row) -> company(rs)).optional();
    }

    @Override
    public List<Company> companiesOfPlan(long planId, LocalDate date) {
        return jdbc.sql(COMPANY_SELECT + " WHERE a.rate_plan_id = :plan ORDER BY o.legal_name, o.code")
                .param("plan", planId).param("today", Date.valueOf(date)).query((rs, row) -> company(rs)).list();
    }

    @Override
    public List<Period<Long>> lockAssignmentPeriods(long organizationId) {
        return jdbc.sql("""
                        SELECT id, valid_from, valid_until, rate_plan_id FROM billing_rate_plan_assignment
                         WHERE organization_id = :organization FOR UPDATE""")
                .param("organization", organizationId)
                .query((rs, row) -> new Period<>(rs.getLong("id"), rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_until", LocalDate.class), rs.getLong("rate_plan_id")))
                .list();
    }

    @Override
    public void applyAssignments(long organizationId, List<Operation<Long>> operations, UUID actor) {
        byte[] by = UuidBytes.of(actor);
        for (Operation<Long> operation : operations) {
            switch (operation) {
                case Operation.Close<Long> close -> jdbc.sql(
                                "UPDATE billing_rate_plan_assignment SET valid_until = :until, updated_by = :by WHERE id = :id")
                        .param("until", Date.valueOf(close.validUntil())).param("by", by).param("id", close.id()).update();
                case Operation.Correct<Long> correct -> jdbc.sql(
                                "UPDATE billing_rate_plan_assignment SET rate_plan_id = :plan, updated_by = :by WHERE id = :id")
                        .param("plan", correct.value()).param("by", by).param("id", correct.id()).update();
                case Operation.Cancel<Long> cancel -> jdbc.sql("DELETE FROM billing_rate_plan_assignment WHERE id = :id")
                        .param("id", cancel.id()).update();
                case Operation.Open<Long> open -> jdbc.sql("""
                                INSERT INTO billing_rate_plan_assignment (organization_id, rate_plan_id, valid_from,
                                                                          valid_until, created_by)
                                VALUES (:organization, :plan, :from, :until, :by)""")
                        .param("organization", organizationId).param("plan", open.value())
                        .param("from", Date.valueOf(open.validFrom()))
                        .param("until", open.validUntil() == null ? null : Date.valueOf(open.validUntil()))
                        .param("by", by).update();
            }
        }
    }

    @Override
    public List<PlanAssignment> assignments(long organizationId) {
        return jdbc.sql("""
                        SELECT a.id, p.code, p.name, p.status, a.valid_from, a.valid_until
                          FROM billing_rate_plan_assignment a JOIN billing_rate_plan p ON p.id = a.rate_plan_id
                         WHERE a.organization_id = :organization ORDER BY a.valid_from DESC""")
                .param("organization", organizationId)
                .query((rs, row) -> new PlanAssignment(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        RecordStatus.valueOf(rs.getString("status")), rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_until", LocalDate.class)))
                .list();
    }

    @Override
    public List<EffectivePrice> effectivePrices(long organizationId, LocalDate date, String search) {
        return jdbc.sql("SELECT e.*, c.name AS category_name FROM (" + EffectivePrices.RESOLVED + """
                        ) e JOIN catalog_procedure_category c ON c.code = e.category_code
                        """ + (search == null ? "" : " WHERE (e.code LIKE :search OR e.name LIKE :search)")
                        + " ORDER BY c.name, e.name, e.code")
                .param("organization", organizationId).param("date", Date.valueOf(date))
                .params(search == null ? Map.of() : Map.of("search", "%" + search + "%"))
                .query((rs, row) -> new EffectivePrice(rs.getString("code"), rs.getString("name"),
                        rs.getString("category_name"), rs.getBigDecimal("price"),
                        EffectivePrice.PriceSource.valueOf(rs.getString("source")), rs.getString("plan_code"),
                        rs.getString("plan_name"), rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_until", LocalDate.class)))
                .list();
    }

    @Override
    public java.util.Optional<com.siiso.billing.domain.CostSharingRule> costSharingRule(long organizationId, int level,
                                                                                       LocalDate date) {
        return jdbc.sql("""
                        SELECT moderator_fee, copay_percent, copay_cap_per_event FROM billing_cost_sharing_rule
                         WHERE organization_id = :organization AND cost_sharing_level = :level
                           AND valid_from <= :date AND (valid_until IS NULL OR valid_until >= :date)
                         ORDER BY valid_from DESC LIMIT 1""")
                .param("organization", organizationId).param("level", level).param("date", Date.valueOf(date))
                .query((rs, row) -> new com.siiso.billing.domain.CostSharingRule(rs.getBigDecimal(1),
                        rs.getBigDecimal(2), rs.getBigDecimal(3)))
                .optional();
    }

    // ---------------------------------------------------------------- campaigns

    @Override
    public String nextCampaignCode() {
        int next = jdbc.sql("""
                        SELECT COALESCE(MAX(CAST(SUBSTRING(code, 6) AS UNSIGNED)), 0) + 1 FROM billing_rate_plan
                         WHERE code LIKE 'CAMP-%' FOR UPDATE""")
                .query(Integer.class).single();
        return "CAMP-" + next;
    }

    @Override
    public void insertCampaign(String code, CampaignData data, UUID actor) {
        jdbc.sql("""
                        INSERT INTO billing_rate_plan (code, name, description, plan_type, rate_schedule_id, valid_from,
                                                       valid_until, pricing_mode, discount_percent, discount_rounding,
                                                       discount_category_id, created_by)
                        SELECT :code, :name, :description, 'CAMPAIGN', rs.id, :from, :until, :mode, :percent, :rounding,
                               (SELECT dc.id FROM catalog_procedure_category dc WHERE dc.code = :category), :actor
                          FROM catalog_rate_schedule rs WHERE rs.code = :schedule""")
                .param("code", code).params(campaignParams(data)).param("actor", UuidBytes.of(actor)).update();
    }

    @Override
    public boolean updateCampaign(long id, CampaignData data, RecordStatus status, int version, UUID actor) {
        return jdbc.sql("""
                        UPDATE billing_rate_plan
                           SET name = :name, description = :description, valid_from = :from, valid_until = :until,
                               pricing_mode = :mode, discount_percent = :percent, discount_rounding = :rounding,
                               discount_category_id = (SELECT dc.id FROM catalog_procedure_category dc WHERE dc.code = :category),
                               rate_schedule_id = (SELECT rs.id FROM catalog_rate_schedule rs WHERE rs.code = :schedule),
                               status = :status, updated_by = :actor, version = version + 1
                         WHERE id = :id AND version = :version AND plan_type = 'CAMPAIGN'""")
                .params(campaignParams(data)).param("status", status.name()).param("actor", UuidBytes.of(actor))
                .param("id", id).param("version", version).update() == 1;
    }

    private static Map<String, Object> campaignParams(CampaignData data) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", data.name());
        params.put("description", data.description());
        params.put("schedule", data.rateScheduleCode());
        params.put("from", Date.valueOf(data.validFrom()));
        params.put("until", Date.valueOf(data.validUntil()));
        params.put("mode", data.pricingMode().name());
        params.put("percent", data.discountPercent());
        params.put("rounding", data.discountRounding() == null ? null : data.discountRounding().name());
        params.put("category", data.discountCategoryCode());
        return params;
    }

    @Override
    public List<Company> campaignCompanies(long planId, LocalDate today) {
        return jdbc.sql(COMPANY_SELECT + """
                         WHERE o.id IN (SELECT m.organization_id FROM billing_campaign_organization m
                                         WHERE m.rate_plan_id = :plan)
                         ORDER BY o.legal_name, o.code""")
                .param("plan", planId).param("today", Date.valueOf(today)).query((rs, row) -> company(rs)).list();
    }

    @Override
    public boolean addCampaignCompany(long planId, long organizationId, UUID actor) {
        return jdbc.sql("""
                        INSERT IGNORE INTO billing_campaign_organization (rate_plan_id, organization_id, created_by)
                        VALUES (:plan, :organization, :actor)""")
                .param("plan", planId).param("organization", organizationId).param("actor", UuidBytes.of(actor))
                .update() == 1;
    }

    @Override
    public boolean removeCampaignCompany(long planId, long organizationId) {
        return jdbc.sql("DELETE FROM billing_campaign_organization WHERE rate_plan_id = :plan AND organization_id = :organization")
                .param("plan", planId).param("organization", organizationId).update() == 1;
    }

    @Override
    public List<String> overlappingCampaigns(long organizationId, LocalDate from, LocalDate until, long excludePlanId) {
        return jdbc.sql("""
                        SELECT CONCAT(p.name, ' (', DATE_FORMAT(p.valid_from, '%d/%m/%Y'), ' – ',
                                      DATE_FORMAT(p.valid_until, '%d/%m/%Y'), ')')
                          FROM billing_campaign_organization m
                          JOIN billing_rate_plan p ON p.id = m.rate_plan_id
                         WHERE m.organization_id = :organization AND p.id <> :exclude AND p.status = 'ACTIVE'
                           AND p.valid_from <= :until AND p.valid_until >= :from""")
                .param("organization", organizationId).param("exclude", excludePlanId)
                .param("from", Date.valueOf(from)).param("until", Date.valueOf(until)).query(String.class).list();
    }

    // ---------------------------------------------------------------- legacy write-back queue

    @Override
    public void queuePlanCompanies(long planId, LocalDate date) {
        jdbc.sql("""
                        INSERT INTO billing_legacy_outbox (organization_id)
                        SELECT organization_id FROM billing_rate_plan WHERE id = :plan AND organization_id IS NOT NULL
                        UNION
                        SELECT organization_id FROM billing_rate_plan_assignment
                         WHERE rate_plan_id = :plan AND valid_from <= :date AND (valid_until IS NULL OR valid_until >= :date)
                        UNION
                        SELECT m.organization_id FROM billing_campaign_organization m
                          JOIN billing_rate_plan p ON p.id = m.rate_plan_id
                         WHERE m.rate_plan_id = :plan AND p.valid_from <= :date AND p.valid_until >= :date""")
                .param("plan", planId).param("date", Date.valueOf(date)).update();
    }

    @Override
    public void queueCompany(long organizationId) {
        jdbc.sql("INSERT INTO billing_legacy_outbox (organization_id) VALUES (:organization)")
                .param("organization", organizationId).update();
    }

    // ---------------------------------------------------------------- mapping

    private static RatePlan plan(ResultSet rs) throws SQLException {
        return new RatePlan(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                RatePlanType.valueOf(rs.getString("plan_type")), rs.getObject("organization_id", Long.class),
                rs.getString("rate_schedule_code"), rs.getString("rate_schedule_name"),
                RecordStatus.valueOf(rs.getString("status")), rs.getInt("version"), rs.getInt("services_count"),
                rs.getInt("companies_count"), rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_until", LocalDate.class),
                rs.getString("pricing_mode") == null ? null : PricingMode.valueOf(rs.getString("pricing_mode")),
                rs.getBigDecimal("discount_percent"),
                rs.getString("discount_rounding") == null ? null : Rounding.valueOf(rs.getString("discount_rounding")),
                rs.getString("discount_category_code"));
    }

    private static Company company(ResultSet rs) throws SQLException {
        return new Company(rs.getLong("id"), rs.getString("code"), rs.getString("tax_id"),
                rs.getString("tax_id_check_digit"), rs.getString("legal_name"), rs.getString("municipality_name"),
                rs.getString("status"), rs.getString("plan_code"), rs.getString("plan_name"),
                rs.getString("own_plan_code"), rs.getInt("own_prices_count"));
    }
}
