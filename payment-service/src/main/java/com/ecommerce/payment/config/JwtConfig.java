package com.ecommerce.payment.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Verifies tokens minted by user-service, using the same shared secret.
 *
 * This service only verifies; it never mints. Verification happens here rather
 * than being delegated to the Kong gateway because Kong is not the only way to
 * reach this pod - anyone with cluster access can port-forward straight past
 * the gateway. A live test proved the point: before this class existed,
 * POST /api/orders with no Authorization header at all returned 200.
 */
@Component
public class JwtConfig {

    @Value("${jwt.secret}")
    private String secret;

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isValid(String token) {
        try {
            Jwts.parser().verifyWith(getKey()).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String extractEmail(String token) {
        return Jwts.parser().verifyWith(getKey()).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }

    public String extractRole(String token) {
        return Jwts.parser().verifyWith(getKey()).build()
                .parseSignedClaims(token).getPayload().get("role", String.class);
    }

    /**
     * The caller's real user id, as asserted by the signed token.
     *
     * Before this claim existed the token carried only an email and a role, so
     * order-service had no way to know who the caller was and read userId out
     * of the request body instead. That let any caller place an order in any
     * other user's name. Identity has to come from something the caller cannot
     * edit.
     */
    public Long extractUserId(String token) {
        Number id = Jwts.parser().verifyWith(getKey()).build()
                .parseSignedClaims(token).getPayload().get("userId", Number.class);
        return id == null ? null : id.longValue();
    }
}
