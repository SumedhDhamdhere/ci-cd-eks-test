package com.ecommerce.order.service;

import com.ecommerce.order.dto.CreateOrderRequest;
import com.ecommerce.order.kafka.OrderEventPublisher;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @Mock private OrderEventPublisher eventPublisher;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, eventPublisher);
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
        req.setUserId(1L);
        req.setShippingAddress("123 Main St");
        req.setItems(List.of(item(1L, 2, "10.00"), item(2L, 1, "5.00")));

        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(42L);
            return o;
        });

        Order result = orderService.createOrder(req);

        assertThat(result.getTotalAmount()).isEqualByComparingTo("25.00");
        assertThat(result.getItems()).hasSize(2);
        verify(eventPublisher).publishOrderCreated(result);
    }

    @Test
    void getOrder_throwsWhenNotFound() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void cancelOrder_throwsWhenAlreadyShipped() {
        Order order = Order.builder().id(1L).status(Order.OrderStatus.SHIPPED).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancelOrder(1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Cannot cancel");

        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancelOrder_succeedsWhenPendingAndPublishesEvent() {
        Order order = Order.builder().id(1L).status(Order.OrderStatus.PENDING).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = orderService.cancelOrder(1L);

        assertThat(result.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        verify(eventPublisher).publishOrderCancelled(result, "User requested cancellation");
    }

    @Test
    void cancelDueToOutOfStock_noOpWhenOrderNotPending() {
        Order order = Order.builder().id(1L).status(Order.OrderStatus.CANCELLED).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.cancelDueToOutOfStock(1L, "no stock");

        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishOrderCancelled(any(), any());
    }

    @Test
    void cancelDueToOutOfStock_noOpWhenOrderMissing() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());

        orderService.cancelDueToOutOfStock(1L, "no stock");

        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancelDueToOutOfStock_cancelsWhenPending() {
        Order order = Order.builder().id(1L).status(Order.OrderStatus.PENDING).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.cancelDueToOutOfStock(1L, "Insufficient stock");

        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        verify(eventPublisher).publishOrderCancelled(order, "Insufficient stock");
    }
}
