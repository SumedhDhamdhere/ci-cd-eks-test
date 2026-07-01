package com.ecommerce.inventory.controller;

import com.ecommerce.inventory.model.Inventory;
import com.ecommerce.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// REST surface for warehouse operations. The rest of inventory is event-driven
// (reserve/release/confirm happen via Kafka), but adding stock is a human/admin
// action, so it needs an API.
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    // GET current stock for a product
    @GetMapping("/{productId}")
    public ResponseEntity<Inventory> getStock(@PathVariable Long productId) {
        return ResponseEntity.ok(inventoryService.getStock(productId));
    }

    // Add stock. Body: {"quantity": 100}
    @PostMapping("/{productId}/restock")
    public ResponseEntity<Inventory> restock(@PathVariable Long productId,
                                             @RequestBody Map<String, Integer> body) {
        return ResponseEntity.ok(inventoryService.restock(productId, body.get("quantity")));
    }
}
