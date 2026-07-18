package com.ecommerce.user;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import java.security.MessageDigest;
@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> tempDebugRedisPasswordHash() {
        return event -> {
            ConfigurableApplicationContext ctx = event.getApplicationContext();
            try {
                String resolved = ctx.getEnvironment().getProperty("spring.data.redis.password");
                String envVar = System.getenv("SPRING_DATA_REDIS_PASSWORD");
                System.out.println("TEMPDEBUG spring.data.redis.password resolved=[" + hash(resolved) + "] length=" + (resolved == null ? "null" : resolved.length()));
                System.out.println("TEMPDEBUG System.getenv(SPRING_DATA_REDIS_PASSWORD)=[" + hash(envVar) + "] length=" + (envVar == null ? "null" : envVar.length()));
            } catch (Exception e) {
                System.out.println("TEMPDEBUG error: " + e);
            }
        };
    }

    private static String hash(String s) throws Exception {
        if (s == null) return "NULL";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
