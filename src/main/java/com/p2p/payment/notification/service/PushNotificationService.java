package com.p2p.payment.notification.service;

import com.google.firebase.messaging.*;
import com.p2p.payment.notification.dto.NotificationEvent;
import com.p2p.payment.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Sends push notifications via Firebase Cloud Messaging (FCM).
 *
 * Used for:
 *   - Security events      → immediate alert requiring user action
 *   - Transactional events → fast UX confirmation (transfer received, deposit)
 *
 * Tokens that FCM reports as invalid or unregistered are automatically
 * deactivated to keep the token table clean.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private final FirebaseMessaging firebaseMessaging;
    private final DeviceTokenRepository deviceTokenRepository;

    public void send(NotificationEvent event) {
        List<String> tokens = deviceTokenRepository
                .findActiveTokensByUserId(event.getUserId())
                .stream()
                .map(dt -> dt.getToken())
                .toList();

        if (tokens.isEmpty()) {
            log.debug("No active device tokens for userId={} — skipping push", event.getUserId());
            return;
        }

        PushTemplate template = buildTemplate(event);

        // Send to all registered devices for this user in one multicast call
        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder()
                        .setTitle(template.title())
                        .setBody(template.body())
                        .build())
                .putAllData(event.getData() != null ? event.getData() : Map.of())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .putHeader("apns-priority", "10")
                        .build())
                .build();

        try {
            BatchResponse response = firebaseMessaging.sendEachForMulticast(message);
            handleBatchResponse(response, tokens);
            log.info("Push sent userId={} eventType={} success={} failure={}", event.getUserId(), event.getEventType(), response.getSuccessCount(), response.getFailureCount());
        } catch (FirebaseMessagingException e) {
            log.error("Failed to send push for userId={} eventType={}", event.getUserId(), event.getEventType(), e);
            throw new RuntimeException("Push delivery failed", e);
        }
    }

    /**
     * Deactivates tokens that FCM reports as invalid.
     * Keeps the device_tokens table clean without manual maintenance.
     */
    private void handleBatchResponse(BatchResponse response, List<String> tokens) {
        List<SendResponse> responses = response.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            SendResponse sendResponse = responses.get(i);
            if (!sendResponse.isSuccessful()) {
                MessagingErrorCode errorCode = sendResponse.getException().getMessagingErrorCode();
                if (errorCode == MessagingErrorCode.UNREGISTERED || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                    String invalidToken = tokens.get(i);
                    deviceTokenRepository.deactivateToken(invalidToken);
                    log.info("Deactivated invalid FCM token ending in ...{}",
                            invalidToken.substring(Math.max(0, invalidToken.length() - 8)));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Push templates — short title + one-line body for notification tray
    // ------------------------------------------------------------------

    private PushTemplate buildTemplate(NotificationEvent event) {
        Map<String, String> data = event.getData();

        return switch (event.getEventType()) {

            case LOGIN_NEW_IP -> new PushTemplate(
                    "New login detected",
                    "A login was made from " + data.getOrDefault("location", "a new location") +
                    ". Tap to review."
            );

            case PASSWORD_CHANGED -> new PushTemplate(
                    "Password changed",
                    "Your account password was just changed. Not you? Contact support."
            );

            case LARGE_WITHDRAWAL -> new PushTemplate(
                    "Large withdrawal alert",
                    data.getOrDefault("amount", "") + " " +
                    data.getOrDefault("currency", "") + " was withdrawn from your account."
            );

            case TRANSFER_RECEIVED -> new PushTemplate(
                    "Payment received",
                    "You received " + data.getOrDefault("amount", "") + " " +
                    data.getOrDefault("currency", "") + " from " +
                    data.getOrDefault("senderName", "another user") + "."
            );

            case DEPOSIT_CONFIRMED -> new PushTemplate(
                    "Deposit confirmed",
                    data.getOrDefault("amount", "") + " " +
                    data.getOrDefault("currency", "") + " has been added to your wallet."
            );

            // Compliance events are email-only — should not reach here
            default -> throw new IllegalArgumentException(
                    "Event type " + event.getEventType() + " is not a push event");
        };
    }

    private record PushTemplate(String title, String body) {}
}