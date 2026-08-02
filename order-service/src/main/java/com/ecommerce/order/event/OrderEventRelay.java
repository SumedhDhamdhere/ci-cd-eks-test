package com.ecommerce.order.event;

import com.ecommerce.order.kafka.OrderEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Relays domain events to Kafka AFTER the database transaction commits.
 *
 * WHY THIS EXISTS
 *   OrderService used to call the Kafka publisher directly, inside the
 *   @Transactional method. The send goes out immediately; the commit happens
 *   when the method returns. That leaves a window where the event is already in
 *   Kafka but the order row is not yet visible to anyone else.
 *
 *   Under a 20-order burst that window was wide enough to lose orders. Measured
 *   on a live cluster:
 *
 *     21 orders in the database
 *     21 order.created events in Kafka        - nothing lost
 *     21 inventory.updated replies in Kafka   - every order got an answer
 *     consumer lag 0                          - every reply was consumed
 *     ...yet 4 orders sat at PENDING forever, with not one log line about them.
 *
 *   inventory-service rejected those four in milliseconds and replied before the
 *   creating transaction had committed. order-service looked the order up,
 *   found nothing, and returned silently. Orders 13-16, created within 180ms of
 *   each other, all at the peak of the burst.
 *
 *   AFTER_COMMIT closes it: the event cannot reach Kafka until the row it talks
 *   about is readable.
 *
 * WHAT THIS STILL DOES NOT GUARANTEE
 *   If the process dies between the commit and the send, the event is lost -
 *   the commit already happened, so nothing will retry it. A transactional
 *   outbox (write the event to a table in the same transaction, relay it from
 *   there) is the only way to make those two atomic.
 *
 *   That gap is deliberately covered by StaleOrderReaper rather than by an
 *   outbox: the reaper cancels anything still PENDING past its timeout, so a
 *   lost event degrades to a cancelled order instead of a stuck one. If this
 *   ever needs to be exactly-once, add the outbox - do not widen the reaper.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventRelay {

    private final OrderEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderDomainEvents.OrderCreated event) {
        eventPublisher.publishOrderCreated(event.order());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCancelled(OrderDomainEvents.OrderCancelled event) {
        eventPublisher.publishOrderCancelled(event.order(), event.reason());
    }
}
