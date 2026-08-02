package com.ecommerce.inventory.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Reservation outcomes.
 *
 * A rejection is not an error — refusing to sell the eleventh unit of ten is the
 * system working. But the RATE of rejections is the only early warning that
 * stock is running out under load, and comparing accepted against rejected is
 * how an oversell would show up: accepted should never exceed what existed.
 */
@Component
@RequiredArgsConstructor
public class InventoryMetrics {

    private final MeterRegistry registry;

    private Counter accepted;
    private Counter rejected;

    @PostConstruct
    void register() {
        accepted = Counter.builder("inventory_reserve_total").tag("result", "accepted")
                .description("Stock reservation attempts").register(registry);
        rejected = Counter.builder("inventory_reserve_total").tag("result", "rejected")
                .description("Stock reservation attempts").register(registry);
    }

    public void accepted() { accepted.increment(); }
    public void rejected() { rejected.increment(); }
}
