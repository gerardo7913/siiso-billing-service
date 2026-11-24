package com.siiso.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One period of the price of a service in a plan. */
public record PriceHistoryEntry(BigDecimal price, LocalDate validFrom, LocalDate validUntil, Instant createdAt) {
}
