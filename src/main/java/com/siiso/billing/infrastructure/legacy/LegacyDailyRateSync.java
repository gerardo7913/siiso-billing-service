package com.siiso.billing.infrastructure.legacy;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The legacy database has no validity dates: it only holds today's price. Every few minutes this
 * queues, for each day since the last run, the companies whose prices, shared plan or campaign start
 * or end that day (siiso-docs ADR 0022). The first run only records the day (the import left the legacy
 * database as it was). Runs only when a legacy database is configured.
 */
@Component
@ConditionalOnExpression("!'${siiso.legacy.datasource.url:}'.isBlank()")
public class LegacyDailyRateSync {

    private static final String AFFECTED_ON = """
            INSERT INTO billing_legacy_outbox (organization_id)
            SELECT DISTINCT organization_id FROM (
                SELECT a.organization_id FROM billing_rate_plan_assignment a
                 WHERE a.valid_from = :day OR a.valid_until = :yesterday
                UNION
                SELECT p.organization_id FROM billing_rate_plan p
                  JOIN billing_rate_plan_price pr ON pr.rate_plan_id = p.id
                 WHERE p.organization_id IS NOT NULL AND (pr.valid_from = :day OR pr.valid_until = :yesterday)
                UNION
                SELECT a.organization_id FROM billing_rate_plan_price pr
                  JOIN billing_rate_plan_assignment a ON a.rate_plan_id = pr.rate_plan_id
                       AND a.valid_from <= :day AND (a.valid_until IS NULL OR a.valid_until >= :day)
                 WHERE pr.valid_from = :day OR pr.valid_until = :yesterday
                UNION
                SELECT m.organization_id FROM billing_campaign_organization m
                  JOIN billing_rate_plan p ON p.id = m.rate_plan_id
                 WHERE p.valid_from = :day OR p.valid_until = :yesterday
                UNION
                SELECT m.organization_id FROM billing_rate_plan_price pr
                  JOIN billing_rate_plan p ON p.id = pr.rate_plan_id AND p.plan_type = 'CAMPAIGN'
                       AND p.valid_from <= :day AND p.valid_until >= :day
                  JOIN billing_campaign_organization m ON m.rate_plan_id = p.id
                 WHERE pr.valid_from = :day OR pr.valid_until = :yesterday
            ) affected""";

    private final JdbcClient target;
    private final Clock clock;

    LegacyDailyRateSync(JdbcClient target, Clock clock) {
        this.target = target;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${siiso.legacy.daily-sync.interval:5m}", initialDelayString = "15s")
    @Transactional
    public void queueDatedChanges() {
        LocalDate today = LocalDate.now(clock);
        LocalDate last = target.sql("SELECT last_run_date FROM billing_legacy_daily_sync WHERE id = 1 FOR UPDATE")
                .query((rs, n) -> rs.getObject(1, LocalDate.class)).optional().orElse(null);
        if (last != null && !last.isBefore(today)) {
            return;
        }
        if (last != null) {
            for (LocalDate day = last.plusDays(1); !day.isAfter(today); day = day.plusDays(1)) {
                target.sql(AFFECTED_ON).param("day", Date.valueOf(day)).param("yesterday", Date.valueOf(day.minusDays(1)))
                        .update();
            }
        }
        target.sql("UPDATE billing_legacy_daily_sync SET last_run_date = :today WHERE id = 1")
                .param("today", Date.valueOf(today)).update();
    }
}
