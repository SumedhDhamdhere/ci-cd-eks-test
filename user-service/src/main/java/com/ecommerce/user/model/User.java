package com.ecommerce.user.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class User {
    @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "users_seq")
    @SequenceGenerator(name = "users_seq", sequenceName = "users_seq", allocationSize = 50)
    private Long id;
    @Column(unique = true, nullable = false) private String email;
    @Column(nullable = false) private String password;
    @Column(nullable = false) private String name;
    private String phone;
    @Enumerated(EnumType.STRING) @Builder.Default private Role role = Role.USER;
    @Builder.Default private boolean active = true;
    @Column(updatable = false) @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
    public enum Role { USER, ADMIN }
}
