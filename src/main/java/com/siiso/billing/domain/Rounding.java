package com.siiso.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rounding of a bulk increase, as the desktop offered it: to the peso, to 100 or to 1,000 pesos. */
public enum Rounding {
    NONE(BigDecimal.ONE), HUNDRED(BigDecimal.valueOf(100)), THOUSAND(BigDecimal.valueOf(1000));

    private final BigDecimal unit;

    Rounding(BigDecimal unit) {
        this.unit = unit;
    }

    public BigDecimal apply(BigDecimal amount) {
        return amount.divide(unit, 0, RoundingMode.HALF_UP).multiply(unit).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** {@code price} increased by {@code percent} (negative = decrease), rounded; never below zero. */
    public BigDecimal increase(BigDecimal price, BigDecimal percent) {
        BigDecimal raised = price.add(price.multiply(percent).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        return apply(raised.max(BigDecimal.ZERO));
    }
}
