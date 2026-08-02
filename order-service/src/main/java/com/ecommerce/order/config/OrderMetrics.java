package com.ecommerce.order.config;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.repository.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Domain metrics — the layer infrastructure monitoring cannot reach.
 *
 * Everything Prometheus scraped before this existed described the machine: heap,
 * request counts, consumer lag. All of it was green on the day four orders were
 * stranded at PENDING for good. The pods were healthy, lag was 0, nothing had
 * thrown. The system was broken in a way no infrastructure metric can express.
 *
 * These are the numbers that describe the BUSINESS, and each one exists because
 * something went wrong without it.
 */
@Component
@RequiredArgsConstructor
public class OrderMetrics {

    private final MeterRegistry registry;
    private final OrderRepository orderRepository;

    private Counter created;
    private Counter paid;
    private Counter cancelled;
    private Counter paymentWithoutOrder;
    private Timer sagaDuration;

    @PostConstruct
    void register() {
        created   = Counter.builder("orders_created_total")
                .description("Orders accepted from customers").register(registry);
        paid      = Counter.builder("orders_completed_total").tag("outcome", "paid")
                .description("Orders that reached a terminal state").register(registry);
        cancelled = Counter.builder("orders_completed_total").tag("outcome", "cancelled")
                .description("Orders that reached a terminal state").register(registry);

        // If this is ever non-zero, money moved for an order the system cannot
        // find — and the stale-order reaper will then cancel it, with no refund
        // path anywhere. It should page a human, not sit on a dashboard.
        paymentWithoutOrder = Counter.builder("payment_without_order_total")
                .description("payment.processed arrived for an orderId that does not exist")
                .register(registry);

        sagaDuration = Timer.builder("saga_duration_seconds")
                .description("Time from order accepted to terminal state")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // THE metric. Age of the oldest order still awaiting a saga reply.
        // Normal is a few seconds. Anything in the minutes means replies are
        // being lost, and that is invisible everywhere else.
        registry.gauge("orders_pending_oldest_age_seconds", this,
                OrderMetrics::oldestPendingAgeSeconds);

        registry.gauge("orders_pending_count", this,
                m -> m.orderRepository.countByStatus(Order.OrderStatus.PENDING));
    }

    double oldestPendingAgeSeconds() {
        try {
            LocalDateTime oldest = orderRepository.findOldestCreatedAtByStatus(Order.OrderStatus.PENDING);
            return oldest == null ? 0d : Duration.between(oldest, LocalDateTime.now()).toSeconds();
        } catch (Exception e) {
            // A gauge that throws takes the whole scrape down with it.
            return -1d;
        }
    }

    public void orderCreated() { created.increment(); }
    public void orderPaid(LocalDateTime createdAt) {
        paid.increment();
        recordSaga(createdAt);
    }
    public void orderCancelled(LocalDateTime createdAt) {
        cancelled.increment();
        recordSaga(createdAt);
    }
    public void paymentWithoutOrder() { paymentWithoutOrder.increment(); }

    private void recordSaga(LocalDateTime createdAt) {
        if (createdAt != null) {
            sagaDuration.record(Duration.between(createdAt, LocalDateTime.now()));
        }
    }
}
