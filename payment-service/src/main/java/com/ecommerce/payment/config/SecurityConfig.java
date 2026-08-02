package com.ecommerce.payment.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Every payment endpoint requires a valid token.
 *
 * Until this existed the service had no SecurityConfig and no filter at all,
 * so every endpoint answered anonymous callers.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Scraped by the kubelet and Prometheus, neither of which
                // carries a token.
                .requestMatchers("/actuator/**").permitAll()
                // Spring forwards to /error after a handler throws. If that
                // forward is not permitted, a plain validation failure comes
                // back as 403 and the caller cannot tell bad input from
                // "not allowed" - which is exactly what user-service did.
                .requestMatchers("/error").permitAll()

                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
