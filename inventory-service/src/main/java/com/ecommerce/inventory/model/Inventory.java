package com.ecommerce.inventory.model;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "inventory")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Inventory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true, nullable = false) private Long productId;
    @Column(nullable = false) private Integer quantity;
    @Column(nullable = false) private Integer reserved;
    @Version private Long version; // optimistic lock
}
