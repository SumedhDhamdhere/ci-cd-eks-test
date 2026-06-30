package com.ecommerce.product.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ecommerce.product.model.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component @RequiredArgsConstructor @Slf4j
public class ProductEventPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishProductCreated(Product product) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "productId", product.getId(),
                "name", product.getName(),
                "event", "PRODUCT_CREATED",
                "timestamp", System.currentTimeMillis()
            ));
            kafkaTemplate.send("product.created", product.getId().toString(), payload);
            log.info("Published product.created event for productId={}", product.getId());
        } catch (Exception e) {
            log.error("Failed to publish product.created event", e);
        }
    }
}
