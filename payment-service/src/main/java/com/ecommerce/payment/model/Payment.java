package com.ecommerce.payment.model;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name = "payments")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payments_seq")
    @SequenceGenerator(name = "payments_seq", sequenceName = "payments_seq", allocationSize = 50)
    private Long id;
    @Column(nullable = false) private Long orderId;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false) private BigDecimal amount;
    @Enumerated(EnumType.STRING) @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;
    private String transactionId;
    @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();

    public enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }
}
