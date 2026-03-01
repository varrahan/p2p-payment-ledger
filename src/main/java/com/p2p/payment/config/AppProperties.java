package com.p2p.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
    Jwt jwt,
    Idempotency idempotency,
    RateLimit rateLimit,
    Kafka kafka,
    Notifications notifications,
    int largeWithdrawalThreshold
) {
    public record Jwt(String secret, long expirationMs) {}
    
    public record Idempotency(int ttlHours) {}
    
    public record RateLimit(int transfersPerMinute) {}
    
    public record Kafka(Map<String, String> topics) {}
    
    public record Notifications(Sendgrid sendgrid, Firebase firebase, int largeWithdrawalThreshold) {
        public record Sendgrid(String apiKey, String fromEmail, String fromName) {}
        public record Firebase(String credentialsPath) {}
    }
}