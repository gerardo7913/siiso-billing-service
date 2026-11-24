package com.siiso.billing.infrastructure.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Accepts only portal-scoped access tokens. Refresh tokens are signed with the same key, so
 * without this check a 7-day refresh token would work as a bearer token.
 */
final class AccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!TokenClaims.TOKEN_TYPE_ACCESS.equals(token.getClaimAsString(TokenClaims.TOKEN_TYPE))) {
            return failure("Not an access token");
        }
        if (token.getClaimAsString(TokenClaims.PORTAL) == null) {
            return failure("Access token without portal");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
