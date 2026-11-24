package com.siiso.billing.domain;

import java.util.Map;

/** Failures of billing operations, each mapped to one HTTP problem type. */
public abstract sealed class BillingException extends RuntimeException {

    BillingException(String message) {
        super(message);
    }

    public static final class NotFound extends BillingException {
        public NotFound(String message) {
            super(message);
        }
    }

    public static final class Invalid extends BillingException {
        private final Map<String, String> errors;

        public Invalid(Map<String, String> errors) {
            super("Revisa los datos marcados.");
            this.errors = Map.copyOf(errors);
        }

        public Map<String, String> errors() {
            return errors;
        }
    }

    /** A code already used, or a change that the current state does not allow. */
    public static final class Conflict extends BillingException {
        private final String field;

        public Conflict(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /** Someone else changed the record since it was read. */
    public static final class Stale extends BillingException {
        public Stale() {
            super("Otra persona modificó este plan. Vuelve a cargarlo y repite el cambio.");
        }
    }
}
