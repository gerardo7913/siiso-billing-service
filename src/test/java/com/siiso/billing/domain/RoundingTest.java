package com.siiso.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RoundingTest {

    @Test
    void increasesAndRoundsAsTheDesktopDid() {
        BigDecimal price = new BigDecimal("43750.00");
        BigDecimal ten = BigDecimal.TEN;

        assertThat(Rounding.NONE.increase(price, ten)).isEqualByComparingTo("48125");
        assertThat(Rounding.HUNDRED.increase(price, ten)).isEqualByComparingTo("48100");
        assertThat(Rounding.THOUSAND.increase(price, ten)).isEqualByComparingTo("48000");
        assertThat(Rounding.HUNDRED.increase(price, new BigDecimal("-10"))).isEqualByComparingTo("39400");
    }
}
