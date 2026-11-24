package com.siiso.billing.support;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** "Today" is fixed in the tests: 2026-12-01 in Colombia. */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClock {

    public static final LocalDate TODAY = LocalDate.of(2026, 12, 1);
    public static final ZoneId ZONE = ZoneId.of("America/Bogota");

    public static Clock clock() {
        return Clock.fixed(TODAY.atStartOfDay(ZONE).plusHours(9).toInstant(), ZONE);
    }

    @Bean
    @Primary
    Clock fixedClock() {
        return clock();
    }
}
