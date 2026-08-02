package com.ecommerce.order.repository;
import com.ecommerce.order.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);

    // Used by StaleOrderReaper. An order only leaves PENDING when a saga event
    // comes back, so anything still PENDING after the cutoff is one whose event
    // never arrived.
    List<Order> findByStatusAndCreatedAtBefore(Order.OrderStatus status, LocalDateTime cutoff);
}
