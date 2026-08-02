package com.ecommerce.inventory.service;

import com.ecommerce.inventory.model.Inventory;
import com.ecommerce.inventory.config.InventoryMetrics;
import com.ecommerce.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryServiceTest {

    @Mock private InventoryRepository inventoryRepository;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @Mock private InventoryMetrics metrics;

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        inventoryService = new InventoryService(inventoryRepository, redisTemplate, metrics);
    }

    // ---------- reserveStock: now a single atomic UPDATE ----------

    @Test
    void reserveStock_returnsFalseWhenNoRowsUpdated() {
        // reserveAtomic returns 0 when `quantity - reserved < requested`,
        // so the database itself rejected the reservation.
        when(inventoryRepository.reserveAtomic(1L, 5)).thenReturn(0);

        boolean result = inventoryService.reserveStock(1L, 5, 100L);

        assertThat(result).isFalse();
        // Nothing should be recorded against the order if nothing was reserved.
        verify(hashOperations, never()).increment(anyString(), any(), anyLong());
    }

    @Test
    void reserveStock_succeedsAndRecordsReservationAgainstTheOrder() {
        when(inventoryRepository.reserveAtomic(1L, 5)).thenReturn(1);

        boolean result = inventoryService.reserveStock(1L, 5, 100L);

        assertThat(result).isTrue();
        verify(inventoryRepository).reserveAtomic(1L, 5);
        // The reservation must be tracked — payment.processed carries no items,
        // so this is the only record of what to confirm or release later.
        verify(hashOperations).increment("inventory:reservation:100", "1", 5L);
        verify(redisTemplate).expire(eq("inventory:reservation:100"), eq(24L), any());
    }

    @Test
    void reserveStock_neverDoesReadModifyWrite() {
        // Regression guard for the oversell bug: the old implementation read the
        // row, computed availability in Java, then saved. Under concurrency two
        // threads read the same value and both reserved. The check and the
        // increment must stay in one statement.
        when(inventoryRepository.reserveAtomic(1L, 1)).thenReturn(1);

        inventoryService.reserveStock(1L, 1, 100L);

        verify(inventoryRepository, never()).findByProductId(any());
        verify(inventoryRepository, never()).save(any());
    }

    // ---------- confirmOrder: the step that was missing entirely ----------

    @Test
    void confirmOrder_deductsEveryReservedItem() {
        Map<Object, Object> reserved = new LinkedHashMap<>();
        reserved.put("1", 2);
        reserved.put("7", 3);
        when(hashOperations.entries("inventory:reservation:100")).thenReturn(reserved);

        inventoryService.confirmOrder(100L);

        // This is what never happened before: quantity actually comes down.
        verify(inventoryRepository).confirmAtomic(1L, 2);
        verify(inventoryRepository).confirmAtomic(7L, 3);
        verify(redisTemplate).delete("inventory:reservation:100");
    }

    @Test
    void confirmOrder_isIdempotentOnRedelivery() {
        // Kafka can redeliver. The second delivery finds the key gone and must
        // not deduct the stock a second time.
        when(hashOperations.entries("inventory:reservation:100")).thenReturn(Map.of());

        inventoryService.confirmOrder(100L);

        verify(inventoryRepository, never()).confirmAtomic(anyLong(), any());
    }

    // ---------- releaseOrder ----------

    @Test
    void releaseOrder_releasesEveryReservedItem() {
        Map<Object, Object> reserved = new LinkedHashMap<>();
        reserved.put("1", 4);
        when(hashOperations.entries("inventory:reservation:55")).thenReturn(reserved);

        inventoryService.releaseOrder(55L);

        verify(inventoryRepository).releaseAtomic(1L, 4);
        verify(redisTemplate).delete("inventory:reservation:55");
    }

    @Test
    void releaseOrder_isIdempotentOnRedelivery() {
        when(hashOperations.entries("inventory:reservation:55")).thenReturn(Map.of());

        inventoryService.releaseOrder(55L);

        verify(inventoryRepository, never()).releaseAtomic(anyLong(), any());
    }

    // ---------- unchanged behaviour ----------

    @Test
    void releaseStock_decrementsReservedButNotBelowZero() {
        Inventory inventory = Inventory.builder().productId(1L).quantity(10).reserved(2).build();
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));

        inventoryService.releaseStock(1L, 5);

        assertThat(inventory.getReserved()).isEqualTo(0);
        verify(inventoryRepository).save(inventory);
    }

    @Test
    void confirmStock_deductsQuantityAndReducesReserved() {
        Inventory inventory = Inventory.builder().productId(1L).quantity(10).reserved(4).build();
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));

        inventoryService.confirmStock(1L, 4);

        assertThat(inventory.getQuantity()).isEqualTo(6);
        assertThat(inventory.getReserved()).isEqualTo(0);
    }

    @Test
    void restock_rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> inventoryService.restock(1L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void getStock_throwsWhenProductUnknown() {
        when(inventoryRepository.findByProductId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.getStock(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }
}
