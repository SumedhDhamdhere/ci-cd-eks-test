package com.ecommerce.payment.service;

import com.ecommerce.payment.kafka.PaymentEventPublisher;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentEventPublisher eventPublisher;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, eventPublisher);
    }

    @Test
    void processPayment_succeedsAndPublishesProcessedEvent() {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = paymentService.processPayment(1L, 2L, BigDecimal.valueOf(99.99));

        assertThat(result.getStatus()).isEqualTo(Payment.PaymentStatus.SUCCESS);
        assertThat(result.getTransactionId()).isNotBlank();
        verify(eventPublisher).publishPaymentProcessed(eq(1L), eq(2L), eq(BigDecimal.valueOf(99.99)),
                any(), eq(true));
    }

    @Test
    void processPayment_marksFailedAndPublishesFailureWhenSaveThrows() {
        Payment initial = Payment.builder().orderId(1L).userId(2L).amount(BigDecimal.TEN).build();
        // 1st save (initial persist) succeeds, 2nd save (after marking SUCCESS) throws,
        // 3rd save (in the catch block, marking FAILED) succeeds again.
        when(paymentRepository.save(any(Payment.class)))
                .thenReturn(initial)
                .thenThrow(new RuntimeException("db unavailable"))
                .thenReturn(initial);

        Payment result = paymentService.processPayment(1L, 2L, BigDecimal.TEN);

        assertThat(result.getStatus()).isEqualTo(Payment.PaymentStatus.FAILED);
        verify(eventPublisher).publishPaymentProcessed(eq(1L), eq(2L), eq(BigDecimal.TEN), eq(null), eq(false));
    }
}
