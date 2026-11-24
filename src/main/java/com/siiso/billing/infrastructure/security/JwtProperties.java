package com.siiso.billing.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How to verify the access tokens issued by security-service.
 *
 * @param issuer            expected {@code iss} claim
 * @param publicKeyLocation PEM (X.509) public key matching security-service's signing key
 */
@Validated
@ConfigurationProperties("siiso.security.jwt")
public record JwtProperties(@NotBlank String issuer, String publicKeyLocation) {
}
