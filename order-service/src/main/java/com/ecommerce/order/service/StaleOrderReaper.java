package com.ecommerce.order.service;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Cancels orders that never received a saga result.
 *
 * An order leaves PENDING only when inventory.result or payment.processed comes
 * back. If that event is lost the order sits at PENDING forever: the customer
 * sees a hung order, and any stock it reserved is never released.
 *
 * This is not hypothetical. Restarting the Kafka broker during a load test left
 * 12 of 40 orders permanently PENDING with consumer lag at zero — the events
 * were gone, so no retry could ever deliver them.
 *
 * acks=all plus idempotence makes that loss far less likely, but "far less
 * likely" is not "never", and a distributed saga needs a timeout for the case
 * where the reply genuinely never comes. This is that timeout.
 *
 * Cancelling publishes order.cancelled, which inventory-service consumes to
 * release the reservation — so the stock comes back too.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StaleOrderReaper {

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @Value("${order.stale-timeout-minutes:15}")
    private long staleTimeoutMinutes;

    /**
     * Runs every 5 minutes. Deliberately far longer than the saga's normal
     * round trip (a few seconds), so this only ever catches genuinely lost
     * events and never races a saga that is merely slow.
     */
    @Scheduled(fixedDelayString = "${order.reaper-interval-ms:300000}")
    public void cancelStaleOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleTimeoutMinutes);
        List<Order> stale = orderRepository.findByStatusAndCreatedAtBefore(
                Order.OrderStatus.PENDING, cutoff);

        if (stale.isEmpty()) {
            return;
        }

        log.warn("Found {} order(s) still PENDING after {} minutes — cancelling",
                stale.size(), staleTimeoutMinutes);

        for (Order order : stale) {
            try {
                // Goes through the service so order.cancelled is published and
                // inventory releases whatever this order was holding.
                orderService.cancelDueToPaymentFailure(order.getId(),
                        "No saga result within " + staleTimeoutMinutes + " minutes");
            } catch (Exception e) {
                // One bad order must not stop the rest of the sweep.
                log.error("Could not cancel stale order {}: {}", order.getId(), e.getMessage());
            }
        }
    }
}
