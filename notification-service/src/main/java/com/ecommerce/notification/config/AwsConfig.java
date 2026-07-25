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

    @Value("${aws.access-key-id:test}")
    private String accessKeyId;

    @Value("${aws.secret-access-key:test}")
    private String secretAccessKey;

    @Bean
    public SesClient sesClient() {
        return SesClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpointUrl))  // Floci locally, real AWS in prod
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                    // defaults to "test"/"test" — Floci accepts any creds; real AWS
                    // deploys must set AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY
                ))
                .build();
    }
}
