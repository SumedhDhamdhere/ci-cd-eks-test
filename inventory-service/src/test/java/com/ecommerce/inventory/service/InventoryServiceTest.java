package com.ecommerce.inventory.service;

import com.ecommerce.inventory.model.Inventory;
import com.ecommerce.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock private InventoryRepository inventoryRepository;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(inventoryRepository, redisTemplate);
    }

    @Test
    void reserveStock_returnsFalseWhenLockNotAcquired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("inventory:lock:1"), any(), eq(30L), any())).thenReturn(false);

        boolean result = inventoryService.reserveStock(1L, 5, 100L);

        assertThat(result).isFalse();
        verify(inventoryRepository, never()).findByProductId(any());
    }

    @Test
    void reserveStock_returnsFalseWhenInsufficientStock() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("inventory:lock:1"), any(), eq(30L), any())).thenReturn(true);
        Inventory inventory = Inventory.builder().productId(1L).quantity(5).reserved(3).build();
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));

        boolean result = inventoryService.reserveStock(1L, 5, 100L);

        assertThat(result).isFalse();
        verify(inventoryRepository, never()).save(any());
        verify(redisTemplate).delete("inventory:lock:1");
    }

    @Test
    void reserveStock_succeedsAndIncrementsReserved() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("inventory:lock:1"), any(), eq(30L), any())).thenReturn(true);
        Inventory inventory = Inventory.builder().productId(1L).quantity(10).reserved(2).build();
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));

        boolean result = inventoryService.reserveStock(1L, 5, 100L);

        assertThat(result).isTrue();
        assertThat(inventory.getReserved()).isEqualTo(7);
        verify(inventoryRepository).save(inventory);
        verify(redisTemplate).delete("inventory:lock:1");
    }

    @Test
    void reserveStock_throwsWhenProductNotInInventory() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("inventory:lock:99"), any(), eq(30L), any())).thenReturn(true);
        when(inventoryRepository.findByProductId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.reserveStock(99L, 1, 100L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");

        verify(redisTemplate).delete("inventory:lock:99");
    }

    @Test
    void releaseStock_decrementsReservedButNotBelowZero() {
        Inventory inventory = Inventory.builder().productId(1L).quantity(10).reserved(2).build();
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));

        inventoryService.releaseStock(1L, 5);

        assertThat(inventory.getReserved()).isEqualTo(0);
        verify(inventoryRepository).save(inventory);
    }

    @Test
    void confirmStock_deductsQuantityAndReduceReserved() {
        Inventory inventory = Inventory.builder().productId(1L).quantity(10).reserved(4).build();
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));

        inventoryService.confirmStock(1L, 4);

        assertThat(inventory.getQuantity()).isEqualTo(6);
        assertThat(inventory.getReserved()).isEqualTo(0);
    }
}
