package com.p2p.payment.notification.service;

import com.p2p.payment.notification.dto.NotificationEvent;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

/**
 * Sends transactional emails via SendGrid.
 *
 * Used for:
 *   - Security events  → immediate alert + permanent trail
 *   - Compliance events → "durable medium" legal requirement
 *
 * NOT used for transactional events (transfer received, deposit confirmed)
 * — those go to push only for fast UX confirmation.
 */
@Service
@Slf4j
public class EmailService {

    private final SendGrid sendGrid;
    private final String fromEmail;
    private final String fromName;

    public EmailService(
            @Value("${app.notifications.sendgrid.api-key}") String apiKey,
            @Value("${app.notifications.sendgrid.from-email}") String fromEmail,
            @Value("${app.notifications.sendgrid.from-name}") String fromName) {
        this.sendGrid = new SendGrid(apiKey);
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    public void send(NotificationEvent event) {
        EmailTemplate template = buildTemplate(event);

        Mail mail = new Mail(
                new Email(fromEmail, fromName),
                template.subject(),
                new Email(event.getUserEmail(), event.getUserFullName()),
                new Content("text/html", template.body())
        );

        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            var response = sendGrid.api(request);

            if (response.getStatusCode() >= 400) {
                log.error("SendGrid rejected email for userId={} eventType={} status={}",
                        event.getUserId(), event.getEventType(), response.getStatusCode());
            } else {
                log.info("Email sent userId={} eventType={}", event.getUserId(), event.getEventType());
            }
        } catch (IOException e) {
            // Log and rethrow — caller decides whether to retry
            log.error("Failed to send email for userId={} eventType={}",
                    event.getUserId(), event.getEventType(), e);
            throw new RuntimeException("Email delivery failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Template builder — one branch per notification type.
    // In a larger system these would be SendGrid Dynamic Templates
    // with a template ID per event type, loaded from a CMS or config.
    // ------------------------------------------------------------------

    private EmailTemplate buildTemplate(NotificationEvent event) {
        Map<String, String> data = event.getData();
        String name = event.getUserFullName();

        return switch (event.getEventType()) {

            case LOGIN_NEW_IP -> new EmailTemplate(
                    "Security Alert: New login detected",
                    """
                    <p>Hi %s,</p>
                    <p>We detected a login to your account from a new location.</p>
                    <ul>
                      <li><strong>IP Address:</strong> %s</li>
                      <li><strong>Location:</strong> %s</li>
                      <li><strong>Time:</strong> %s</li>
                    </ul>
                    <p>If this was you, no action is needed.</p>
                    <p><strong>If this wasn't you, change your password immediately and contact support.</strong></p>
                    """.formatted(name,
                            data.getOrDefault("ip", "Unknown"),
                            data.getOrDefault("location", "Unknown"),
                            data.getOrDefault("timestamp", event.getOccurredAt().toString()))
            );

            case PASSWORD_CHANGED -> new EmailTemplate(
                    "Your password was changed",
                    """
                    <p>Hi %s,</p>
                    <p>Your account password was successfully changed on %s.</p>
                    <p>If you did not make this change, please contact support immediately.</p>
                    """.formatted(name,
                            data.getOrDefault("timestamp", event.getOccurredAt().toString()))
            );

            case LARGE_WITHDRAWAL -> new EmailTemplate(
                    "Large withdrawal alert",
                    """
                    <p>Hi %s,</p>
                    <p>A large withdrawal was processed from your account.</p>
                    <ul>
                      <li><strong>Amount:</strong> %s %s</li>
                      <li><strong>Wallet:</strong> %s</li>
                      <li><strong>Time:</strong> %s</li>
                    </ul>
                    <p>If you did not authorise this, contact support immediately.</p>
                    """.formatted(name,
                            data.getOrDefault("amount", ""),
                            data.getOrDefault("currency", ""),
                            data.getOrDefault("walletId", ""),
                            event.getOccurredAt().toString())
            );

            case MONTHLY_STATEMENT -> new EmailTemplate(
                    "Your monthly statement is ready",
                    """
                    <p>Hi %s,</p>
                    <p>Your statement for %s %s is now available.</p>
                    <p><a href="%s">Download your statement</a></p>
                    <p>This statement is your official record for the period and
                    satisfies regulatory durable medium requirements.</p>
                    """.formatted(name,
                            data.getOrDefault("month", ""),
                            data.getOrDefault("year", ""),
                            data.getOrDefault("statementUrl", "#"))
            );

            case TOS_UPDATE -> new EmailTemplate(
                    "Important: Our Terms of Service have been updated",
                    """
                    <p>Hi %s,</p>
                    <p>We have updated our Terms of Service, effective %s.</p>
                    <p><a href="%s">Review the changes</a></p>
                    <p>Continued use of the service after this date constitutes
                    acceptance of the updated terms.</p>
                    """.formatted(name,
                            data.getOrDefault("effectiveDate", ""),
                            data.getOrDefault("summaryUrl", "#"))
            );

            // Transactional events are push-only — should not reach here
            default -> throw new IllegalArgumentException(
                    "Event type " + event.getEventType() + " is not an email event");
        };
    }

    private record EmailTemplate(String subject, String body) {}
}