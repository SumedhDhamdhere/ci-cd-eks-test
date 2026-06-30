package com.ecommerce.product.service;
import com.ecommerce.product.kafka.ProductEventPublisher;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service @RequiredArgsConstructor @Slf4j
public class ProductService {
    private final ProductRepository productRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ProductEventPublisher eventPublisher;
    private static final String CACHE_PREFIX = "product:";
    private static final long CACHE_TTL = 15; // 15 minutes

    public Product getProduct(Long id) {
        String cacheKey = CACHE_PREFIX + id;

        // Check Redis cache first
        Product cached = (Product) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Cache HIT for productId={}", id);
            return cached;
        }

        // Cache miss → fetch from DB
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));

        // Store in Redis with TTL
        redisTemplate.opsForValue().set(cacheKey, product, CACHE_TTL, TimeUnit.MINUTES);
        log.debug("Cache MISS for productId={}, stored in cache", id);
        return product;
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public List<Product> getByCategory(String category) {
        return productRepository.findByCategoryAndActiveTrue(category);
    }

    public List<Product> search(String name) {
        return productRepository.findByNameContainingIgnoreCaseAndActiveTrue(name);
    }

    public Product createProduct(Product product) {
        Product saved = productRepository.save(product);
        // Invalidate cache if exists
        redisTemplate.delete(CACHE_PREFIX + saved.getId());
        eventPublisher.publishProductCreated(saved);
        return saved;
    }

    public Product updateProduct(Long id, Product updated) {
        Product existing = getProduct(id);
        existing.setName(updated.getName());
        existing.setPrice(updated.getPrice());
        existing.setDescription(updated.getDescription());
        existing.setCategory(updated.getCategory());
        Product saved = productRepository.save(existing);
        // Invalidate cache
        redisTemplate.delete(CACHE_PREFIX + id);
        return saved;
    }
}
