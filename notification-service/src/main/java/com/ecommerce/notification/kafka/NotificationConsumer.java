package com.ecommerce.notification.kafka;
import com.ecommerce.notification.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class NotificationConsumer {
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.created", groupId = "notification-service-group", concurrency = "4")
    public void handleOrderCreated(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            Long orderId = event.get("orderId").asLong();
            Long userId  = event.get("userId").asLong();
            log.info("Sending order confirmation notification: orderId={}", orderId);
            notificationService.sendOrderConfirmation(orderId, userId);
        } catch (Exception e) {
            log.error("Failed to send order notification: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "payment.processed", groupId = "notification-service-group", concurrency = "4")
    public void handlePaymentProcessed(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            Long orderId = event.get("orderId").asLong();
            log.info("Sending payment success notification: orderId={}", orderId);
            notificationService.sendPaymentSuccess(orderId);
        } catch (Exception e) {
            log.error("Failed to send payment notification: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "order.cancelled", groupId = "notification-service-group", concurrency = "3")
    public void handleOrderCancelled(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            Long orderId = event.get("orderId").asLong();
            log.info("Sending cancellation notification: orderId={}", orderId);
            notificationService.sendOrderCancelled(orderId);
        } catch (Exception e) {
            log.error("Failed to send cancellation notification: {}", e.getMessage());
        }
    }
}
