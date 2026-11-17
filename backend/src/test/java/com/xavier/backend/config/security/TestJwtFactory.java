package com.xavier.backend.config.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Access tokens as Keycloak issues them (D176, D189), signed by a test key that the test {@code TrustedRealms} trusts
 * ({@link TestJwtAutoConfiguration}): an RS256 key and an ES256 key per JVM. A token is valid by default (tenant realm,
 * audience {@code restaurantpro-api}, {@code azp backoffice-bff}, {@code typ Bearer}, {@code acr 2}, just
 * authenticated); each method changes one thing, so a test names the one rule it breaks.
 */
public final class TestJwtFactory {

    public static final String TENANT_ISSUER = "https://auth.restaurantpro.test/realms/restaurantpro";
    public static final String OPS_ISSUER = "https://auth.restaurantpro.test/realms/restaurantpro-ops";
    public static final String AUDIENCE = "restaurantpro-api";
    public static final String BFF = "backoffice-bff";

    private static final RSAKey RSA = generateRsa();
    private static final ECKey EC = generateEc();
    private static final JWKSource<SecurityContext> KEYS = new ImmutableJWKSet<>(
            new JWKSet(List.of(RSA.toPublicJWK(), EC.toPublicJWK())));

    private final Map<String, Object> claims = new LinkedHashMap<>();
    private JWSAlgorithm algorithm = JWSAlgorithm.RS256;
    private boolean unsigned;
    private boolean hmacWithPublicKey;

    private TestJwtFactory(String issuer, String subject, Instant now) {
        claims.put("iss", issuer);
        claims.put("sub", subject);
        claims.put("aud", List.of(AUDIENCE, "account"));
        claims.put("azp", BFF);
        claims.put("typ", "Bearer");
        claims.put("acr", "2");
        claims.put("auth_time", now.getEpochSecond());
        claims.put("iat", Date.from(now));
        claims.put("nbf", Date.from(now));
        claims.put("exp", Date.from(now.plusSeconds(300)));
        claims.put("jti", UUID.randomUUID().toString());
    }

    /** The public keys the test decoder trusts (both realms). */
    public static JWKSource<SecurityContext> keys() {
        return KEYS;
    }

    /** A BackOffice user of the tenant realm, authenticated now with a second factor. */
    public static TestJwtFactory account(String subject) {
        return account(subject, Instant.now());
    }

    /** As {@link #account(String)}, with {@code now} as the authentication and issue time. */
    public static TestJwtFactory account(String subject, Instant now) {
        return new TestJwtFactory(TENANT_ISSUER, subject, now);
    }

    /** A support person of the ops realm (D183), authenticated now with a second factor. */
    public static TestJwtFactory support(String subject, Instant now) {
        return new TestJwtFactory(OPS_ISSUER, subject, now);
    }

    public TestJwtFactory claim(String name, Object value) {
        claims.put(name, value instanceof Instant instant ? Date.from(instant) : value);
        return this;
    }

    public TestJwtFactory without(String name) {
        claims.remove(name);
        return this;
    }

    /** {@code acr} and {@code auth_time} of the user's authentication (RFC 9470). */
    public TestJwtFactory authenticated(String acr, Instant authTime) {
        claims.put("acr", acr);
        claims.put("auth_time", authTime.getEpochSecond());
        return this;
    }

    public TestJwtFactory es256() {
        algorithm = JWSAlgorithm.ES256;
        return this;
    }

    /** {@code alg: none}. */
    public TestJwtFactory unsigned() {
        unsigned = true;
        return this;
    }

    /** HS256 with the RSA public key as the secret (the classic algorithm-confusion attack). */
    public TestJwtFactory hmacWithPublicKey() {
        hmacWithPublicKey = true;
        return this;
    }

    public String encode() {
        JWTClaimsSet set = claimsSet();
        if (unsigned) {
            return new PlainJWT(set).serialize();
        }
        try {
            if (hmacWithPublicKey) {
                byte[] secret = RSA.toPublicJWK().toJSONString().getBytes(StandardCharsets.UTF_8);
                return sign(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(RSA.getKeyID()).build(),
                        new MACSigner(secret), set);
            }
            if (algorithm == JWSAlgorithm.ES256) {
                return sign(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(EC.getKeyID()).build(),
                        new ECDSASigner(EC), set);
            }
            return sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA.getKeyID()).build(),
                    new RSASSASigner(RSA), set);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code Authorization: Bearer <token>} on a MockMvc request. */
    public RequestPostProcessor bearer() {
        String token = encode();
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    private JWTClaimsSet claimsSet() {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static String sign(JWSHeader header, JWSSigner signer, JWTClaimsSet set) throws JOSEException {
        SignedJWT jwt = new SignedJWT(header, set);
        jwt.sign(signer);
        return jwt.serialize();
    }

    /** A token signed with a key nobody trusts (same kid as the trusted one). */
    public String encodeWithForeignKey() {
        try {
            RSAKey foreign = new RSAKeyGenerator(2048).keyID(RSA.getKeyID()).generate();
            return sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(foreign.getKeyID()).build(),
                    new RSASSASigner(foreign), claimsSet());
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateRsa() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-rs256").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ECKey generateEc() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("test-es256").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

}
