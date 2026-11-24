package com.siiso.billing.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues tokens shaped exactly like security-service's, signed with a key pair generated for the
 * test run. Its public key is written to a PEM file the service under test is pointed at.
 */
public final class TestTokens {

    /** Demo company = legacy company 0080, with the id the legacy import derives (V005). */
    public static final UUID DEMO_ORGANIZATION = UUID.fromString("586fdba3-62b2-3ff3-8170-d44862af2241");

    private final KeyPair keyPair;
    private final Path publicKeyPem;

    public TestTokens() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            this.keyPair = generator.generateKeyPair();
            this.publicKeyPem = Files.createTempFile("test-jwt-public", ".pem");
            String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPublic().getEncoded());
            Files.writeString(publicKeyPem, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
            publicKeyPem.toFile().deleteOnExit();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String publicKeyLocation() {
        return "file:" + publicKeyPem;
    }

    public String clientToken(UUID organizationId) {
        return sign(builder("CLIENT", List.of("ROLE_CLIENT_VIEWER"))
                .claim("organizationId", organizationId.toString())
                .build());
    }

    public String staffToken() {
        return staffToken(List.of());
    }

    /** A staff (Colaborador) token carrying these {@code MODULE:ACTION} permissions. */
    public String staffToken(List<String> permissions) {
        return sign(builder("STAFF", List.of("ROLE_ADMIN")).claim("permissions", permissions).build());
    }

    /** A refresh token: valid signature, but must never be accepted as a bearer token. */
    public String refreshToken() {
        return sign(builder("CLIENT", List.of()).claim("token_type", "refresh").build());
    }

    public String expiredClientToken(UUID organizationId) {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        return sign(builder("CLIENT", List.of("ROLE_CLIENT_VIEWER"))
                .claim("organizationId", organizationId.toString())
                .issueTime(Date.from(past.minus(Duration.ofMinutes(15))))
                .expirationTime(Date.from(past))
                .build());
    }

    private static JWTClaimsSet.Builder builder(String portal, List<String> roles) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("siiso")
                .jwtID(UUID.randomUUID().toString())
                .subject("52123456")
                .claim("principalId", UUID.randomUUID().toString())
                .claim("sessionId", UUID.randomUUID().toString())
                .claim("roles", roles)
                .claim("token_type", "access")
                .claim("portal", portal)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))));
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(keyPair.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
