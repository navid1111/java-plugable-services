package com.example.platform.messaging.support;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tuning for the outbox relay: batch size and the exponential-backoff bounds. */
@ConfigurationProperties(prefix = "platform.messaging.outbox")
public class OutboxRelayProperties {

    /** Max rows claimed per drain pass. */
    private int batchSize = 100;
    /** First retry delay; each further attempt doubles this up to {@link #maxBackoff}. */
    private Duration baseBackoff = Duration.ofSeconds(1);
    /** Ceiling for the backoff delay. */
    private Duration maxBackoff = Duration.ofMinutes(5);

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getBaseBackoff() { return baseBackoff; }
    public void setBaseBackoff(Duration baseBackoff) { this.baseBackoff = baseBackoff; }
    public Duration getMaxBackoff() { return maxBackoff; }
    public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }
}
