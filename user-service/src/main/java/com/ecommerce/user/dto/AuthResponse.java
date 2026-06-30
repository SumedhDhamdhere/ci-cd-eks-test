package com.ecommerce.user.dto;
import lombok.*;
@Data @AllArgsConstructor @Builder
public class AuthResponse {
    private String token;
    private String email;
    private String name;
    private String role;
}
