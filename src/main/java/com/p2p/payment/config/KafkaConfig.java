package com.p2p.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${app.kafka.topics.transfer-completed}")
    private String transferCompletedTopic;

    @Value("${app.kafka.topics.transfer-reversed}")
    private String transferReversedTopic;

    @Bean
    public NewTopic transferCompletedTopic() {
        return TopicBuilder.name(java.util.Objects.requireNonNull(transferCompletedTopic, "Complete topic name cannot be null"))
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transferReversedTopic() {
        return TopicBuilder.name(java.util.Objects.requireNonNull(transferReversedTopic, "Reversed topic name cannot be null"))
                .partitions(3)
                .replicas(1)
                .build();
    }
}
