package com.ecommerce.order.controller;
import com.ecommerce.order.config.AuthenticatedUser;
import com.ecommerce.order.dto.CreateOrderRequest;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Every method passes AuthenticatedUser.id() down to the service, which enforces
 * ownership. The caller's identity is never read from the URL or the body.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<Order> createOrder(@Valid @RequestBody CreateOrderRequest req) {
        // The order is booked against the caller named in the token.
        // CreateOrderRequest no longer carries a userId for the client to pick.
        return ResponseEntity.ok(orderService.createOrder(req, AuthenticatedUser.id()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrder(id, AuthenticatedUser.id()));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Order>> getUserOrders(@PathVariable Long userId) {
        // Reading another user's order history worked with any valid token, and
        // with no token at all. Both are refused now.
        return ResponseEntity.ok(orderService.getUserOrders(userId, AuthenticatedUser.id()));
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<Order> cancelOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.cancelOrder(id, AuthenticatedUser.id()));
    }
}
