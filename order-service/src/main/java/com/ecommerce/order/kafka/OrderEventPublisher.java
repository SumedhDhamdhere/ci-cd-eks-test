package com.ecommerce.order.kafka;
import com.ecommerce.order.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component @RequiredArgsConstructor @Slf4j
public class OrderEventPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private List<Map<String, Object>> itemsPayload(Order order) {
        return order.getItems().stream()
                .map(i -> Map.<String, Object>of("productId", i.getProductId(), "quantity", i.getQuantity()))
                .collect(Collectors.toList());
    }

    public void publishOrderCreated(Order order) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "orderId", order.getId(),
                "userId", order.getUserId(),
                "amount", order.getTotalAmount(),
                "items", itemsPayload(order),
                "event", "ORDER_CREATED",
                "timestamp", System.currentTimeMillis()
            ));
            // key = orderId.toString() → same order always goes to same partition
            kafkaTemplate.send("order.created", order.getId().toString(), payload);
            log.info("Published order.created event for orderId={}", order.getId());
        } catch (Exception e) {
            log.error("Failed to publish order.created event", e);
        }
    }

    public void publishOrderCancelled(Order order, String reason) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "orderId", order.getId(),
                "reason", reason,
                "items", itemsPayload(order),
                "event", "ORDER_CANCELLED",
                "timestamp", System.currentTimeMillis()
            ));
            kafkaTemplate.send("order.cancelled", order.getId().toString(), payload);
            log.info("Published order.cancelled for orderId={}", order.getId());
        } catch (Exception e) {
            log.error("Failed to publish order.cancelled", e);
        }
    }
}
