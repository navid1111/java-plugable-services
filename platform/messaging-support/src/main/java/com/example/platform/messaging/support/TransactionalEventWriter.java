package com.example.platform.messaging.support;

import com.example.platform.messaging.EventEnvelope;
import tools.jackson.databind.ObjectMapper;

/** Writes a fully serialized domain event to the local outbox transaction. */
public class TransactionalEventWriter {
    private final OutboxMessageRepository outbox;
    private final SafeEventSerializer serializer;
    public TransactionalEventWriter(OutboxMessageRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox; this.serializer = new SafeEventSerializer(mapper);
    }
    public void write(EventEnvelope<?> event) {
        outbox.save(new OutboxMessage(event.eventId(), event.aggregateType(), event.aggregateId(),
                event.eventType(), event.eventVersion(), serializer.serialize(event), event.occurredAt()));
    }
}
