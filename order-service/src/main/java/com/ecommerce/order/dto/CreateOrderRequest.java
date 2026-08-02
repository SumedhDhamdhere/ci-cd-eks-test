package com.ecommerce.order.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

@Data
public class CreateOrderRequest {
    // There is deliberately no userId field. It used to be required, and because
    // the service trusted it, any caller could book an order in another user's
    // name. The owner now comes from the verified JWT.
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
