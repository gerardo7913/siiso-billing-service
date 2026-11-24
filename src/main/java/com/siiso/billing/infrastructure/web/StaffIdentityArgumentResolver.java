package com.siiso.billing.infrastructure.web;

import com.siiso.billing.application.StaffIdentity;
import com.siiso.billing.infrastructure.security.TokenClaims;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Lets controllers take a {@link StaffIdentity} parameter, built only from the verified token. */
final class StaffIdentityArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return StaffIdentity.class.equals(parameter.getParameterType());
    }

    @Override
    public StaffIdentity resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                         NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new AccessDeniedException("A staff access token is required");
        }
        var token = jwtAuthentication.getToken();
        UUID principalId;
        try {
            principalId = UUID.fromString(token.getClaimAsString(TokenClaims.PRINCIPAL_ID));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AccessDeniedException("The token has no principal");
        }
        List<String> permissions = token.getClaimAsStringList(TokenClaims.PERMISSIONS);
        return new StaffIdentity(principalId, permissions == null ? Set.of() : Set.copyOf(permissions));
    }
}
