package com.example.platform.messaging.support;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long published outbox rows and processed inbox markers are kept before a retention
 * job may purge them. Published outbox rows are safe to drop once no replay/audit window
 * needs them; inbox markers must outlive the broker's maximum redelivery window so
 * deduplication stays effective.
 */
@ConfigurationProperties(prefix = "platform.messaging.retention")
public class MessagingRetentionProperties {

    private Duration outbox = Duration.ofDays(7);
    private Duration inbox = Duration.ofDays(14);

    public Duration getOutbox() { return outbox; }
    public void setOutbox(Duration outbox) { this.outbox = outbox; }
    public Duration getInbox() { return inbox; }
    public void setInbox(Duration inbox) { this.inbox = inbox; }
}
