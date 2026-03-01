package com.p2p.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    // Transactions
    @Value("${app.kafka.topics.transfer-completed}") private String transferCompleted;
    @Value("${app.kafka.topics.transfer-reversed}")  private String transferReversed;
    @Value("${app.kafka.topics.deposit-confirmed}")  private String depositConfirmed;

    // Security
    @Value("${app.kafka.topics.login-new-ip}")       private String loginNewIp;
    @Value("${app.kafka.topics.password-changed}")   private String passwordChanged;
    @Value("${app.kafka.topics.large-withdrawal}")   private String largeWithdrawal;

    // Compliance
    @Value("${app.kafka.topics.monthly-statement}")  private String monthlyStatement;
    @Value("${app.kafka.topics.tos-update}")         private String tosUpdate;

    @Bean public NewTopic transferCompletedTopic() { return topic(transferCompleted, "transferCompleted"); }
    @Bean public NewTopic transferReversedTopic()  { return topic(transferReversed, "transferReversed"); }
    @Bean public NewTopic depositConfirmedTopic()  { return topic(depositConfirmed, "depositConfirmed" ); }
    @Bean public NewTopic loginNewIpTopic()        { return topic(loginNewIp, "loginNewIp"); }
    @Bean public NewTopic passwordChangedTopic()   { return topic(passwordChanged, "passwordChanged"); }
    @Bean public NewTopic largeWithdrawalTopic()   { return topic(largeWithdrawal, "largeWithdrawal"); }
    @Bean public NewTopic monthlyStatementTopic()  { return topic(monthlyStatement, "monthlyStatement"); }
    @Bean public NewTopic tosUpdateTopic()         { return topic(tosUpdate, "tosUpdate"); }

    private NewTopic topic(String name, String type) {
        return TopicBuilder.name(java.util.Objects.requireNonNull(name, type + " cannot be null")).partitions(3).replicas(1).build();
    }
}
