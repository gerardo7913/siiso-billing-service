package com.siiso.billing.infrastructure.legacy;

import com.siiso.billing.application.RateStore;
import com.siiso.billing.domain.PeriodPlanner;
import com.siiso.billing.domain.RatePlan;
import com.siiso.billing.domain.RatePlanType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Load of the legacy rates (siiso-docs ADR 0022). The desktop app prices a company with the
 * {@code tarifa} rows (HABI 1) of its DEPENDENCIA_EMPRESA, so each group of companies that depend on
 * the same company becomes a shared plan {@code LEGACY-<code>} ("Migrada – <name>") with those prices
 * from today, and every company of the group gets it from today. Staff later rename the plans
 * (Diamante, Oro…) and reassign companies.
 *
 * <p>This service owns the rates: a company with an own plan or a plan assigned by staff, and a plan
 * edited here, are never touched; the rest is refreshed on each start while the desktop app is being
 * blocked. Rows of codes that are not companies (9999, 9000) and the own rows of companies that depend
 * on another (the desktop app does not use them) are only counted. Idempotent, one transaction,
 * read-only legacy connection, logs a report; it queues nothing for the write-back.
 */
@Component
@ConditionalOnExpression("${siiso.legacy-import.enabled:false} and !'${siiso.legacy.datasource.url:}'.isBlank()")
class LegacyRateImport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyRateImport.class);
    static final String PLAN_PREFIX = "LEGACY-";

    private final JdbcClient legacy;
    private final JdbcClient target;
    private final RateStore store;
    private final TransactionTemplate transaction;
    private final Clock clock;

    LegacyRateImport(@Qualifier(LegacyDatabaseConfig.LEGACY) JdbcClient legacy, JdbcClient target, RateStore store,
                     TransactionTemplate transaction, Clock clock) {
        this.legacy = legacy;
        this.target = target;
        this.store = store;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Report report = transaction.execute(status -> {
            Report result = new Report();
            importRates(result);
            importCostSharing(result);
            return result;
        });
        log.info("Legacy rate import done: {}", report);
    }

    record LegacyPrice(BigDecimal price, String rateSchedule) {
    }

    /**
     * The legacy descuento table (moderator fee and copay by company and level) as cost-sharing rules
     * from today (siiso-docs ADR 0024). Only for a company and level that have no rule yet, so it never
     * overwrites a rule changed here.
     */
    void importCostSharing(Report report) {
        LocalDate today = LocalDate.now(clock);
        legacy.sql("SELECT CDGEMPRESA, CODNIVEL, CUOTA_MODERADA, CUOTA_COPAGO FROM descuento").query(rs -> {
            String level = rs.getString("CODNIVEL") == null ? "" : rs.getString("CODNIVEL").trim();
            if (!level.matches("[1-3]")) {
                report.skipped.add("descuento " + rs.getString("CDGEMPRESA") + "/" + level);
                return;
            }
            int inserted = target.sql("""
                            INSERT INTO billing_cost_sharing_rule (organization_id, cost_sharing_level, moderator_fee,
                                                                   copay_percent, valid_from)
                            SELECT o.id, :level, :fee, :copay, :today FROM organization_directory_view o
                             WHERE o.code = :company
                               AND NOT EXISTS (SELECT 1 FROM billing_cost_sharing_rule r
                                                WHERE r.organization_id = o.id AND r.cost_sharing_level = :level)""")
                    .param("level", Integer.parseInt(level))
                    .param("fee", java.math.BigDecimal.valueOf(rs.getDouble("CUOTA_MODERADA")).setScale(2, java.math.RoundingMode.HALF_UP))
                    .param("copay", java.math.BigDecimal.valueOf(rs.getDouble("CUOTA_COPAGO")).setScale(2, java.math.RoundingMode.HALF_UP))
                    .param("today", java.sql.Date.valueOf(today))
                    .param("company", rs.getString("CDGEMPRESA").trim())
                    .update();
            report.costSharingRules += inserted;
        });
    }

    void importRates(Report report) {
        LocalDate today = LocalDate.now(clock);
        Map<String, String> dependencyOf = new HashMap<>();
        legacy.sql("SELECT CDGEMPRESA, DEPENDENCIA_EMPRESA FROM empresa").query(rs -> {
            String company = rs.getString(1).trim();
            String dependency = rs.getString(2);
            dependencyOf.put(company, dependency == null || dependency.isBlank() ? company : dependency.trim());
        });
        Map<String, Long> companyIds = new HashMap<>();
        Map<String, String> companyNames = new HashMap<>();
        target.sql("SELECT code, id, legal_name FROM organization_directory_view").query(rs -> {
            companyIds.put(rs.getString(1), rs.getLong(2));
            companyNames.put(rs.getString(1), rs.getString(3));
        });
        Map<String, Long> serviceIds = new HashMap<>();
        target.sql("SELECT code, id FROM service_offering_directory_view").query(rs -> {
            serviceIds.put(rs.getString(1), rs.getLong(2));
        });
        Set<String> schedules = new HashSet<>(target.sql("SELECT code FROM catalog_rate_schedule").query(String.class).list());

        // Price lists by owner company: the first rate schedule of each service wins, as in the desktop app.
        Map<String, Map<String, LegacyPrice>> lists = new TreeMap<>();
        legacy.sql("""
                        SELECT CDGEXAMEN, CDGEMPRESA, TIPOTARIFA, VALORTARIFA FROM tarifa
                         WHERE HABI = '1' AND VALORTARIFA IS NOT NULL ORDER BY CDGEMPRESA, CDGEXAMEN, TIPOTARIFA""")
                .query(rs -> {
                    String service = rs.getString(1).trim();
                    String company = rs.getString(2).trim();
                    if (!dependencyOf.containsKey(company)) {
                        report.rowsOfUnknownCompanies.merge(company, 1, Integer::sum);
                        return;
                    }
                    if (!dependencyOf.get(company).equals(company)) {
                        report.unusedRowsOfDependentCompanies++;
                        return;
                    }
                    if (!serviceIds.containsKey(service)) {
                        report.unknownServices.add(service);
                        return;
                    }
                    String schedule = schedules.contains(rs.getString(3)) ? rs.getString(3) : store.defaultRateSchedule();
                    BigDecimal price = rs.getBigDecimal(4).setScale(2, RoundingMode.HALF_UP);
                    lists.computeIfAbsent(company, key -> new LinkedHashMap<>())
                            .putIfAbsent(service, new LegacyPrice(price, schedule));
                });

        Set<Long> handledHere = new HashSet<>(target.sql("""
                        SELECT organization_id FROM billing_rate_plan WHERE organization_id IS NOT NULL
                        UNION
                        SELECT organization_id FROM billing_rate_plan_assignment
                         WHERE created_by IS NOT NULL OR updated_by IS NOT NULL""")
                .query(Long.class).list());

        Map<String, Long> plans = new HashMap<>();
        for (Map.Entry<String, Long> company : new TreeMap<>(companyIds).entrySet()) {
            if (handledHere.contains(company.getValue())) {
                report.companiesKept++;
                continue;
            }
            String owner = dependencyOf.getOrDefault(company.getKey(), company.getKey());
            Map<String, LegacyPrice> list = lists.get(owner);
            if (list == null || list.isEmpty()) {
                report.companiesWithoutRates.add(company.getKey());
                continue;
            }
            Long planId = plans.computeIfAbsent(owner,
                    key -> plan(key, companyNames.getOrDefault(key, "empresa " + key), list, serviceIds, today, report));
            var operations = PeriodPlanner.plan(store.lockAssignmentPeriods(company.getValue()), today, planId, today);
            store.applyAssignments(company.getValue(), operations, null);
            if (!operations.isEmpty()) {
                report.companiesAssigned++;
            }
        }
    }

    /** Creates or refreshes the shared plan of one price list; returns its id. */
    private long plan(String owner, String ownerName, Map<String, LegacyPrice> list, Map<String, Long> serviceIds,
                      LocalDate today, Report report) {
        String code = PLAN_PREFIX + owner;
        RatePlan plan = store.plan(code, today).orElse(null);
        if (plan == null) {
            String schedule = list.values().stream().map(LegacyPrice::rateSchedule)
                    .collect(java.util.stream.Collectors.groupingBy(s -> s, TreeMap::new, java.util.stream.Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            String name = "Migrada – " + ownerName.trim();
            store.insertPlan(code, name.length() > 100 ? name.substring(0, 100) : name,
                    "Tarifa del sistema anterior (empresa " + owner + ")", RatePlanType.SHARED, null, schedule, null);
            plan = store.plan(code, today).orElseThrow();
            report.plansCreated++;
        } else if (editedHere(plan.id())) {
            report.plansKept.add(code);
            return plan.id();
        }
        int changed = 0;
        Set<Long> listed = new HashSet<>();
        for (Map.Entry<String, LegacyPrice> price : list.entrySet()) {
            long serviceId = serviceIds.get(price.getKey());
            listed.add(serviceId);
            var operations = PeriodPlanner.plan(store.lockPricePeriods(plan.id(), serviceId), today,
                    price.getValue().price(), today);
            store.applyPrices(plan.id(), serviceId, operations, null);
            changed += operations.isEmpty() ? 0 : 1;
        }
        for (Long serviceId : new ArrayList<>(store.pricesOn(plan.id(), today, null).keySet())) {
            if (!listed.contains(serviceId)) {
                var operations = PeriodPlanner.plan(store.lockPricePeriods(plan.id(), serviceId), today,
                        (BigDecimal) null, today);
                store.applyPrices(plan.id(), serviceId, operations, null);
                changed += operations.isEmpty() ? 0 : 1;
            }
        }
        report.pricesWritten += changed;
        return plan.id();
    }

    private boolean editedHere(long planId) {
        return target.sql("""
                        SELECT (SELECT COUNT(*) FROM billing_rate_plan WHERE id = :plan AND updated_by IS NOT NULL)
                             + (SELECT COUNT(*) FROM billing_rate_plan_price
                                 WHERE rate_plan_id = :plan AND (created_by IS NOT NULL OR updated_by IS NOT NULL))""")
                .param("plan", planId).query(Integer.class).single() > 0;
    }

    static final class Report {
        int plansCreated;
        int pricesWritten;
        int companiesAssigned;
        int companiesKept;
        int unusedRowsOfDependentCompanies;
        int costSharingRules;
        final List<String> skipped = new java.util.ArrayList<>();
        final Set<String> plansKept = new TreeSet<>();
        final Set<String> companiesWithoutRates = new TreeSet<>();
        final Set<String> unknownServices = new TreeSet<>();
        final Map<String, Integer> rowsOfUnknownCompanies = new TreeMap<>();

        @Override
        public String toString() {
            return "plansCreated=" + plansCreated + ", pricesWritten=" + pricesWritten
                    + ", companiesAssigned=" + companiesAssigned + ", companiesKept(handled here)=" + companiesKept
                    + ", plansKept(edited here)=" + plansKept + ", companiesWithoutRates=" + companiesWithoutRates.size()
                    + ", unusedRowsOfDependentCompanies=" + unusedRowsOfDependentCompanies
                    + ", rowsOfUnknownCompanies=" + rowsOfUnknownCompanies + ", unknownServices=" + unknownServices
                    + ", costSharingRules=" + costSharingRules + ", skipped=" + skipped;
        }
    }
}
