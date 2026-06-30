package com.ecommerce.order.service;
import com.ecommerce.order.dto.CreateOrderRequest;
import com.ecommerce.order.kafka.OrderEventPublisher;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor @Slf4j
public class OrderService {
    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;

    @Transactional
    public Order createOrder(CreateOrderRequest req) {
        BigDecimal total = req.getItems().stream()
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = Order.builder()
                .userId(req.getUserId())
                .totalAmount(total)
                .shippingAddress(req.getShippingAddress())
                .build();

        List<OrderItem> items = req.getItems().stream()
                .map(i -> OrderItem.builder()
                        .order(order).productId(i.getProductId())
                        .productName(i.getProductName())
                        .quantity(i.getQuantity()).price(i.getPrice())
                        .build())
                .collect(Collectors.toList());
        order.setItems(items);

        Order saved = orderRepository.save(order);

        // Publish to Kafka → payment + inventory + notification will consume
        eventPublisher.publishOrderCreated(saved);

        log.info("Order created: id={}, userId={}, amount={}", saved.getId(), saved.getUserId(), total);
        return saved;
    }

    public Order getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found: " + id));
    }

    public List<Order> getUserOrders(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public Order cancelOrder(Long id) {
        Order order = getOrder(id);
        if (order.getStatus() == Order.OrderStatus.SHIPPED ||
            order.getStatus() == Order.OrderStatus.DELIVERED) {
            throw new RuntimeException("Cannot cancel order in status: " + order.getStatus());
        }
        order.setStatus(Order.OrderStatus.CANCELLED);
        Order saved = orderRepository.save(order);
        eventPublisher.publishOrderCancelled(saved, "User requested cancellation");
        return saved;
    }

    @Transactional
    public void cancelDueToOutOfStock(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != Order.OrderStatus.PENDING) {
            return; // already handled or in a terminal state — avoid double-cancelling
        }
        order.setStatus(Order.OrderStatus.CANCELLED);
        Order saved = orderRepository.save(order);
        eventPublisher.publishOrderCancelled(saved, reason);
        log.warn("Order {} cancelled due to insufficient stock: {}", orderId, reason);
    }
}
