package com.p2p.payment.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2p.payment.domain.entity.OutboxEvent;
import com.p2p.payment.notification.dto.NotificationEvent;
import com.p2p.payment.notification.dto.NotificationEventType;
import com.p2p.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Central publisher for all notification events.
 *
 * Writes to the outbox_events table inside the caller's existing
 * transaction — guaranteeing atomicity with the business operation.
 * The OutboxRelayScheduler picks them up and publishes to Kafka.
 *
 * Each method corresponds to one NotificationEventType and builds
 * the correct data payload for that event.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.notifications.large-withdrawal-threshold:1000}")
    private BigDecimal largeWithdrawalThreshold;

    // ----------------------------------------------------------------
    // Security Events
    // ----------------------------------------------------------------

    public void publishLoginFromNewIp(UUID userId, String email, String fullName,
                                       String ipAddress, String location) {
        publish(NotificationEvent.builder()
                .eventType(NotificationEventType.LOGIN_NEW_IP)
                .userId(userId)
                .userEmail(email)
                .userFullName(fullName)
                .data(Map.of(
                        "ip", ipAddress,
                        "location", location,
                        "timestamp", OffsetDateTime.now().toString()
                ))
                .build());
    }

    public void publishPasswordChanged(UUID userId, String email, String fullName) {
        publish(NotificationEvent.builder()
                .eventType(NotificationEventType.PASSWORD_CHANGED)
                .userId(userId)
                .userEmail(email)
                .userFullName(fullName)
                .data(Map.of("timestamp", OffsetDateTime.now().toString()))
                .build());
    }

    /**
     * Only publishes if amount exceeds the configured threshold.
     * Threshold is configurable via LARGE_WITHDRAWAL_THRESHOLD env var.
     */
    public void publishLargeWithdrawalIfRequired(UUID userId, String email, String fullName,
                                                  BigDecimal amount, String currency,
                                                  UUID walletId) {
        if (amount.compareTo(largeWithdrawalThreshold) >= 0) {
            publish(NotificationEvent.builder()
                    .eventType(NotificationEventType.LARGE_WITHDRAWAL)
                    .userId(userId)
                    .userEmail(email)
                    .userFullName(fullName)
                    .data(Map.of(
                            "amount", amount.toPlainString(),
                            "currency", currency,
                            "walletId", walletId.toString(),
                            "timestamp", OffsetDateTime.now().toString()
                    ))
                    .build());
        }
    }

    // ----------------------------------------------------------------
    // Transactional Events
    // ----------------------------------------------------------------

    public void publishTransferReceived(UUID receiverUserId, String email, String fullName,
                                         BigDecimal amount, String currency,
                                         String senderName, UUID transferId) {
        publish(NotificationEvent.builder()
                .eventType(NotificationEventType.TRANSFER_RECEIVED)
                .userId(receiverUserId)
                .userEmail(email)
                .userFullName(fullName)
                .data(Map.of(
                        "amount", amount.toPlainString(),
                        "currency", currency,
                        "senderName", senderName,
                        "transferId", transferId.toString()
                ))
                .build());
    }

    public void publishDepositConfirmed(UUID userId, String email, String fullName,
                                         BigDecimal amount, String currency, UUID walletId) {
        publish(NotificationEvent.builder()
                .eventType(NotificationEventType.DEPOSIT_CONFIRMED)
                .userId(userId)
                .userEmail(email)
                .userFullName(fullName)
                .data(Map.of(
                        "amount", amount.toPlainString(),
                        "currency", currency,
                        "walletId", walletId.toString()
                ))
                .build());
    }

    // ----------------------------------------------------------------
    // Compliance Events
    // ----------------------------------------------------------------

    public void publishMonthlyStatement(UUID userId, String email, String fullName,
                                         int month, int year, String statementUrl) {
        publish(NotificationEvent.builder()
                .eventType(NotificationEventType.MONTHLY_STATEMENT)
                .userId(userId)
                .userEmail(email)
                .userFullName(fullName)
                .data(Map.of(
                        "month", String.valueOf(month),
                        "year", String.valueOf(year),
                        "statementUrl", statementUrl
                ))
                .build());
    }

    public void publishTosUpdate(UUID userId, String email, String fullName,
                                  String effectiveDate, String summaryUrl) {
        publish(NotificationEvent.builder()
                .eventType(NotificationEventType.TOS_UPDATE)
                .userId(userId)
                .userEmail(email)
                .userFullName(fullName)
                .data(Map.of(
                        "effectiveDate", effectiveDate,
                        "summaryUrl", summaryUrl
                ))
                .build());
    }

    // ----------------------------------------------------------------
    // Internal
    // ----------------------------------------------------------------

    private void publish(NotificationEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            var outboxEvent = OutboxEvent.builder()
                    .eventType(event.getEventType().name())
                    .payload(payload)
                    .build();
            outboxEventRepository.save(outboxEvent);
            log.debug("Queued notification event type={} userId={}",
                    event.getEventType(), event.getUserId());
        } catch (JsonProcessingException e) {
            // Should never happen with well-formed DTOs — log and continue
            log.error("Failed to serialise notification event type={}", event.getEventType(), e);
        }
    }
}