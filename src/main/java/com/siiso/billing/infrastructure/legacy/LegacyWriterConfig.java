package com.siiso.billing.infrastructure.legacy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Connection that writes each company's prices of the day back to the legacy database while it is
 * being deprecated (ADR 0006). A dedicated user with SELECT / INSERT / UPDATE on {@code tarifa} and
 * SELECT (CDGEMPRESA, DEPENDENCIA_EMPRESA) / UPDATE (DEPENDENCIA_EMPRESA) on {@code empresa}. Enabled
 * only when {@code siiso.legacy.writer.username} is set; reads still use the read-only connection.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnExpression("!'${siiso.legacy.datasource.url:}'.isBlank() and !'${siiso.legacy.writer.username:}'.isBlank()")
class LegacyWriterConfig {

    static final String WRITER = "legacyWriter";

    @Bean(defaultCandidate = false)
    @Qualifier(WRITER)
    HikariDataSource legacyWriterDataSource(@Value("${siiso.legacy.datasource.url}") String url,
                                            @Value("${siiso.legacy.writer.username}") String username,
                                            @Value("${siiso.legacy.writer.password:}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("legacy-writer");
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(2);
        return dataSource;
    }

    @Bean(defaultCandidate = false)
    @Qualifier(WRITER)
    JdbcClient legacyWriterJdbcClient(@Qualifier(WRITER) HikariDataSource dataSource) {
        return JdbcClient.create(dataSource);
    }
}
