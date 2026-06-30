package com.ecommerce.payment.kafka;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component @RequiredArgsConstructor @Slf4j
public class PaymentEventPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishPaymentProcessed(Long orderId, Long userId, BigDecimal amount,
                                         String txnId, boolean success) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", orderId);
            payload.put("userId", userId);
            payload.put("amount", amount);
            payload.put("transactionId", txnId);
            payload.put("success", success);
            payload.put("event", "PAYMENT_PROCESSED");
            payload.put("timestamp", System.currentTimeMillis());

            kafkaTemplate.send("payment.processed", orderId.toString(),
                    objectMapper.writeValueAsString(payload));
            log.info("Published payment.processed for orderId={}, success={}", orderId, success);
        } catch (Exception e) {
            log.error("Failed to publish payment event: {}", e.getMessage());
        }
    }
}
