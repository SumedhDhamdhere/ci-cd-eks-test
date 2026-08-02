package com.ecommerce.order.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * These tests guard the control that was missing entirely: before this package
 * existed, every endpoint on this service answered anonymous callers.
 */
class JwtConfigTest {

    // 64 bytes — HS512 refuses anything shorter.
    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hs512-signing-0123456789";
    private static final String OTHER_SECRET = "a-completely-different-key-also-long-enough-for-hs512-987654321!";

    private JwtConfig jwtConfig;

    @BeforeEach
    void setUp() {
        jwtConfig = new JwtConfig();
        ReflectionTestUtils.setField(jwtConfig, "secret", SECRET);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private String token(String secret, Long userId, String role, long ttlMillis) {
        var builder = Jwts.builder()
                .subject("someone@example.com")
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttlMillis))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)));
        if (userId != null) {
            builder.claim("userId", userId);
        }
        return builder.compact();
    }

    @Test
    void acceptsATokenSignedWithTheSharedSecret() {
        assertThat(jwtConfig.isValid(token(SECRET, 7L, "USER", 60_000))).isTrue();
    }

    @Test
    void rejectsATokenSignedWithADifferentKey() {
        // Anyone can mint a well-formed JWT. Only the signature makes it trustworthy.
        assertThat(jwtConfig.isValid(token(OTHER_SECRET, 7L, "USER", 60_000))).isFalse();
    }

    @Test
    void rejectsGarbage() {
        assertThat(jwtConfig.isValid("garbage.token.here")).isFalse();
        assertThat(jwtConfig.isValid("")).isFalse();
    }

    @Test
    void rejectsAnExpiredToken() {
        assertThat(jwtConfig.isValid(token(SECRET, 7L, "USER", -1000))).isFalse();
    }

    @Test
    void extractsTheIdentityClaims() {
        String t = token(SECRET, 7L, "ADMIN", 60_000);
        assertThat(jwtConfig.extractUserId(t)).isEqualTo(7L);
        assertThat(jwtConfig.extractRole(t)).isEqualTo("ADMIN");
        assertThat(jwtConfig.extractEmail(t)).isEqualTo("someone@example.com");
    }

    @Test
    void extractUserIdIsNullWhenTheClaimIsAbsent() {
        // Tokens minted before the userId claim was added must not be treated
        // as if they identified user 0.
        assertThat(jwtConfig.extractUserId(token(SECRET, null, "USER", 60_000))).isNull();
    }

    // ---------- filter behaviour ----------

    @Test
    void filterAuthenticatesAValidToken() throws Exception {
        var filter = new JwtAuthenticationFilter(jwtConfig);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token(SECRET, 7L, "USER", 60_000));

        filter.doFilter(request, response, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        // The principal is the id, so controllers never need the request body
        // to find out who is calling.
        assertThat(auth.getPrincipal()).isEqualTo(7L);
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        verify(chain).doFilter(request, response);
    }

    @Test
    void filterLeavesTheContextEmptyForAForgedToken() throws Exception {
        var filter = new JwtAuthenticationFilter(jwtConfig);
        var request = mock(HttpServletRequest.class);
        var chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token(OTHER_SECRET, 7L, "ADMIN", 60_000));

        filter.doFilter(request, mock(HttpServletResponse.class), chain);

        // Unauthenticated — the filter chain then refuses the request.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void filterLeavesTheContextEmptyWithNoHeader() throws Exception {
        var filter = new JwtAuthenticationFilter(jwtConfig);
        var request = mock(HttpServletRequest.class);
        var chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(request, mock(HttpServletResponse.class), chain);

        // This is the case that used to return 200 from every endpoint.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void filterIgnoresATokenWithNoUserIdClaim() throws Exception {
        var filter = new JwtAuthenticationFilter(jwtConfig);
        var request = mock(HttpServletRequest.class);
        var chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token(SECRET, null, "USER", 60_000));

        filter.doFilter(request, mock(HttpServletResponse.class), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
