package com.siiso.billing.infrastructure.legacy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Read-only access to the legacy database ({@code saludocupacional}), where the clinic's current
 * system still writes orders, clinical records and results.
 *
 * <p>This is the anti-corruption layer's entry point: only adapters in this package may use the
 * {@link #LEGACY} beans, and they must map legacy tables to the clean domain model. When a new
 * service owns that data, only these adapters change.
 *
 * <p>Enabled only when {@code siiso.legacy.datasource.url} is set. The beans are not default
 * candidates, so they never replace the service's own DataSource.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${siiso.legacy.datasource.url:}'.isBlank()")
public class LegacyDatabaseConfig {

    public static final String LEGACY = "legacy";

    @Bean(defaultCandidate = false)
    @Qualifier(LEGACY)
    @ConfigurationProperties("siiso.legacy.datasource")
    DataSourceProperties legacyDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(defaultCandidate = false)
    @Qualifier(LEGACY)
    @ConfigurationProperties("siiso.legacy.datasource.hikari")
    HikariDataSource legacyDataSource(@Qualifier(LEGACY) DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        dataSource.setPoolName("legacy");
        // Defense in depth: the DB user must also be read-only (GRANT SELECT only).
        dataSource.setReadOnly(true);
        return dataSource;
    }

    @Bean(defaultCandidate = false)
    @Qualifier(LEGACY)
    JdbcClient legacyJdbcClient(@Qualifier(LEGACY) HikariDataSource dataSource) {
        return JdbcClient.create(dataSource);
    }
}
