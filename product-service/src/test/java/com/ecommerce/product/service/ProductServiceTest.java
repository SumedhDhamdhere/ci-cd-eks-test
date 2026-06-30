package com.ecommerce.product.service;

import com.ecommerce.product.kafka.ProductEventPublisher;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;
    @Mock private ProductEventPublisher eventPublisher;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, redisTemplate, eventPublisher);
    }

    @Test
    void getProduct_returnsCachedValueWithoutHittingDb() {
        Product cached = Product.builder().id(1L).name("Cached Widget").build();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("product:1")).thenReturn(cached);

        Product result = productService.getProduct(1L);

        assertThat(result).isEqualTo(cached);
        verify(productRepository, never()).findById(any());
    }

    @Test
    void getProduct_fetchesFromDbAndCachesOnMiss() {
        Product product = Product.builder().id(2L).name("DB Widget").build();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("product:2")).thenReturn(null);
        when(productRepository.findById(2L)).thenReturn(Optional.of(product));

        Product result = productService.getProduct(2L);

        assertThat(result).isEqualTo(product);
        verify(valueOperations).set(eq("product:2"), eq(product), eq(15L), any());
    }

    @Test
    void getProduct_throwsWhenNotFound() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("product:99")).thenReturn(null);
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProduct(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void createProduct_savesInvalidatesCacheAndPublishesEvent() {
        Product input = Product.builder().name("New Widget").price(BigDecimal.TEN).build();
        Product saved = Product.builder().id(5L).name("New Widget").price(BigDecimal.TEN).build();
        when(productRepository.save(input)).thenReturn(saved);

        Product result = productService.createProduct(input);

        assertThat(result.getId()).isEqualTo(5L);
        verify(redisTemplate).delete("product:5");
        verify(eventPublisher).publishProductCreated(saved);
    }

    @Test
    void updateProduct_updatesFieldsAndInvalidatesCache() {
        Product existing = Product.builder().id(3L).name("Old").price(BigDecimal.ONE).build();
        Product updates = Product.builder().name("New Name").price(BigDecimal.TEN)
                .description("desc").category("cat").build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("product:3")).thenReturn(existing);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product result = productService.updateProduct(3L, updates);

        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getPrice()).isEqualTo(BigDecimal.TEN);
        verify(redisTemplate).delete("product:3");
    }
}
