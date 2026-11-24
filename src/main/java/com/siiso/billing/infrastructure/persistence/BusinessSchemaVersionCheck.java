package com.siiso.billing.infrastructure.persistence;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * siiso_business is migrated by siiso-business-db, not by this service (siiso-docs ADR 0016). At
 * startup this checks that the database is at least at the version this service needs, so an
 * installation that skipped the migration step fails fast with a clear message.
 */
@Component
class BusinessSchemaVersionCheck implements InitializingBean {

    private final JdbcClient jdbc;
    private final int requiredVersion;

    BusinessSchemaVersionCheck(JdbcClient jdbc, @Value("${siiso.business-db.required-version}") int requiredVersion) {
        this.jdbc = jdbc;
        this.requiredVersion = requiredVersion;
    }

    @Override
    public void afterPropertiesSet() {
        int current;
        try {
            current = jdbc.sql("SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0) FROM flyway_schema_history WHERE success = TRUE")
                    .query(Integer.class).single();
        } catch (RuntimeException e) {
            throw new IllegalStateException("siiso_business has not been migrated: run siiso-business-db (migrate) first", e);
        }
        if (current < requiredVersion) {
            throw new IllegalStateException("siiso_business is at version " + current + "; this service needs "
                    + requiredVersion + " or later: run siiso-business-db (migrate) first");
        }
    }
}
