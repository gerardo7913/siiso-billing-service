package com.siiso.billing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A service of the portfolio in a plan on a date: the price valid then ({@code null} = not in the
 * plan) and the next scheduled change, if any ({@code nextPrice} {@code null} with a date = it ends).
 */
public record PlanPrice(String serviceCode, String serviceName, String categoryCode, String categoryName,
                        String serviceStatus, BigDecimal price, LocalDate validFrom, LocalDate validUntil,
                        BigDecimal nextPrice, LocalDate nextFrom) {
}
