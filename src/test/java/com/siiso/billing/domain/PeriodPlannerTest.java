package com.siiso.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siiso.billing.domain.PeriodPlanner.Operation;
import com.siiso.billing.domain.PeriodPlanner.Period;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PeriodPlannerTest {

    static final LocalDate TODAY = LocalDate.of(2026, 12, 1);

    static Period<BigDecimal> period(long id, String from, String until, String price) {
        return new Period<>(id, LocalDate.parse(from), until == null ? null : LocalDate.parse(until), new BigDecimal(price));
    }

    @Test
    void aNewPriceClosesTheCurrentOneTheDayBefore() {
        var operations = PeriodPlanner.plan(List.of(period(1, "2026-01-01", null, "100.00")),
                LocalDate.of(2027, 1, 1), new BigDecimal("120.00"), TODAY);

        assertThat(operations).containsExactly(
                new Operation.Close<>(1, LocalDate.of(2026, 12, 31)),
                new Operation.Open<>(LocalDate.of(2027, 1, 1), null, new BigDecimal("120.00")));
    }

    @Test
    void aNewPriceLastsUntilTheNextScheduledOne() {
        var operations = PeriodPlanner.plan(List.of(
                        period(1, "2026-01-01", "2027-01-31", "100.00"),
                        period(2, "2027-02-01", null, "130.00")),
                LocalDate.of(2027, 1, 1), new BigDecimal("120.00"), TODAY);

        assertThat(operations).containsExactly(
                new Operation.Close<>(1, LocalDate.of(2026, 12, 31)),
                new Operation.Open<>(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31), new BigDecimal("120.00")));
    }

    @Test
    void aScheduledPriceIsCorrectedOrCancelledNotDuplicated() {
        List<Period<BigDecimal>> periods = List.of(period(1, "2026-01-01", "2027-01-31", "100.00"),
                period(2, "2027-02-01", null, "130.00"));

        assertThat(PeriodPlanner.plan(periods, LocalDate.of(2027, 2, 1), new BigDecimal("125.00"), TODAY))
                .containsExactly(new Operation.Correct<>(2, new BigDecimal("125.00")));
        assertThat(PeriodPlanner.plan(periods, LocalDate.of(2027, 2, 1), null, TODAY))
                .containsExactly(new Operation.Cancel<>(2));
    }

    @Test
    void endingClosesTheCurrentPeriodAndTheSameValueChangesNothing() {
        List<Period<BigDecimal>> periods = List.of(period(1, "2026-01-01", null, "100.00"));

        assertThat(PeriodPlanner.plan(periods, TODAY, null, TODAY))
                .containsExactly(new Operation.Close<>(1, TODAY.minusDays(1)));
        assertThat(PeriodPlanner.plan(periods, TODAY, new BigDecimal("100.00"), TODAY)).isEmpty();
        assertThat(PeriodPlanner.plan(List.<Period<BigDecimal>>of(), TODAY, null, TODAY)).isEmpty();
    }

    @Test
    void thePastNeverChanges() {
        assertThatThrownBy(() -> PeriodPlanner.plan(List.of(), TODAY.minusDays(1), BigDecimal.TEN, TODAY))
                .isInstanceOf(BillingException.Invalid.class);
    }
}
