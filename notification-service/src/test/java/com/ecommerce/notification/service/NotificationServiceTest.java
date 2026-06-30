package com.ecommerce.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private SesClient sesClient;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(sesClient);
    }

    @Test
    void sendOrderConfirmation_invokesSesClientWithExpectedSubject() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("msg-1").build());

        notificationService.sendOrderConfirmation(42L, 7L);

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().message().subject().data())
                .isEqualTo("Order Confirmed #42");
    }

    @Test
    void sendEmail_swallowsExceptionFromSesClient() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(new RuntimeException("SES unavailable"));

        assertThatCode(() -> notificationService.sendPaymentSuccess(1L)).doesNotThrowAnyException();
    }

    @Test
    void sendOrderCancelled_invokesSesClient() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("msg-2").build());

        notificationService.sendOrderCancelled(5L);

        verify(sesClient).sendEmail(any(SendEmailRequest.class));
    }
}
