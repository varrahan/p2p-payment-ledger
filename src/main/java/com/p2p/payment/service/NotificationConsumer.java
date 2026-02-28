package com.p2p.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Notification consumer — listens to transfer events and sends async notifications.
 * Decoupled from the core transfer transaction; failure here does not affect transfers.
 *
 * NOTE: In production this would be a separate service/module.
 * Consumers MUST be idempotent — the outbox relay may deliver the same event twice
 * on restart. Use the Kafka message key (outbox event ID) for deduplication.
 */
@Service
@Slf4j
public class NotificationConsumer {

    @KafkaListener(
            topics = "${app.kafka.topics.transfer-completed}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onTransferCompleted(String payload) {
        // TODO: Replace with actual notification dispatch (push, email, SMS)
        log.info("[NOTIFICATION] Transfer completed event received: {}", payload);
    }

    @KafkaListener(
            topics = "${app.kafka.topics.transfer-reversed}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onTransferReversed(String payload) {
        log.info("[NOTIFICATION] Transfer reversed event received: {}", payload);
    }
}
