package com.siiso.billing.domain;

import java.math.BigDecimal;

/**
 * What a payer charges its affiliate at an attention (siiso-docs ADR 0024): a fixed moderator fee and a
 * copay percent of the chargeable services, with an optional cap per order.
 *
 * @param copayCapPerEvent {@code null} when there is no cap
 */
public record CostSharingRule(BigDecimal moderatorFee, BigDecimal copayPercent, BigDecimal copayCapPerEvent) {
}
