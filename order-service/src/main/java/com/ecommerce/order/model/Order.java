package com.ecommerce.order.model;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "orders")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;
    @Column(nullable = false) private BigDecimal totalAmount;
    private String shippingAddress;
    @Builder.Default private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<OrderItem> items;

    public enum OrderStatus {
        PENDING, CONFIRMED, PAYMENT_PROCESSING,
        PAID, SHIPPED, DELIVERED, CANCELLED
    }
}
