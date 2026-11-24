package com.siiso.billing.infrastructure.legacy;

import com.siiso.billing.infrastructure.persistence.EffectivePrices;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes each queued company's prices of the day to the legacy database (ADR 0006, siiso-docs ADR
 * 0022). The desktop app prices an order with the {@code tarifa} rows of the company's
 * DEPENDENCIA_EMPRESA, so the company gets its own rows (one per priced service, HABI 1; the rest of
 * its rows HABI 0) and its DEPENDENCIA_EMPRESA becomes itself. Companies that depended on it in the
 * legacy database get the same treatment, so none of them keeps reading prices that are no longer
 * theirs. Idempotent; the legacy tables are MyISAM (no transactions). Failed entries are retried up
 * to 10 times and keep their last error.
 */
@Component
@ConditionalOnExpression("!'${siiso.legacy.datasource.url:}'.isBlank() and !'${siiso.legacy.writer.username:}'.isBlank()")
public class LegacyRateWriteBack {

    private static final Logger log = LoggerFactory.getLogger(LegacyRateWriteBack.class);
    static final int MAX_ATTEMPTS = 10;

    private final JdbcClient writer;
    private final JdbcClient target;
    private final Clock clock;

    LegacyRateWriteBack(@Qualifier(LegacyWriterConfig.WRITER) JdbcClient writer, JdbcClient target, Clock clock) {
        this.writer = writer;
        this.target = target;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${siiso.legacy.writer.interval:10s}", initialDelayString = "5s")
    public void publishPending() {
        record Entry(long organizationId, long lastId) {
        }
        List<Entry> pending = target.sql("""
                        SELECT organization_id, MAX(id) FROM billing_legacy_outbox
                         WHERE processed_at IS NULL AND attempts < :max
                         GROUP BY organization_id ORDER BY MIN(id) LIMIT 50""")
                .param("max", MAX_ATTEMPTS)
                .query((rs, n) -> new Entry(rs.getLong(1), rs.getLong(2)))
                .list();
        LocalDate today = LocalDate.now(clock);
        for (Entry entry : pending) {
            try {
                apply(entry.organizationId(), today, new HashSet<>());
                target.sql("""
                                UPDATE billing_legacy_outbox SET processed_at = CURRENT_TIMESTAMP(3), last_error = NULL
                                 WHERE organization_id = :organization AND processed_at IS NULL AND id <= :last""")
                        .param("organization", entry.organizationId()).param("last", entry.lastId()).update();
            } catch (RuntimeException e) {
                log.warn("Legacy rate write-back failed for company {}", entry.organizationId(), e);
                String message = String.valueOf(e.getMessage());
                target.sql("""
                                UPDATE billing_legacy_outbox SET attempts = attempts + 1, last_error = :error
                                 WHERE organization_id = :organization AND processed_at IS NULL AND id <= :last""")
                        .param("error", message.length() > 500 ? message.substring(0, 500) : message)
                        .param("organization", entry.organizationId()).param("last", entry.lastId()).update();
            }
        }
    }

    /** Writes one company and the legacy companies that depended on it. */
    void apply(long organizationId, LocalDate today, Set<String> done) {
        String code = target.sql("SELECT code FROM organization_directory_view WHERE id = :id")
                .param("id", organizationId).query(String.class).optional().orElse(null);
        if (code == null || !done.add(code)) {
            return;
        }
        record Price(String serviceCode, BigDecimal price, String rateSchedule) {
        }
        List<Price> prices = target.sql(EffectivePrices.OF_COMPANY)
                .param("organization", organizationId).param("date", Date.valueOf(today))
                .query((rs, n) -> new Price(rs.getString("code"), rs.getBigDecimal("price"),
                        rs.getString("rate_schedule_code")))
                .list();
        List<String> dependents = writer.sql("""
                        SELECT CDGEMPRESA FROM empresa WHERE DEPENDENCIA_EMPRESA = :code AND CDGEMPRESA <> :code""")
                .param("code", code).query(String.class).list();

        writer.sql("""
                        UPDATE empresa SET DEPENDENCIA_EMPRESA = :code
                         WHERE CDGEMPRESA = :code AND (DEPENDENCIA_EMPRESA IS NULL OR DEPENDENCIA_EMPRESA <> :code)""")
                .param("code", code).update();
        Set<String> priced = new HashSet<>();
        for (Price price : prices) {
            priced.add(price.serviceCode() + "|" + price.rateSchedule());
            writer.sql("""
                            INSERT INTO tarifa (CDGEXAMEN, CDGEMPRESA, TIPOTARIFA, VALORTARIFA, NIVELCOMPLEJIDAD, UVR, HABI)
                            VALUES (:service, :company, :schedule, :price, '1', '0', '1')
                            ON DUPLICATE KEY UPDATE VALORTARIFA = VALUES(VALORTARIFA), HABI = '1'""")
                    .param("service", price.serviceCode()).param("company", code)
                    .param("schedule", price.rateSchedule()).param("price", price.price()).update();
        }
        record Row(String service, String schedule) {
        }
        List<Row> enabled = writer.sql("SELECT CDGEXAMEN, TIPOTARIFA FROM tarifa WHERE CDGEMPRESA = :company AND HABI = '1'")
                .param("company", code).query((rs, n) -> new Row(rs.getString(1), rs.getString(2))).list();
        for (Row row : enabled) {
            if (!priced.contains(row.service() + "|" + row.schedule())) {
                writer.sql("""
                                UPDATE tarifa SET HABI = '0'
                                 WHERE CDGEMPRESA = :company AND CDGEXAMEN = :service AND TIPOTARIFA = :schedule""")
                        .param("company", code).param("service", row.service()).param("schedule", row.schedule())
                        .update();
            }
        }
        for (String dependent : dependents) {
            target.sql("SELECT id FROM organization_directory_view WHERE code = :code").param("code", dependent)
                    .query(Long.class).optional().ifPresent(id -> apply(id, today, done));
        }
    }
}
