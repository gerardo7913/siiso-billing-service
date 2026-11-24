package com.siiso.billing.infrastructure.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Turns token claims into authorities: the session portal ({@code PORTAL_CLIENT}) plus the roles
 * of that portal ({@code ROLE_CLIENT_VIEWER}). security-service only puts the portal's own roles
 * in the token, so roles of other portals never leak into a CLIENT session.
 */
final class TokenAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt token) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(
                TokenClaims.PORTAL_AUTHORITY_PREFIX + token.getClaimAsString(TokenClaims.PORTAL)));
        List<String> roles = token.getClaimAsStringList(TokenClaims.ROLES);
        if (roles != null) {
            roles.stream().map(SimpleGrantedAuthority::new).forEach(authorities::add);
        }
        return authorities;
    }
}
