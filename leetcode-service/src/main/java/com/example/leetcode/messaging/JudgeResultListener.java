package com.example.leetcode.messaging;

import com.example.leetcode.repository.SubmissionRepository;
import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.support.DeterministicProcessingException;
import com.example.platform.messaging.support.InboxIdempotency;
import com.example.platform.messaging.support.InvalidEventSchemaException;
import com.example.platform.messaging.support.UnsupportedEventVersionException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "leetcode.role", havingValue = "api", matchIfMissing = true)
public class JudgeResultListener {
    static final String CONSUMER = MessagingConfig.RESULT_QUEUE;
    private static final Set<String> TERMINAL = Set.of(
            "ACCEPTED", "WRONG_ANSWER", "COMPILE_ERROR", "RUNTIME_ERROR",
            "TIME_LIMIT_EXCEEDED", "MEMORY_LIMIT_EXCEEDED", "SYSTEM_ERROR");

    private final SubmissionRepository repository;
    private final ObjectMapper mapper;
    private final InboxIdempotency inbox;
    private final LeetcodeDeliveryFailureHandler failures;

    public JudgeResultListener(SubmissionRepository repository, ObjectMapper mapper,
            InboxIdempotency inbox, LeetcodeDeliveryFailureHandler failures) {
        this.repository = repository;
        this.mapper = mapper;
        this.inbox = inbox;
        this.failures = failures;
    }

    @RabbitListener(queues = MessagingConfig.RESULT_QUEUE)
    public void complete(Message delivery) {
        try {
            JsonNode root = parse(delivery);
            validateEnvelope(root, EventTypes.LEETCODE_JUDGE_COMPLETED_V1);
            UUID eventId = UUID.fromString(required(root, "eventId").asText());
            JsonNode payload = required(root, "payload");
            long submissionId = required(payload, "submissionId").asLong();

            inbox.process(CONSUMER, eventId, EventTypes.LEETCODE_JUDGE_COMPLETED_V1, () -> {
                var submission = repository.findById(submissionId).orElseThrow(() ->
                        new DeterministicProcessingException("submission not found: " + submissionId));
                if (TERMINAL.contains(submission.getStatus())) return;

                String status = required(payload, "status").asText();
                if (!TERMINAL.contains(status)) {
                    throw new InvalidEventSchemaException("unsupported terminal status: " + status);
                }
                submission.setStatus(status);
                submission.setPassedCount(required(payload, "passedCount").asInt());
                submission.setTotalCount(required(payload, "totalCount").asInt());
                submission.setExecutionTimeMs(required(payload, "executionTimeMs").asInt());
                JsonNode error = payload.get("errorMessage");
                submission.setErrorMessage(error == null || error.isNull() ? null : error.asText());
                submission.setCompletedAt(Instant.now());
                submission.setUpdatedAt(Instant.now());
                repository.save(submission);
            });
        } catch (RuntimeException failure) {
            failures.route(CONSUMER, delivery, failure);
        }
    }

    private JsonNode parse(Message delivery) {
        try {
            return mapper.readTree(new String(delivery.getBody(), StandardCharsets.UTF_8));
        } catch (RuntimeException malformed) {
            throw new InvalidEventSchemaException("malformed judge result", malformed);
        }
    }

    private static void validateEnvelope(JsonNode root, String eventType) {
        if (!eventType.equals(required(root, "eventType").asText())) {
            throw new InvalidEventSchemaException("unexpected event type");
        }
        if (required(root, "eventVersion").asInt() != 1) {
            throw new UnsupportedEventVersionException("only judge result v1 is supported");
        }
    }

    static JsonNode required(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || value.isNull()) {
            throw new InvalidEventSchemaException("missing required field: " + field);
        }
        return value;
    }
}
