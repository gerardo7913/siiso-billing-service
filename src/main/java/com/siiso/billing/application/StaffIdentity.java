package com.siiso.billing.application;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An authenticated staff user (portal Colaborador), taken <b>only</b> from the verified token.
 *
 * @param permissions {@code MODULE_CODE:ACTION} granted by the user's roles in security-service
 */
public record StaffIdentity(UUID principalId, Set<String> permissions) {

    public StaffIdentity {
        Objects.requireNonNull(principalId, "principalId");
        permissions = Set.copyOf(permissions);
    }

    public boolean can(String module, String action) {
        return permissions.contains(module + ":" + action);
    }
}
