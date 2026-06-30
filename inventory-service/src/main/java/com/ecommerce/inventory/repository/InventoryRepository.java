package com.ecommerce.inventory.repository;
import com.ecommerce.inventory.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    Optional<Inventory> findByProductId(Long productId);
    @Query("SELECT i FROM Inventory i WHERE i.productId = :productId AND (i.quantity - i.reserved) >= :required")
    Optional<Inventory> findAvailable(Long productId, Integer required);
}
