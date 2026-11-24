package com.siiso.billing.infrastructure.security;

/** Claim names and values of security-service access tokens (see its {@code SecurityConstants}). */
public final class TokenClaims {

    public static final String PRINCIPAL_ID = "principalId";
    public static final String ORGANIZATION_ID = "organizationId";
    public static final String PORTAL = "portal";
    public static final String ROLES = "roles";
    /** {@code MODULE_CODE:ACTION} strings granted by the user's roles in the session portal. */
    public static final String PERMISSIONS = "permissions";
    public static final String TOKEN_TYPE = "token_type";

    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String PORTAL_CLIENT = "CLIENT";

    /** Authority granted for the session portal, e.g. {@code PORTAL_CLIENT}. */
    public static final String PORTAL_AUTHORITY_PREFIX = "PORTAL_";

    private TokenClaims() {
    }
}
