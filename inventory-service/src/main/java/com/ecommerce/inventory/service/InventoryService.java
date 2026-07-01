package com.ecommerce.inventory.service;
import com.ecommerce.inventory.model.Inventory;
import com.ecommerce.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.TimeUnit;

@Service @RequiredArgsConstructor @Slf4j
public class InventoryService {
    private final InventoryRepository inventoryRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private static final String LOCK_PREFIX = "inventory:lock:";
    private static final long LOCK_TTL = 30;

    @Transactional
    public boolean reserveStock(Long productId, Integer quantity, Long orderId) {
        String lockKey = LOCK_PREFIX + productId;

        // Redis distributed lock — prevents overselling
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, orderId.toString(), LOCK_TTL, TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(locked)) {
            log.warn("Could not acquire lock for productId={}", productId);
            return false;
        }

        try {
            Inventory inventory = inventoryRepository.findByProductId(productId)
                    .orElseThrow(() -> new RuntimeException("Product not found in inventory"));

            int available = inventory.getQuantity() - inventory.getReserved();
            if (available < quantity) {
                log.warn("Insufficient stock for productId={}, available={}, requested={}", productId, available, quantity);
                return false;
            }

            inventory.setReserved(inventory.getReserved() + quantity);
            inventoryRepository.save(inventory);
            log.info("Stock reserved: productId={}, quantity={}, orderId={}", productId, quantity, orderId);
            return true;
        } finally {
            // Always release lock
            redisTemplate.delete(lockKey);
        }
    }

    @Transactional
    public void releaseStock(Long productId, Integer quantity) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new RuntimeException("Product not found"));
        inventory.setReserved(Math.max(0, inventory.getReserved() - quantity));
        inventoryRepository.save(inventory);
        log.info("Stock released: productId={}, quantity={}", productId, quantity);
    }

    // Add stock for a product. Creates the inventory row if the product.created
    // event hasn't arrived yet (upsert) — a warehouse must be able to stock a
    // product regardless of Kafka timing.
    @Transactional
    public Inventory restock(Long productId, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Restock quantity must be positive");
        }
        // Single atomic INSERT ... ON CONFLICT — safe even if the product.created
        // consumer is concurrently creating the same product_id row.
        inventoryRepository.upsertStock(productId, quantity);
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new RuntimeException("Restock failed for productId=" + productId));
        log.info("Restocked productId={}, added={}, newQuantity={}", productId, quantity, inventory.getQuantity());
        return inventory;
    }

    public Inventory getStock(Long productId) {
        return inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new RuntimeException("Product not found in inventory: " + productId));
    }

    @Transactional
    public void confirmStock(Long productId, Integer quantity) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new RuntimeException("Product not found"));
        inventory.setReserved(Math.max(0, inventory.getReserved() - quantity));
        inventory.setQuantity(inventory.getQuantity() - quantity);
        inventoryRepository.save(inventory);
        log.info("Stock confirmed deducted: productId={}, quantity={}", productId, quantity);
    }
}
