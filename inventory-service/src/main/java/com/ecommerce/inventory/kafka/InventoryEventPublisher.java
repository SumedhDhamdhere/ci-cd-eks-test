package com.ecommerce.inventory.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component @RequiredArgsConstructor @Slf4j
public class InventoryEventPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishInventoryResult(Long orderId, Long userId, BigDecimal amount, boolean success, String reason) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", orderId);
            payload.put("userId", userId);
            payload.put("amount", amount);
            payload.put("success", success);
            payload.put("reason", reason);
            payload.put("event", "INVENTORY_UPDATED");
            payload.put("timestamp", System.currentTimeMillis());
            kafkaTemplate.send("inventory.updated", orderId.toString(), objectMapper.writeValueAsString(payload));
            log.info("Published inventory.updated for orderId={}, success={}", orderId, success);
        } catch (Exception e) {
            log.error("Failed to publish inventory.updated event: {}", e.getMessage());
        }
    }
}
