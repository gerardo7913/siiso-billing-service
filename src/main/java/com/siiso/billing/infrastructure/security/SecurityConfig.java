package com.siiso.billing.infrastructure.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * Stateless resource server. Tokens are issued by security-service (RS256); this service only
 * verifies them. Everything not explicitly allowed is denied.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class})
public class SecurityConfig {

    /** Rates are administered by staff (portal Colaborador) only. */
    public static final String STAFF_PORTAL = TokenClaims.PORTAL_AUTHORITY_PREFIX + "STAFF";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(new TokenAuthoritiesConverter());

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Error dispatches carry the status of the original request; never hide it behind a 403.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/billing/**").hasAuthority(STAFF_PORTAL)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(readPublicKey(properties)).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new AccessTokenValidator()));
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private static RSAPublicKey readPublicKey(JwtProperties properties) {
        String location = properties.publicKeyLocation();
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("The public key of security-service is not configured: set "
                    + "siiso.security.jwt.public-key-location (JWT_PUBLIC_KEY_LOCATION)");
        }
        try (InputStream pem = new org.springframework.core.io.DefaultResourceLoader().getResource(location.trim())
                .getInputStream()) {
            return checkPublicKey(RsaKeyConverters.x509().convert(pem));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read JWT public key " + location, e);
        }
    }

    /** SHA-256 of public keys that must never be trusted (siiso-docs ADR 0014). */
    private static final java.util.Set<String> REVOKED_KEYS = java.util.Set.of(
            // Development key committed to the repositories until 2026-11-20.
            "3f9387bb0a14f32ef45dca7741c208a4fa00af416f0961660cb92c033fbf941d");

    /** RSA of at least 3072 bits (NIST SP 800-57) and not revoked. */
    static RSAPublicKey checkPublicKey(RSAPublicKey key) {
        if (key.getModulus().bitLength() < 3072) {
            throw new IllegalStateException("The public key of security-service has "
                    + key.getModulus().bitLength() + " bits; at least 3072 are required");
        }
        try {
            String fingerprint = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
            if (REVOKED_KEYS.contains(fingerprint)) {
                throw new IllegalStateException("The public key of security-service is revoked; use this installation's key");
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return key;
    }
}
