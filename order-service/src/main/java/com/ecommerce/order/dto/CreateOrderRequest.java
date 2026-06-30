package com.ecommerce.order.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

@Data
public class CreateOrderRequest {
    @NotNull private Long userId;
    @NotEmpty private List<OrderItemRequest> items;
    @NotBlank private String shippingAddress;

    @Data
    public static class OrderItemRequest {
        @NotNull private Long productId;
        @NotBlank private String productName;
        @Min(1) private Integer quantity;
        @NotNull private java.math.BigDecimal price;
    }
}
