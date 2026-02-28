package com.p2p.payment.scheduler;

import com.p2p.payment.domain.entity.OutboxEvent;
import com.p2p.payment.repository.IdempotencyKeyRepository;
import com.p2p.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void relayPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();

        if (pending.isEmpty()) return;

        log.debug("Relaying {} outbox event(s) to Kafka", pending.size());

        for (OutboxEvent event : pending) {
            try {
                // Use event ID as Kafka message key to enable idempotent consumer dedup
                kafkaTemplate.send(java.util.Objects.requireNonNull(event.getEventType(), "Event type must not be null"), java.util.Objects.requireNonNull(event.getId().toString(), "Event ID must not be null"), event.getPayload());
                outboxEventRepository.markAsPublished(event.getId());
            } catch (Exception e) {
                // Log and continue — event will be retried on next poll cycle
                log.error("Failed to publish outbox event id={} type={}", event.getId(), event.getEventType(), e);
            }
        }
    }

    /**
     * Purge expired idempotency keys daily to prevent unbounded table growth.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredIdempotencyKeys() {
        int deleted = idempotencyKeyRepository.deleteExpiredKeys(OffsetDateTime.now());
        if (deleted > 0) {
            log.info("Purged {} expired idempotency keys", deleted);
        }
    }
}
