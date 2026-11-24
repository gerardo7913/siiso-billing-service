package com.siiso.billing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A rate plan as stored. {@code organizationId} only for own plans of a company; the period and the
 * pricing only for campaigns. Counts are of services with a price valid today and of companies that
 * have the plan today (for a campaign: the companies chosen for it).
 */
public record RatePlan(long id, String code, String name, String description, RatePlanType planType,
                       Long organizationId, String rateScheduleCode, String rateScheduleName, RecordStatus status,
                       int version, int servicesCount, int companiesCount, LocalDate validFrom, LocalDate validUntil,
                       PricingMode pricingMode, BigDecimal discountPercent, Rounding discountRounding,
                       String discountCategoryCode) {
}
