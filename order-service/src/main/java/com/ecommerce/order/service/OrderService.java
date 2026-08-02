package com.ecommerce.order.service;
import com.ecommerce.order.dto.CreateOrderRequest;
import com.ecommerce.order.event.OrderDomainEvents;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.config.AuthenticatedUser;
import com.ecommerce.order.exception.AccessDeniedException;
import com.ecommerce.order.exception.OrderNotFoundException;
import com.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor @Slf4j
public class OrderService {
    private final OrderRepository orderRepository;
    // Domain events go out through Spring, and OrderEventRelay forwards them to
    // Kafka only after this transaction commits. Publishing straight to Kafka
    // from inside the transaction let replies arrive before the order row was
    // visible, and those orders were silently stranded at PENDING.
    private final ApplicationEventPublisher events;

    /**
     * @param callerId the caller's id from the verified JWT. The request body no
     *                 longer carries a userId, because when it did, a caller
     *                 could book an order in anyone's name.
     */
    @Transactional
    public Order createOrder(CreateOrderRequest req, Long callerId) {
        BigDecimal total = req.getItems().stream()
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = Order.builder()
                .userId(callerId)
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

        // Relayed to Kafka after commit, never before.
        events.publishEvent(new OrderDomainEvents.OrderCreated(saved));

        log.info("Order created: id={}, userId={}, amount={}", saved.getId(), saved.getUserId(), total);
        return saved;
    }

    public Order getOrder(Long id, Long callerId) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + id));
        // Deliberately the same exception as "no such order": telling a caller
        // that an order exists but belongs to someone else leaks that the id is
        // real, which is enough to enumerate the order table.
        if (!order.getUserId().equals(callerId) && !AuthenticatedUser.isAdmin()) {
            throw new OrderNotFoundException("Order not found: " + id);
        }
        return order;
    }

    public List<Order> getUserOrders(Long userId, Long callerId) {
        if (!userId.equals(callerId) && !AuthenticatedUser.isAdmin()) {
            throw new AccessDeniedException("Cannot read another user's orders");
        }
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public Order cancelOrder(Long id, Long callerId) {
        Order order = getOrder(id, callerId);   // ownership enforced here
        if (order.getStatus() == Order.OrderStatus.SHIPPED ||
            order.getStatus() == Order.OrderStatus.DELIVERED) {
            throw new RuntimeException("Cannot cancel order in status: " + order.getStatus());
        }
        order.setStatus(Order.OrderStatus.CANCELLED);
        Order saved = orderRepository.save(order);
        events.publishEvent(new OrderDomainEvents.OrderCancelled(saved, "User requested cancellation"));
        return saved;
    }

    // ---- Saga callbacks. These run from Kafka consumers, not from an HTTP
    // ---- request, so there is no caller to authorise; they look the order up
    // ---- directly by id.

    // Payment succeeded → complete the saga by marking the order PAID.
    // Without this the order would sit at PENDING forever after a successful payment.
    @Transactional
    public void markPaid(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            // The money has already moved by the time this event exists, so an
            // order we cannot find here is a paid order nobody will ever mark
            // paid — and StaleOrderReaper will then cancel it, with no refund
            // anywhere in the system. This must never be silent.
            log.error("PAYMENT WITHOUT ORDER: payment.processed for unknown orderId={} "
                    + "- the customer may have been charged for an order that does not exist", orderId);
            return;
        }
        if (order.getStatus() != Order.OrderStatus.PENDING) {
            return; // already terminal (e.g. cancelled) — don't override
        }
        order.setStatus(Order.OrderStatus.PAID);
        orderRepository.save(order);
        log.info("Order {} marked PAID", orderId);
    }

    // Payment failed → cancel the order and release reserved stock via the event.
    @Transactional
    public void cancelDueToPaymentFailure(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.error("Cannot cancel unknown orderId={} ({})", orderId, reason);
            return;
        }
        if (order.getStatus() == Order.OrderStatus.CANCELLED) {
            return;
        }
        order.setStatus(Order.OrderStatus.CANCELLED);
        Order saved = orderRepository.save(order);
        events.publishEvent(new OrderDomainEvents.OrderCancelled(saved, reason));
        log.warn("Order {} cancelled due to payment failure: {}", orderId, reason);
    }

    @Transactional
    public void cancelDueToOutOfStock(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            // With the event relay this should be unreachable: the reply cannot
            // exist before the order commits. It stayed as a loud error because
            // silence here is exactly what hid the original bug - four orders
            // stranded at PENDING with not one line in the logs.
            log.error("Inventory replied about unknown orderId={} ({}) - "
                    + "the order will stay PENDING until the reaper cancels it", orderId, reason);
            return;
        }
        if (order.getStatus() != Order.OrderStatus.PENDING) {
            return; // already handled or in a terminal state — avoid double-cancelling
        }
        order.setStatus(Order.OrderStatus.CANCELLED);
        Order saved = orderRepository.save(order);
        events.publishEvent(new OrderDomainEvents.OrderCancelled(saved, reason));
        log.warn("Order {} cancelled due to insufficient stock: {}", orderId, reason);
    }
}
