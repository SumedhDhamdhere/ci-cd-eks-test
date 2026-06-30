package com.ecommerce.order.kafka;

import com.ecommerce.order.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class OrderEventConsumer {
    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    // If inventory couldn't reserve stock for this order, it must not stay PENDING
    // forever (or get paid for) — cancel it and let notification-service inform the user.
    @KafkaListener(topics = "inventory.updated", groupId = "order-service-group", concurrency = "6")
    public void handleInventoryUpdated(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            Long orderId = event.get("orderId").asLong();
            if (!event.get("success").asBoolean()) {
                String reason = event.hasNonNull("reason") ? event.get("reason").asText() : "Insufficient stock";
                orderService.cancelDueToOutOfStock(orderId, reason);
            }
        } catch (Exception e) {
            log.error("Error processing inventory.updated event: {}", e.getMessage());
        }
    }
}
