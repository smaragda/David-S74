package cz.s74.vouchers.email;

import cz.s74.vouchers.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_delivery")
public class EmailDelivery extends BaseEntity {

    public enum Kind {
        ISSUED,
        RESEND
    }

    public enum Status {
        PENDING,
        SENDING,
        SENT,
        FAILED
    }

    @Column(name = "voucher_id", nullable = false)
    private UUID voucherId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected EmailDelivery() {}

    public EmailDelivery(UUID id, UUID voucherId, Kind kind, String recipient, UUID requestedBy, Instant now) {
        super(id);
        this.voucherId = voucherId;
        this.kind = kind;
        this.recipient = recipient;
        this.requestedBy = requestedBy;
        this.status = Status.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public void markSending(Instant now) {
        this.status = Status.SENDING;
        this.nextAttemptAt = now;
    }

    public void markSent(String messageId, Instant now) {
        this.status = Status.SENT;
        this.attempts++;
        this.providerMessageId = messageId;
        this.sentAt = now;
        this.lastError = null;
    }

    public void markRetry(String error, Instant nextAttempt) {
        this.status = Status.PENDING;
        this.attempts++;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttempt;
    }

    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.attempts++;
        this.lastError = truncate(error);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    public UUID getVoucherId() {
        return voucherId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getRecipient() {
        return recipient;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
