package com.siiso.billing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The price a company pays for a service on a date: the one of its campaign running then, otherwise
 * its own price, otherwise the one of its shared plan ({@code source} CAMPAIGN, OWN or PLAN, with the
 * plan that gives it; for a campaign, validity is the campaign's period).
 */
public record EffectivePrice(String serviceCode, String serviceName, String categoryName, BigDecimal price,
                             PriceSource source, String planCode, String planName, LocalDate validFrom,
                             LocalDate validUntil) {

    public enum PriceSource {
        CAMPAIGN, OWN, PLAN
    }
}
