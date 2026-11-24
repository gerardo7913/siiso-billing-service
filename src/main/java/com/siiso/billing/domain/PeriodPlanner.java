package com.siiso.billing.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plans the change of a dated value (the price of a service in a plan, the shared plan of a company)
 * without overlapping periods and without rewriting the past (siiso-docs ADR 0022).
 *
 * <p>Setting a value from a date closes the period that covers it the day before and opens a new one
 * that lasts until the next scheduled period starts. Ending a value from a date closes the covering
 * period. A period that has not started yet is corrected or cancelled instead. Pure: unit-tested.
 */
public final class PeriodPlanner {

    private PeriodPlanner() {
    }

    /** An existing period of one key (one service in one plan, or one company). */
    public record Period<V>(long id, LocalDate validFrom, LocalDate validUntil, V value) {

        boolean covers(LocalDate date) {
            return !validFrom.isAfter(date) && (validUntil == null || !validUntil.isBefore(date));
        }
    }

    /** What to do in the database. */
    public sealed interface Operation<V> {

        /** Ends an existing period on {@code validUntil}. */
        record Close<V>(long id, LocalDate validUntil) implements Operation<V> {
        }

        /** Changes the value of a period that starts on the date of the change. */
        record Correct<V>(long id, V value) implements Operation<V> {
        }

        /** Removes a period that has not started (a scheduled value that is cancelled). */
        record Cancel<V>(long id) implements Operation<V> {
        }

        record Open<V>(LocalDate validFrom, LocalDate validUntil, V value) implements Operation<V> {
        }
    }

    /**
     * @param periods existing periods of the key, in any order; they never overlap
     * @param from    first day of the change; never before {@code today}
     * @param value   new value, or {@code null} to end it from that day
     * @return the operations; empty when nothing changes
     */
    public static <V> List<Operation<V>> plan(List<Period<V>> periods, LocalDate from, V value, LocalDate today) {
        if (from.isBefore(today)) {
            throw new BillingException.Invalid(Map.of("valid_from",
                    "No se pueden cambiar días pasados: la fecha debe ser hoy o posterior."));
        }
        List<Period<V>> sorted = periods.stream().sorted(Comparator.comparing(Period::validFrom)).toList();
        Period<V> covering = sorted.stream().filter(period -> period.covers(from)).findFirst().orElse(null);
        Period<V> next = sorted.stream().filter(period -> period.validFrom().isAfter(from)).findFirst().orElse(null);

        List<Operation<V>> operations = new ArrayList<>();
        if (value == null) {
            if (covering == null) {
                return operations;
            }
            if (covering.validFrom().equals(from)) {
                operations.add(new Operation.Cancel<>(covering.id()));
            } else {
                operations.add(new Operation.Close<>(covering.id(), from.minusDays(1)));
            }
            return operations;
        }
        if (covering != null && covering.validFrom().equals(from)) {
            if (!Objects.equals(covering.value(), value)) {
                operations.add(new Operation.Correct<>(covering.id(), value));
            }
            return operations;
        }
        if (covering != null) {
            if (Objects.equals(covering.value(), value) && covering.validUntil() == null) {
                return operations;
            }
            operations.add(new Operation.Close<>(covering.id(), from.minusDays(1)));
        }
        operations.add(new Operation.Open<>(from, next == null ? null : next.validFrom().minusDays(1), value));
        return operations;
    }
}
