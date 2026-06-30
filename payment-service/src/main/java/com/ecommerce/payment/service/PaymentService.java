package com.ecommerce.payment.service;
import com.ecommerce.payment.kafka.PaymentEventPublisher;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

@Service @RequiredArgsConstructor @Slf4j
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentEventPublisher eventPublisher;

    @Transactional
    public Payment processPayment(Long orderId, Long userId, BigDecimal amount) {
        Payment payment = Payment.builder()
                .orderId(orderId).userId(userId).amount(amount).build();
        paymentRepository.save(payment);

        try {
            // Simulate payment gateway call (Stripe / Razorpay in real impl)
            String txnId = UUID.randomUUID().toString();
            payment.setStatus(Payment.PaymentStatus.SUCCESS);
            payment.setTransactionId(txnId);
            paymentRepository.save(payment);

            // Publish success event → order + notification services consume
            eventPublisher.publishPaymentProcessed(orderId, userId, amount, txnId, true);
            log.info("Payment SUCCESS for orderId={}, txnId={}", orderId, txnId);
        } catch (Exception e) {
            payment.setStatus(Payment.PaymentStatus.FAILED);
            paymentRepository.save(payment);
            eventPublisher.publishPaymentProcessed(orderId, userId, amount, null, false);
            log.error("Payment FAILED for orderId={}: {}", orderId, e.getMessage());
        }
        return payment;
    }
}
