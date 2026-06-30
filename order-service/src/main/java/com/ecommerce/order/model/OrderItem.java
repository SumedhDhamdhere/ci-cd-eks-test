package com.ecommerce.order.model;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne @JoinColumn(name = "order_id")
    @JsonIgnore
    private Order order;
    @Column(nullable = false) private Long productId;
    @Column(nullable = false) private String productName;
    @Column(nullable = false) private Integer quantity;
    @Column(nullable = false) private BigDecimal price;
}
