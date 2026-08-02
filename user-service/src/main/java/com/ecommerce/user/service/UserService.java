package com.ecommerce.user.service;
import com.ecommerce.user.config.JwtConfig;
import com.ecommerce.user.dto.*;
import com.ecommerce.user.model.User;
import com.ecommerce.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

@Service @RequiredArgsConstructor @Slf4j
public class UserService {
    private final UserRepository userRepository;
    private final JwtConfig jwtConfig;
    private final PasswordEncoder passwordEncoder;
    private final RedisTemplate<String, String> redisTemplate;
    private static final String SESSION_PREFIX = "session:";

    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail()))
            throw new RuntimeException("Email already registered");
        User user = User.builder()
                .name(req.getName()).email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .phone(req.getPhone()).build();
        userRepository.save(user);
        String token = jwtConfig.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        redisTemplate.opsForValue().set(SESSION_PREFIX + user.getEmail(), token, 24, TimeUnit.HOURS);
        log.info("User registered: {}", user.getEmail());
        return AuthResponse.builder().token(token).email(user.getEmail())
                .name(user.getName()).role(user.getRole().name()).build();
    }

    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword()))
            throw new RuntimeException("Invalid credentials");
        String token = jwtConfig.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        redisTemplate.opsForValue().set(SESSION_PREFIX + user.getEmail(), token, 24, TimeUnit.HOURS);
        log.info("User logged in: {}", user.getEmail());
        return AuthResponse.builder().token(token).email(user.getEmail())
                .name(user.getName()).role(user.getRole().name()).build();
    }

    public void logout(String email) {
        redisTemplate.delete(SESSION_PREFIX + email);
        log.info("User logged out: {}", email);
    }

    public User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
