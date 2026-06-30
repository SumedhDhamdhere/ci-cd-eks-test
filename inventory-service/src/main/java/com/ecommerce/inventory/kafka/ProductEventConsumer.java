package com.ecommerce.inventory.kafka;

import com.ecommerce.inventory.model.Inventory;
import com.ecommerce.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class ProductEventConsumer {
    private final InventoryRepository inventoryRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "product.created", groupId = "inventory-service-group")
    public void handleProductCreated(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            Long productId = event.get("productId").asLong();
            if (inventoryRepository.findByProductId(productId).isPresent()) {
                log.info("Inventory row already exists for productId={}", productId);
                return;
            }
            Inventory inventory = Inventory.builder()
                    .productId(productId).quantity(0).reserved(0).build();
            inventoryRepository.save(inventory);
            log.info("Created inventory row for productId={}", productId);
        } catch (Exception e) {
            log.error("Error processing product.created event: {}", e.getMessage());
        }
    }
}
