package com.ecommerce.order.service;

import com.ecommerce.order.dto.CreateOrderRequest;
import com.ecommerce.order.event.OrderDomainEvents;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    // OrderService no longer talks to Kafka directly. It raises a Spring event
    // and OrderEventRelay forwards it to Kafka after the transaction commits,
    // so a saga reply can never arrive before the order row is visible.
    @Mock private ApplicationEventPublisher events;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, events);
    }

    private CreateOrderRequest.OrderItemRequest item(long productId, int qty, String price) {
        CreateOrderRequest.OrderItemRequest item = new CreateOrderRequest.OrderItemRequest();
        item.setProductId(productId);
        item.setProductName("Widget");
        item.setQuantity(qty);
        item.setPrice(new BigDecimal(price));
        return item;
    }

    @Test
    void createOrder_calculatesTotalFromItemsAndPublishesEvent() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShippingAddress("123 Main St");
        req.setItems(List.of(item(1L, 2, "10.00"), item(2L, 1, "5.00")));

        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(42L);
            return o;
        });

        Order result = orderService.createOrder(req, 1L);

        assertThat(result.getTotalAmount()).isEqualByComparingTo("25.00");
        assertThat(result.getItems()).hasSize(2);
        verify(events).publishEvent(new OrderDomainEvents.OrderCreated(result));
    }

    @Test
    void getOrder_throwsWhenNotFound() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(99L, 1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void cancelOrder_throwsWhenAlreadyShipped() {
        Order order = Order.builder().id(1L).userId(1L).status(Order.OrderStatus.SHIPPED).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancelOrder(1L, 1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Cannot cancel");

        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancelOrder_succeedsWhenPendingAndPublishesEvent() {
        Order order = Order.builder().id(1L).userId(1L).status(Order.OrderStatus.PENDING).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = orderService.cancelOrder(1L, 1L);

        assertThat(result.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        verify(events).publishEvent(new OrderDomainEvents.OrderCancelled(result, "User requested cancellation"));
    }

    @Test
    void cancelDueToOutOfStock_noOpWhenOrderNotPending() {
        Order order = Order.builder().id(1L).status(Order.OrderStatus.CANCELLED).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.cancelDueToOutOfStock(1L, "no stock");

        verify(orderRepository, never()).save(any());
        verify(events, never()).publishEvent(any(OrderDomainEvents.OrderCancelled.class));
    }

    @Test
    void cancelDueToOutOfStock_noOpWhenOrderMissing() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());

        orderService.cancelDueToOutOfStock(1L, "no stock");

        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancelDueToOutOfStock_cancelsWhenPending() {
        Order order = Order.builder().id(1L).userId(1L).status(Order.OrderStatus.PENDING).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.cancelDueToOutOfStock(1L, "Insufficient stock");

        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        verify(events).publishEvent(new OrderDomainEvents.OrderCancelled(order, "Insufficient stock"));
    }

    // ---- saga replies about orders that cannot be found ----
    // These used to `return;` without a word, which is precisely why the bug
    // was invisible: 21 replies consumed, 4 orders left at PENDING, zero logs.

    @Test
    void markPaid_doesNothingButComplainsWhenTheOrderIsUnknown() {
        when(orderRepository.findById(404L)).thenReturn(Optional.empty());

        orderService.markPaid(404L);

        verify(orderRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void markPaid_doesNotOverrideATerminalStatus() {
        Order cancelled = Order.builder().id(1L).userId(1L)
                .status(Order.OrderStatus.CANCELLED).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(cancelled));

        orderService.markPaid(1L);

        assertThat(cancelled.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void markPaid_marksAPendingOrderPaid() {
        Order pending = Order.builder().id(1L).userId(1L)
                .status(Order.OrderStatus.PENDING).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(pending));

        orderService.markPaid(1L);

        assertThat(pending.getStatus()).isEqualTo(Order.OrderStatus.PAID);
        verify(orderRepository).save(pending);
    }

    @Test
    void cancelDueToOutOfStock_doesNothingWhenTheOrderIsUnknown() {
        when(orderRepository.findById(404L)).thenReturn(Optional.empty());

        orderService.cancelDueToOutOfStock(404L, "no stock");

        verify(orderRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void cancelDueToPaymentFailure_doesNothingWhenTheOrderIsUnknown() {
        when(orderRepository.findById(404L)).thenReturn(Optional.empty());

        orderService.cancelDueToPaymentFailure(404L, "declined");

        verify(orderRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void cancelDueToPaymentFailure_cancelsAndAnnouncesIt() {
        Order pending = Order.builder().id(1L).userId(1L)
                .status(Order.OrderStatus.PENDING).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(pending));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        orderService.cancelDueToPaymentFailure(1L, "declined");

        assertThat(pending.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        verify(events).publishEvent(new OrderDomainEvents.OrderCancelled(pending, "declined"));
    }
}
