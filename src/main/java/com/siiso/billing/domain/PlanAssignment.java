package com.siiso.billing.domain;

import java.time.LocalDate;

/** The shared plan of a company for a period. */
public record PlanAssignment(long id, String planCode, String planName, RecordStatus planStatus,
                             LocalDate validFrom, LocalDate validUntil) {
}
