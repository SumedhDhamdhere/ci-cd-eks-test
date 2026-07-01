package com.ecommerce.inventory.repository;
import com.ecommerce.inventory.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    Optional<Inventory> findByProductId(Long productId);
    @Query("SELECT i FROM Inventory i WHERE i.productId = :productId AND (i.quantity - i.reserved) >= :required")
    Optional<Inventory> findAvailable(Long productId, Integer required);

    // Atomic add-stock upsert. Single statement, so it's race-proof against the
    // product.created consumer inserting the same (unique) product_id row —
    // if the row exists we add to it, otherwise we create it. Avoids the
    // read-then-insert duplicate-key 500 that showed up under concurrency.
    @Modifying
    @Query(value = "INSERT INTO inventory (product_id, quantity, reserved, version) " +
                   "VALUES (:productId, :qty, 0, 0) " +
                   "ON CONFLICT (product_id) DO UPDATE SET quantity = inventory.quantity + :qty",
           nativeQuery = true)
    void upsertStock(@Param("productId") Long productId, @Param("qty") Integer qty);
}
