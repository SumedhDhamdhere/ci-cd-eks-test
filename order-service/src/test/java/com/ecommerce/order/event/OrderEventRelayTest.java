package com.ecommerce.order.event;

import com.ecommerce.order.kafka.OrderEventPublisher;
import com.ecommerce.order.model.Order;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderEventRelayTest {

    private final OrderEventPublisher publisher = mock(OrderEventPublisher.class);
    private final OrderEventRelay relay = new OrderEventRelay(publisher);

    @Test
    void forwardsOrderCreatedToKafka() {
        Order order = Order.builder().id(1L).userId(7L).build();

        relay.onOrderCreated(new OrderDomainEvents.OrderCreated(order));

        verify(publisher).publishOrderCreated(order);
    }

    @Test
    void forwardsOrderCancelledToKafka() {
        Order order = Order.builder().id(1L).userId(7L).build();

        relay.onOrderCancelled(new OrderDomainEvents.OrderCancelled(order, "no stock"));

        verify(publisher).publishOrderCancelled(order, "no stock");
    }

    /**
     * The phase is the entire point of this class, so assert it rather than
     * trusting the annotation to stay put.
     *
     * With the default phase (AFTER_COMMIT is NOT the default) the event would
     * go to Kafka before the order row was committed, and a fast reply would
     * find no order to update — which is how four orders were stranded at
     * PENDING during a 20-order burst.
     */
    @Test
    void bothListenersFireOnlyAfterTheTransactionCommits() throws Exception {
        for (String name : new String[]{"onOrderCreated", "onOrderCancelled"}) {
            Method m = java.util.Arrays.stream(OrderEventRelay.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            TransactionalEventListener ann = m.getAnnotation(TransactionalEventListener.class);
            assertThat(ann).as("%s must be a transactional listener", name).isNotNull();
            assertThat(ann.phase()).as("%s must wait for commit", name)
                    .isEqualTo(TransactionPhase.AFTER_COMMIT);
        }
    }
}
