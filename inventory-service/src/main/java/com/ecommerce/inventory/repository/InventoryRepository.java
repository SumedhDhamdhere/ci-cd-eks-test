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

    // Atomic reservation. The availability check and the increment happen in ONE
    // statement, so the database serialises concurrent callers — there is no
    // window between reading and writing for another thread to slip into.
    //
    // Replaces a read-modify-write guarded by a Redis lock that was released in a
    // finally block INSIDE @Transactional — i.e. before the commit. A load test
    // (40 concurrent orders against 10 units) sold 18 units through that path.
    //
    // Returns rows affected: 1 = reserved, 0 = insufficient stock.
    @Modifying
    @Query(value = "UPDATE inventory SET reserved = reserved + :qty, version = version + 1 " +
                   "WHERE product_id = :productId AND quantity - reserved >= :qty",
           nativeQuery = true)
    int reserveAtomic(@Param("productId") Long productId, @Param("qty") Integer qty);

    // Confirms a reservation into an actual sale: drop it from reserved AND
    // deduct it from quantity, in one statement.
    @Modifying
    @Query(value = "UPDATE inventory SET quantity = quantity - :qty, " +
                   "reserved = GREATEST(0, reserved - :qty), version = version + 1 " +
                   "WHERE product_id = :productId",
           nativeQuery = true)
    int confirmAtomic(@Param("productId") Long productId, @Param("qty") Integer qty);

    // Releases a reservation without selling (payment failed / order cancelled).
    @Modifying
    @Query(value = "UPDATE inventory SET reserved = GREATEST(0, reserved - :qty), " +
                   "version = version + 1 WHERE product_id = :productId",
           nativeQuery = true)
    int releaseAtomic(@Param("productId") Long productId, @Param("qty") Integer qty);
}
