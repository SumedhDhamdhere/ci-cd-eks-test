package com.ecommerce.user.config;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtConfig {
    @Value("${jwt.secret}")
    private String secret;
    @Value("${jwt.expiration:86400000}")
    private long expiration;

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }
    /**
     * The userId claim is what makes the token usable as proof of identity by
     * the other services.
     *
     * Without it the token carried only an email and a role, so order-service
     * had no way to know who was calling and took userId from the request body
     * instead. A live test placed an order as userId=1 using a completely
     * different user's token and got 200 back. Identity has to travel inside
     * the signature.
     */
    public String generateToken(Long userId, String email, String role) {
        return Jwts.builder()
                .subject(email)
                .claim("userId", userId)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getKey())
                .compact();
    }
    public String extractEmail(String token) {
        return Jwts.parser().verifyWith(getKey()).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }
    public String extractRole(String token) {
        return Jwts.parser().verifyWith(getKey()).build()
                .parseSignedClaims(token).getPayload().get("role", String.class);
    }
    public Long extractUserId(String token) {
        Number id = Jwts.parser().verifyWith(getKey()).build()
                .parseSignedClaims(token).getPayload().get("userId", Number.class);
        return id == null ? null : id.longValue();
    }
    public boolean isValid(String token) {
        try {
            Jwts.parser().verifyWith(getKey()).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) { return false; }
    }
}
