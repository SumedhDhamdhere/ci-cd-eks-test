package com.ecommerce.order.event;

import com.ecommerce.order.model.Order;

/**
 * In-process events published inside the transaction and relayed to Kafka only
 * once that transaction has COMMITTED. See OrderEventRelay for why.
 */
public final class OrderDomainEvents {

    private OrderDomainEvents() {}

    public record OrderCreated(Order order) {}

    public record OrderCancelled(Order order, String reason) {}
}
