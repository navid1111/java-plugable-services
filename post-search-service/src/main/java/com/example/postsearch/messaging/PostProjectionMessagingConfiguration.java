package com.example.postsearch.messaging;

import java.time.Duration;
import java.util.List;

import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.support.InboxIdempotency;
import com.example.platform.messaging.support.InboxMessageRepository;
import com.example.platform.messaging.support.MessagingTopology;

@Configuration
public class PostProjectionMessagingConfiguration {
    public static final String CONSUMER = "post-search.post-projection.v1";

    @Bean
    Declarables platformExchanges() {
        return MessagingTopology.exchanges();
    }

    @Bean
    Declarables postProjectionQueues() {
        return MessagingTopology.forConsumer(new MessagingTopology.ConsumerSpec(CONSUMER,
                List.of(EventTypes.POST_CREATED_V1, EventTypes.POST_UPDATED_V1,
                        EventTypes.POST_DELETED_V1), Duration.ofSeconds(10), 10_000));
    }

    @Bean
    InboxIdempotency inboxIdempotency(InboxMessageRepository repository,
            TransactionOperations transactions) {
        return new InboxIdempotency(repository, transactions);
    }
}
