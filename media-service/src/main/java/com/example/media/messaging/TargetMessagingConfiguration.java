package com.example.media.messaging;

import java.time.Duration;
import java.util.List;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;
import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.support.*;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class TargetMessagingConfiguration {
    static final String CONSUMER = "media-service.post-target.v1";
    @Bean TargetProjectionStore targetProjectionStore(TargetProjectionRepository repository) { return new TargetProjectionStore(repository); }
    @Bean InboxIdempotency inboxIdempotency(InboxMessageRepository repository, TransactionOperations tx) { return new InboxIdempotency(repository, tx); }
    @Bean PostTargetEventProcessor targetProcessor(ObjectMapper mapper, InboxIdempotency inbox, TargetProjectionStore store) {
        return new PostTargetEventProcessor(CONSUMER, mapper, inbox, store);
    }
    @Bean Declarables targetExchanges() { return MessagingTopology.exchanges(); }
    @Bean Declarables targetQueues() { return MessagingTopology.forConsumer(new MessagingTopology.ConsumerSpec(CONSUMER,
            List.of(EventTypes.POST_CREATED_V1, EventTypes.POST_UPDATED_V1, EventTypes.POST_DELETED_V1), Duration.ofSeconds(10), 10000)); }
    @Bean TargetListener targetListener(PostTargetEventProcessor processor) { return new TargetListener(processor); }
    public static class TargetListener {
        private final PostTargetEventProcessor processor;
        TargetListener(PostTargetEventProcessor processor) { this.processor = processor; }
        @RabbitListener(queues = CONSUMER) public void receive(String json) { processor.process(json); }
    }
}
