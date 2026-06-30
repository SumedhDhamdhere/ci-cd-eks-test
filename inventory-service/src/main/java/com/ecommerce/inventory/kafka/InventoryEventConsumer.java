package com.ecommerce.inventory.kafka;
import com.ecommerce.inventory.service.InventoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component @RequiredArgsConstructor @Slf4j
public class InventoryEventConsumer {
    private final InventoryService inventoryService;
    private final InventoryEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private record ReservedItem(Long productId, Integer quantity) {}

    // Consumes from order.created → reserve stock for every item, then reports
    // a definitive success/failure result so payment + order services know
    // whether it's safe to proceed (instead of acting on order.created blindly).
    @KafkaListener(
        topics = "order.created",
        groupId = "inventory-service-group",
        concurrency = "6"  // 6 threads = 6 partitions can be consumed in parallel
    )
    public void handleOrderCreated(String message) {
        JsonNode event;
        try {
            event = objectMapper.readTree(message);
        } catch (Exception e) {
            log.error("Error parsing order.created event: {}", e.getMessage());
            return;
        }
        Long orderId = event.get("orderId").asLong();
        Long userId = event.get("userId").asLong();
        BigDecimal amount = new BigDecimal(event.get("amount").asText());
        log.info("Received order.created event for orderId={}", orderId);

        List<ReservedItem> reserved = new ArrayList<>();
        String failReason = null;
        for (JsonNode item : event.get("items")) {
            Long productId = item.get("productId").asLong();
            Integer quantity = item.get("quantity").asInt();
            try {
                if (inventoryService.reserveStock(productId, quantity, orderId)) {
                    reserved.add(new ReservedItem(productId, quantity));
                } else {
                    failReason = "Insufficient stock for productId=" + productId;
                    break;
                }
            } catch (Exception e) {
                failReason = e.getMessage();
                break;
            }
        }

        if (failReason != null) {
            for (ReservedItem r : reserved) {
                inventoryService.releaseStock(r.productId(), r.quantity());
            }
            log.warn("Reservation failed for orderId={}: {}", orderId, failReason);
            eventPublisher.publishInventoryResult(orderId, userId, amount, false, failReason);
        } else {
            eventPublisher.publishInventoryResult(orderId, userId, amount, true, null);
        }
    }

    // Consumes from order.cancelled → release reserved stock
    @KafkaListener(
        topics = "order.cancelled",
        groupId = "inventory-service-group",
        concurrency = "3"
    )
    public void handleOrderCancelled(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            Long orderId = event.get("orderId").asLong();
            log.info("Received order.cancelled, releasing stock for orderId={}", orderId);
            for (JsonNode item : event.get("items")) {
                Long productId = item.get("productId").asLong();
                Integer quantity = item.get("quantity").asInt();
                inventoryService.releaseStock(productId, quantity);
            }
        } catch (Exception e) {
            log.error("Error processing order.cancelled event: {}", e.getMessage());
        }
    }
}
