package com.ecommerce.notification.config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import java.net.URI;

@Configuration
public class AwsConfig {

    @Value("${aws.endpoint-url:http://localhost:4566}")
    private String endpointUrl;

    @Value("${aws.region:ap-south-1}")
    private String region;

    @Bean
    public SesClient sesClient() {
        return SesClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpointUrl))  // Floci locally
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")  // Floci accepts any creds
                ))
                .build();
    }
}
