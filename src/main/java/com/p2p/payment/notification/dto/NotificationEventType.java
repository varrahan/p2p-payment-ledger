package com.p2p.payment.notification.dto;

/**
 * All notification event types in the system.
 * Maps to Kafka topic names in KafkaConfig.
 *
 * Channel strategy per type:
 *
 * SECURITY events (login-new-ip, password-change, large-withdrawal)
 *   → PUSH + EMAIL: immediate action required + permanent security trail
 *
 * TRANSACTIONAL events (transfer-received, deposit-confirmed)
 *   → PUSH only: fast UX confirmation, no legal requirement
 *
 * COMPLIANCE events (monthly-statement, tos-update)
 *   → EMAIL only: "durable medium" requirement in most jurisdictions
 */
public enum NotificationEventType {

    // Security
    LOGIN_NEW_IP,
    PASSWORD_CHANGED,
    LARGE_WITHDRAWAL,

    // Transactional
    TRANSFER_RECEIVED,
    DEPOSIT_CONFIRMED,

    // Compliance
    MONTHLY_STATEMENT,
    TOS_UPDATE
}