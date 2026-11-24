package com.siiso.billing.infrastructure.security;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/** Front-end origins allowed to call the API (no wildcards: explicit list). */
@Validated
@ConfigurationProperties("siiso.cors")
public record CorsProperties(@NotEmpty List<String> allowedOrigins) {
}
