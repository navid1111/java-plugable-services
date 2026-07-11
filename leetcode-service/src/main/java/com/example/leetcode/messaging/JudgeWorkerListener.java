package com.example.leetcode.messaging;

import com.example.leetcode.service.runner.CodeRunner;
import com.example.leetcode.service.runner.ExecutionResult;
import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.leetcode.JudgeCompleted;
import com.example.platform.messaging.support.InboxIdempotency;
import com.example.platform.messaging.support.InvalidEventSchemaException;
import com.example.platform.messaging.support.OutboxMessage;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.support.SafeEventSerializer;
import com.example.platform.messaging.support.UnsupportedEventVersionException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "leetcode.role", havingValue = "worker")
public class JudgeWorkerListener {
    static final String CONSUMER = MessagingConfig.JUDGE_QUEUE;

    private final List<CodeRunner> runners;
    private final ObjectMapper mapper;
    private final InboxIdempotency inbox;
    private final OutboxMessageRepository outbox;
    private final SafeEventSerializer serializer;
    private final LeetcodeDeliveryFailureHandler failures;

    public JudgeWorkerListener(List<CodeRunner> runners, ObjectMapper mapper,
            InboxIdempotency inbox, OutboxMessageRepository outbox,
            SafeEventSerializer serializer, LeetcodeDeliveryFailureHandler failures) {
        this.runners = runners;
        this.mapper = mapper;
        this.inbox = inbox;
        this.outbox = outbox;
        this.serializer = serializer;
        this.failures = failures;
    }

    @RabbitListener(queues = MessagingConfig.JUDGE_QUEUE)
    public void judge(Message delivery) {
        try {
            JsonNode root = parse(delivery);
            validateEnvelope(root);
            UUID eventId = UUID.fromString(JudgeResultListener.required(root, "eventId").asText());
            UUID correlationId = UUID.fromString(
                    JudgeResultListener.required(root, "correlationId").asText());
            long aggregateVersion = JudgeResultListener.required(root, "aggregateVersion").asLong();
            JsonNode payload = JudgeResultListener.required(root, "payload");

            inbox.process(CONSUMER, eventId, EventTypes.LEETCODE_JUDGE_REQUESTED_V1, () -> {
                long submissionId = JudgeResultListener.required(payload, "submissionId").asLong();
                String language = JudgeResultListener.required(payload, "language").asText();
                CodeRunner runner = runners.stream().filter(value -> value.supports(language))
                        .findFirst().orElse(null);
                ExecutionResult result = runner == null
                        ? new ExecutionResult("SYSTEM_ERROR", 0, 0, 0, "Unsupported language")
                        : runner.runCode(
                                JudgeResultListener.required(payload, "code").asText(),
                                JudgeResultListener.required(payload, "testCasesJson").asText());

                EventEnvelope<JudgeCompleted> completed = EventEnvelope.fact(
                        EventTypes.LEETCODE_JUDGE_COMPLETED_V1, 1, "leetcode-judge-worker",
                        "leetcode-submission", Long.toString(submissionId),
                        Math.max(1, aggregateVersion), correlationId, eventId,
                        root.hasNonNull("traceparent") ? root.get("traceparent").asText() : null,
                        new JudgeCompleted(submissionId, result.getStatus(), result.getPassedCount(),
                                result.getTotalCount(), result.getExecutionTimeMs(),
                                bounded(result.getErrorMessage())));
                outbox.save(new OutboxMessage(completed.eventId(), completed.aggregateType(),
                        completed.aggregateId(), completed.eventType(), completed.eventVersion(),
                        serializer.serialize(completed), Instant.now()));
            });
        } catch (RuntimeException failure) {
            failures.route(CONSUMER, delivery, failure);
        }
    }

    private JsonNode parse(Message delivery) {
        try {
            return mapper.readTree(new String(delivery.getBody(), StandardCharsets.UTF_8));
        } catch (RuntimeException malformed) {
            throw new InvalidEventSchemaException("malformed judge request", malformed);
        }
    }

    private static void validateEnvelope(JsonNode root) {
        if (!EventTypes.LEETCODE_JUDGE_REQUESTED_V1.equals(
                JudgeResultListener.required(root, "eventType").asText())) {
            throw new InvalidEventSchemaException("unexpected event type");
        }
        if (JudgeResultListener.required(root, "eventVersion").asInt() != 1) {
            throw new UnsupportedEventVersionException("only judge request v1 is supported");
        }
    }

    private static String bounded(String value) {
        return value == null ? null : value.substring(0, Math.min(value.length(), 4000));
    }
}
