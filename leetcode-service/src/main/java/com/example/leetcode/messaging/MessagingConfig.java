package com.example.leetcode.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MessagingConfig {
    public static final String EXCHANGE = "platform.events.v1";
    public static final String DLX = "platform.events.dlx";
    public static final String JUDGE_QUEUE = "leetcode.judge.requested.v1";
    public static final String RESULT_QUEUE = "leetcode.judge.completed.v1";
    public static final String JUDGE_DLQ = "leetcode.judge.requested.v1.dlq";
    public static final String RESULT_DLQ = "leetcode.judge.completed.v1.dlq";
    public static final String JUDGE_KEY = "leetcode.submission.judge.requested.v1";
    public static final String RESULT_KEY = "leetcode.submission.judge.completed.v1";

    @Bean TopicExchange platformExchange() { return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build(); }
    @Bean TopicExchange deadLetterExchange() { return ExchangeBuilder.topicExchange(DLX).durable(true).build(); }

    // Work queues dead-letter poison/retry-exhausted messages to the DLX keyed by their own name.
    @Bean Queue judgeQueue() {
        return QueueBuilder.durable(JUDGE_QUEUE).quorum()
                .deadLetterExchange(DLX).deadLetterRoutingKey(JUDGE_QUEUE).build();
    }
    @Bean Queue resultQueue() {
        return QueueBuilder.durable(RESULT_QUEUE).quorum()
                .deadLetterExchange(DLX).deadLetterRoutingKey(RESULT_QUEUE).build();
    }
    @Bean Queue judgeDlq() { return QueueBuilder.durable(JUDGE_DLQ).quorum().build(); }
    @Bean Queue resultDlq() { return QueueBuilder.durable(RESULT_DLQ).quorum().build(); }

    @Bean Binding judgeBinding(TopicExchange platformExchange, Queue judgeQueue) {
        return BindingBuilder.bind(judgeQueue).to(platformExchange).with(JUDGE_KEY);
    }
    @Bean Binding resultBinding(TopicExchange platformExchange, Queue resultQueue) {
        return BindingBuilder.bind(resultQueue).to(platformExchange).with(RESULT_KEY);
    }
    @Bean Binding judgeDlqBinding(TopicExchange deadLetterExchange, Queue judgeDlq) {
        return BindingBuilder.bind(judgeDlq).to(deadLetterExchange).with(JUDGE_QUEUE);
    }
    @Bean Binding resultDlqBinding(TopicExchange deadLetterExchange, Queue resultDlq) {
        return BindingBuilder.bind(resultDlq).to(deadLetterExchange).with(RESULT_QUEUE);
    }
}
