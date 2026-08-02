package com.ecommerce.user.service;

import com.ecommerce.user.config.JwtConfig;
import com.ecommerce.user.dto.AuthResponse;
import com.ecommerce.user.dto.LoginRequest;
import com.ecommerce.user.dto.RegisterRequest;
import com.ecommerce.user.model.User;
import com.ecommerce.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private JwtConfig jwtConfig;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, jwtConfig, passwordEncoder, redisTemplate);
    }

    @Test
    void register_throwsWhenEmailAlreadyRegistered() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("taken@example.com");
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("already registered");

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_savesUserAndReturnsToken() {
        RegisterRequest req = new RegisterRequest();
        req.setName("Jane");
        req.setEmail("jane@example.com");
        req.setPassword("password123");

        when(userRepository.existsByEmail("jane@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(jwtConfig.generateToken(any(), eq("jane@example.com"), anyString())).thenReturn("token123");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthResponse response = userService.register(req);

        assertThat(response.getToken()).isEqualTo("token123");
        assertThat(response.getEmail()).isEqualTo("jane@example.com");
        verify(userRepository).save(any(User.class));
        verify(valueOperations).set(eq("session:jane@example.com"), eq("token123"), eq(24L), any());
    }

    @Test
    void login_throwsWhenUserNotFound() {
        LoginRequest req = new LoginRequest();
        req.setEmail("nobody@example.com");
        req.setPassword("password123");
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid credentials");
    }

    @Test
    void login_throwsWhenPasswordDoesNotMatch() {
        LoginRequest req = new LoginRequest();
        req.setEmail("jane@example.com");
        req.setPassword("wrongpass");
        User user = User.builder().email("jane@example.com").password("hashed").build();
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpass", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> userService.login(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid credentials");
    }

    @Test
    void login_succeedsWithValidCredentials() {
        LoginRequest req = new LoginRequest();
        req.setEmail("jane@example.com");
        req.setPassword("password123");
        User user = User.builder().email("jane@example.com").name("Jane").password("hashed").build();

        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);
        when(jwtConfig.generateToken(any(), eq("jane@example.com"), anyString())).thenReturn("token456");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        AuthResponse response = userService.login(req);

        assertThat(response.getToken()).isEqualTo("token456");
    }

    @Test
    void getUser_throwsWhenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUser(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void getUser_returnsUserWhenFound() {
        User user = User.builder().id(1L).email("jane@example.com").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThat(userService.getUser(1L)).isEqualTo(user);
    }
}
