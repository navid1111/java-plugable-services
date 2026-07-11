package com.example.leetcode.messaging;

import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.support.MessagingTopology;
import java.time.Duration;
import java.util.List;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MessagingConfig {
    public static final String EXCHANGE = MessagingTopology.EVENTS_EXCHANGE;
    public static final String DLX = MessagingTopology.DEAD_LETTER_EXCHANGE;
    public static final String JUDGE_QUEUE = "leetcode.judge.requested.v1";
    public static final String RESULT_QUEUE = "leetcode.judge.completed.v1";
    public static final String JUDGE_DLQ = "leetcode.judge.requested.v1.dlq";
    public static final String RESULT_DLQ = "leetcode.judge.completed.v1.dlq";
    public static final String JUDGE_KEY = EventTypes.LEETCODE_JUDGE_REQUESTED_V1;
    public static final String RESULT_KEY = EventTypes.LEETCODE_JUDGE_COMPLETED_V1;

    @Bean Declarables platformExchanges() { return MessagingTopology.exchanges(); }
    @Bean Declarables judgeTopology() {
        return MessagingTopology.forConsumer(new MessagingTopology.ConsumerSpec(
                JUDGE_QUEUE, List.of(JUDGE_KEY), Duration.ofSeconds(5), 10_000));
    }
    @Bean Declarables resultTopology() {
        return MessagingTopology.forConsumer(new MessagingTopology.ConsumerSpec(
                RESULT_QUEUE, List.of(RESULT_KEY), Duration.ofSeconds(5), 10_000));
    }
}
