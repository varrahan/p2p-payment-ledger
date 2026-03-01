package com.p2p.payment.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Universal notification event payload published to Kafka.
 * All notification topics use this same structure — the
 * eventType field drives routing to the correct channel(s).
 *
 * The `data` map carries event-specific fields:
 *   LOGIN_NEW_IP     → ip, location, timestamp
 *   PASSWORD_CHANGED → timestamp
 *   LARGE_WITHDRAWAL → amount, currency, walletId
 *   TRANSFER_RECEIVED → amount, currency, senderName, transferId
 *   DEPOSIT_CONFIRMED → amount, currency, walletId
 *   MONTHLY_STATEMENT → month, year, statementUrl
 *   TOS_UPDATE        → effectiveDate, summaryUrl
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {

    private NotificationEventType eventType;
    private UUID                  userId;
    private String                userEmail;
    private String                userFullName;
    private Map<String, String>   data;

    @Builder.Default
    private OffsetDateTime occurredAt = OffsetDateTime.now();
}