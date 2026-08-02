package com.ecommerce.order.repository;
import com.ecommerce.order.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);

    // Used by StaleOrderReaper. An order only leaves PENDING when a saga event
    // comes back, so anything still PENDING after the cutoff is one whose event
    // never arrived.
    List<Order> findByStatusAndCreatedAtBefore(Order.OrderStatus status, LocalDateTime cutoff);

    long countByStatus(Order.OrderStatus status);

    // Used by OrderMetrics to publish the age of the OLDEST order still waiting
    // on a saga reply. No infrastructure metric can express this: when four
    // orders were stranded at PENDING the pods were healthy, consumer lag was 0
    // and nothing had errored. This is the one number that would have shouted.
    @Query("SELECT MIN(o.createdAt) FROM Order o WHERE o.status = :status")
    LocalDateTime findOldestCreatedAtByStatus(@Param("status") Order.OrderStatus status);
}
