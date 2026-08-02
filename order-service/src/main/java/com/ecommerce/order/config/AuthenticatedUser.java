package com.ecommerce.order.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The caller's identity, taken from the verified JWT and nothing else.
 *
 * JwtAuthenticationFilter stores the userId claim as the authentication
 * principal, so this is the signed assertion of who is calling. Controllers use
 * it instead of reading userId out of the request body, which is what allowed
 * this against a live pod:
 *
 *   POST /api/orders   Authorization: Bearer &lt;chaos1's token&gt;
 *   {"userId": 1, "shippingAddress": "attacker", ...}   ->  200 OK
 *
 * The order was created against user 1 by a completely different user.
 */
public final class AuthenticatedUser {

    private AuthenticatedUser() {}

    /** Never null on a secured endpoint - the filter chain rejects anonymous callers first. */
    public static Long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long userId)) {
            throw new IllegalStateException("No authenticated user in the security context");
        }
        return userId;
    }

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
