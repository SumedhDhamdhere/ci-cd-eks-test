package com.ecommerce.payment.kafka;
import com.ecommerce.payment.service.PaymentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class PaymentEventConsumer {
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    // Only act once inventory has confirmed stock was actually reserved —
    // never charge for an order that has nothing to ship.
    @KafkaListener(
        topics = "inventory.updated",
        groupId = "payment-service-group",
        concurrency = "6"
    )
    public void handleInventoryUpdated(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            Long orderId = event.get("orderId").asLong();
            if (!event.get("success").asBoolean()) {
                log.info("Skipping payment for orderId={}, inventory reservation failed", orderId);
                return;
            }
            Long userId  = event.get("userId").asLong();
            java.math.BigDecimal amount = new java.math.BigDecimal(event.get("amount").asText());
            log.info("Processing payment for orderId={}", orderId);
            paymentService.processPayment(orderId, userId, amount);
        } catch (Exception e) {
            log.error("Payment processing error: {}", e.getMessage());
        }
    }
}
