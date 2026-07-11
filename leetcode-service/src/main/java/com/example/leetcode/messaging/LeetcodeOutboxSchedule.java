package com.example.leetcode.messaging;

import com.example.platform.messaging.support.OutboxRelay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "leetcode.messaging.relay-enabled", havingValue = "true",
        matchIfMissing = true)
public class LeetcodeOutboxSchedule {
    private final OutboxRelay relay;

    public LeetcodeOutboxSchedule(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${leetcode.outbox.delay-ms:500}")
    public void publishConfirmedBatch() {
        relay.drainOnce();
    }
}
