package com.siiso.billing.domain;

/** How a campaign prices: its own prices per service, or a percentage off the company's normal price. */
public enum PricingMode {
    FIXED_PRICES, DISCOUNT
}
