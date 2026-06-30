package com.ecommerce.notification.service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

@Service @Slf4j
public class NotificationService {

    // SesClient points to Floci locally → real SES in production
    // Same code, endpoint changes via AWS_ENDPOINT_URL env variable
    private final SesClient sesClient;

    public NotificationService(SesClient sesClient) {
        this.sesClient = sesClient;
    }

    public void sendOrderConfirmation(Long orderId, Long userId) {
        log.info("Sending order confirmation email for orderId={}, userId={}", orderId, userId);
        // In real project: fetch user email from user-service, then send
        sendEmail(
            "no-reply@ecommerce.com",
            "user@example.com",  // fetch from user-service in real impl
            "Order Confirmed #" + orderId,
            "Your order #" + orderId + " has been confirmed!"
        );
    }

    public void sendPaymentSuccess(Long orderId) {
        log.info("Sending payment success email for orderId={}", orderId);
        sendEmail(
            "no-reply@ecommerce.com",
            "user@example.com",
            "Payment Successful #" + orderId,
            "Payment for order #" + orderId + " was successful!"
        );
    }

    public void sendOrderCancelled(Long orderId) {
        log.info("Sending cancellation email for orderId={}", orderId);
        sendEmail(
            "no-reply@ecommerce.com",
            "user@example.com",
            "Order Cancelled #" + orderId,
            "Your order #" + orderId + " has been cancelled."
        );
    }

    private void sendEmail(String from, String to, String subject, String body) {
        try {
            sesClient.sendEmail(SendEmailRequest.builder()
                .source(from)
                .destination(Destination.builder().toAddresses(to).build())
                .message(Message.builder()
                    .subject(Content.builder().data(subject).build())
                    .body(Body.builder()
                        .text(Content.builder().data(body).build())
                        .build())
                    .build())
                .build());
            log.info("Email sent to {}: {}", to, subject);
        } catch (Exception e) {
            log.error("Failed to send email: {}", e.getMessage());
        }
    }
}
