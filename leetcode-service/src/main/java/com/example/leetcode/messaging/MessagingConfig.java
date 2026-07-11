package com.example.leetcode.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MessagingConfig {
    public static final String EXCHANGE = "platform.events.v1";
    public static final String JUDGE_QUEUE = "leetcode.judge.requested.v1";
    public static final String RESULT_QUEUE = "leetcode.judge.completed.v1";
    public static final String JUDGE_KEY = "leetcode.submission.judge.requested.v1";
    public static final String RESULT_KEY = "leetcode.submission.judge.completed.v1";

    @Bean TopicExchange platformExchange() { return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build(); }
    @Bean Queue judgeQueue() { return QueueBuilder.durable(JUDGE_QUEUE).quorum().build(); }
    @Bean Queue resultQueue() { return QueueBuilder.durable(RESULT_QUEUE).quorum().build(); }
    @Bean Binding judgeBinding(TopicExchange platformExchange, Queue judgeQueue) {
        return BindingBuilder.bind(judgeQueue).to(platformExchange).with(JUDGE_KEY);
    }
    @Bean Binding resultBinding(TopicExchange platformExchange, Queue resultQueue) {
        return BindingBuilder.bind(resultQueue).to(platformExchange).with(RESULT_KEY);
    }
}
