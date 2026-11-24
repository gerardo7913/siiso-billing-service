package com.siiso.billing.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenRulesTest {

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void acceptsPortalScopedAccessTokens() {
        Jwt jwt = token().claim("token_type", "access").claim("portal", "CLIENT").build();
        assertThat(new AccessTokenValidator().validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void rejectsRefreshTokens() {
        Jwt jwt = token().claim("token_type", "refresh").claim("portal", "CLIENT").build();
        assertThat(new AccessTokenValidator().validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void rejectsAccessTokensWithoutPortal() {
        Jwt jwt = token().claim("token_type", "access").build();
        assertThat(new AccessTokenValidator().validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void mapsPortalAndRolesToAuthorities() {
        Jwt jwt = token().claim("portal", "CLIENT").claim("roles", List.of("ROLE_CLIENT_VIEWER")).build();

        assertThat(new TokenAuthoritiesConverter().convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("PORTAL_CLIENT", "ROLE_CLIENT_VIEWER");
    }
}
