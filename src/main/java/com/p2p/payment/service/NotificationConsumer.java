package com.p2p.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2p.payment.notification.dto.NotificationEvent;
import com.p2p.payment.notification.dto.NotificationEventType;
import com.p2p.payment.notification.service.EmailService;
import com.p2p.payment.notification.service.PushNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Routes inbound Kafka notification events to the correct delivery channel(s).
 *
 * Channel strategy:
 *
 *   SECURITY  (LOGIN_NEW_IP, PASSWORD_CHANGED, LARGE_WITHDRAWAL)
 *     -> Push + Email
 *     Reason: immediate action required (push) + permanent security
 *     audit trail (email). Regulatory best practice.
 *
 *   TRANSACTIONAL  (TRANSFER_RECEIVED, DEPOSIT_CONFIRMED)
 *     -> Push only
 *     Reason: fast UX confirmation. No legal requirement for email.
 *
 *   COMPLIANCE  (MONTHLY_STATEMENT, TOS_UPDATE)
 *     -> Email only
 *     Reason: "durable medium" requirement under PSD2 / MiFID II.
 *     Push notifications do not satisfy this requirement.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationConsumer {

    private final EmailService emailService;
    private final PushNotificationService pushNotificationService;
    private final ObjectMapper objectMapper;

    private static final Set<NotificationEventType> PUSH_AND_EMAIL = Set.of(
            NotificationEventType.LOGIN_NEW_IP,
            NotificationEventType.PASSWORD_CHANGED,
            NotificationEventType.LARGE_WITHDRAWAL
    );

    private static final Set<NotificationEventType> PUSH_ONLY = Set.of(
            NotificationEventType.TRANSFER_RECEIVED,
            NotificationEventType.DEPOSIT_CONFIRMED
    );

    private static final Set<NotificationEventType> EMAIL_ONLY = Set.of(
            NotificationEventType.MONTHLY_STATEMENT,
            NotificationEventType.TOS_UPDATE
    );

    @KafkaListener(
            topics = {
                "${app.kafka.topics.transfer-completed}",
                "${app.kafka.topics.transfer-reversed}",
                "${app.kafka.topics.deposit-confirmed}",
                "${app.kafka.topics.login-new-ip}",
                "${app.kafka.topics.password-changed}",
                "${app.kafka.topics.large-withdrawal}",
                "${app.kafka.topics.monthly-statement}",
                "${app.kafka.topics.tos-update}"
            },
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onNotificationEvent(
            String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {

        NotificationEvent event;
        try {
            event = objectMapper.readValue(payload, NotificationEvent.class);
        } catch (Exception e) {
            log.error("Failed to deserialise notification event from topic={}", topic, e);
            return;
        }

        log.info("Received notification event type={} userId={} topic={}",
                event.getEventType(), event.getUserId(), topic);

        route(event);
    }

    private void route(NotificationEvent event) {
        NotificationEventType type = event.getEventType();

        if (PUSH_AND_EMAIL.contains(type)) {
            sendPush(event);
            sendEmail(event);
        } else if (PUSH_ONLY.contains(type)) {
            sendPush(event);
        } else if (EMAIL_ONLY.contains(type)) {
            sendEmail(event);
        } else {
            log.warn("No channel mapping for eventType={} — event dropped", type);
        }
    }

    private void sendPush(NotificationEvent event) {
        try {
            pushNotificationService.send(event);
        } catch (Exception e) {
            log.error("Push delivery failed for userId={} eventType={}",
                    event.getUserId(), event.getEventType(), e);
        }
    }

    private void sendEmail(NotificationEvent event) {
        try {
            emailService.send(event);
        } catch (Exception e) {
            log.error("Email delivery failed for userId={} eventType={}",
                    event.getUserId(), event.getEventType(), e);
        }
    }
}