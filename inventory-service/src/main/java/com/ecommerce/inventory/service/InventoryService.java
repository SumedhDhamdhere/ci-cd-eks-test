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
    // Tracks what each order reserved, so it can be confirmed or released later.
    // payment.processed carries only orderId/amount/success — no items — so
    // inventory must remember its own reservations.
    private static final String RESERVATION_PREFIX = "inventory:reservation:";
    private static final long RESERVATION_TTL_HOURS = 24;

    /**
     * Reserve stock atomically.
     *
     * The previous version took a Redis lock, did a read-modify-write, and
     * released the lock in a finally block — but the method is @Transactional,
     * so the commit happens AFTER finally runs. The lock was gone before the
     * write was visible, letting two threads read the same `reserved` value.
     * A load test (40 concurrent orders against 10 units) sold 18.
     *
     * Now the availability check and the increment are one conditional UPDATE,
     * so the database serialises concurrent callers. A lock cannot be released
     * too early if there is no lock.
     */
    @Transactional
    public boolean reserveStock(Long productId, Integer quantity, Long orderId) {
        int updated = inventoryRepository.reserveAtomic(productId, quantity);

        if (updated == 0) {
            log.warn("Insufficient stock: productId={}, requested={}, orderId={}",
                    productId, quantity, orderId);
            return false;
        }

        String key = RESERVATION_PREFIX + orderId;
        redisTemplate.opsForHash().increment(key, productId.toString(), quantity);
        redisTemplate.expire(key, RESERVATION_TTL_HOURS, TimeUnit.HOURS);

        log.info("Stock reserved: productId={}, quantity={}, orderId={}", productId, quantity, orderId);
        return true;
    }

    /**
     * Payment succeeded — convert this order's reservations into real deductions.
     *
     * Nothing consumed payment.processed before, so confirmStock() was only ever
     * reached from a unit test. Reservations piled up and `quantity` never moved:
     * 18 paid orders left stock sitting at its original 10.
     *
     * Idempotent — a redelivered event finds the key already deleted.
     */
    @Transactional
    public void confirmOrder(Long orderId) {
        String key = RESERVATION_PREFIX + orderId;
        var reservations = redisTemplate.opsForHash().entries(key);

        if (reservations.isEmpty()) {
            log.warn("Nothing to confirm for orderId={} (already handled or expired)", orderId);
            return;
        }

        reservations.forEach((pid, qty) -> {
            Long productId = Long.valueOf(pid.toString());
            Integer quantity = Integer.valueOf(qty.toString());
            inventoryRepository.confirmAtomic(productId, quantity);
            log.info("Stock deducted: productId={}, quantity={}, orderId={}", productId, quantity, orderId);
        });

        redisTemplate.delete(key);
    }

    /**
     * Payment failed or the order was cancelled — hand the reservation back.
     * Idempotent for the same reason as confirmOrder.
     */
    @Transactional
    public void releaseOrder(Long orderId) {
        String key = RESERVATION_PREFIX + orderId;
        var reservations = redisTemplate.opsForHash().entries(key);

        if (reservations.isEmpty()) {
            log.warn("Nothing to release for orderId={} (already handled or expired)", orderId);
            return;
        }

        reservations.forEach((pid, qty) -> {
            Long productId = Long.valueOf(pid.toString());
            Integer quantity = Integer.valueOf(qty.toString());
            inventoryRepository.releaseAtomic(productId, quantity);
            log.info("Stock released: productId={}, quantity={}, orderId={}", productId, quantity, orderId);
        });

        redisTemplate.delete(key);
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
