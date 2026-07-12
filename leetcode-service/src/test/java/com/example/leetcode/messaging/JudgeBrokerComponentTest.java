package com.example.leetcode.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.leetcode.model.Submission;
import com.example.leetcode.repository.SubmissionRepository;
import com.example.leetcode.service.SubmissionService;
import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.leetcode.JudgeCompleted;
import com.example.platform.messaging.support.InboxMessageRepository;
import com.example.platform.messaging.support.OutboxMessageRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "leetcode.role=api",
        "leetcode.outbox.delay-ms=100",
        "leetcode.judge.reconcile-delay-ms=3600000"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JudgeBrokerComponentTest {
    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PROBE = "leetcode-component.judge-probe";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired SubmissionService service;
    @Autowired SubmissionRepository submissions;
    @Autowired InboxMessageRepository inbox;
    @Autowired OutboxMessageRepository outbox;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clean() {
        inbox.deleteAll();
        outbox.deleteAll();
        submissions.deleteAll();
        rabbitAdmin.deleteQueue(PROBE);
    }

    @Test
    void committedSubmissionPublishesJudgeRequestThroughRealBroker() throws Exception {
        Queue probe = new Queue(PROBE, false);
        TopicExchange exchange = new TopicExchange(MessagingConfig.EXCHANGE, true, false);
        rabbitAdmin.declareQueue(probe);
        rabbitAdmin.declareBinding(BindingBuilder.bind(probe).to(exchange).with(MessagingConfig.JUDGE_KEY));

        Submission submission = service.submit(USER_ID, "alice", "two-sum", null,
                "python", "print(1)", "request-1");

        org.springframework.amqp.core.Message delivery = rabbitTemplate.receive(PROBE, 10_000);
        assertNotNull(delivery, "judge request should reach RabbitMQ");
        String body = new String(delivery.getBody());
        assertTrue(body.contains(EventTypes.LEETCODE_JUDGE_REQUESTED_V1));
        assertTrue(body.contains("\"submissionId\":" + submission.getId()));
        await(Duration.ofSeconds(5), () -> outbox.findAll().stream()
                .allMatch(row -> row.getPublishedAt() != null));
    }

    @Test
    void duplicateJudgeResultMakesOneTerminalInboxEffect() throws Exception {
        Submission submission = new Submission();
        submission.setProblemId("two-sum");
        submission.setUserId(USER_ID);
        submission.setUsername("alice");
        submission.setCode("print(1)");
        submission.setLanguage("python");
        submission.setStatus("QUEUED");
        submission.setSubmittedAt(Instant.now());
        submission.setUpdatedAt(Instant.now());
        submission = submissions.saveAndFlush(submission);
        EventEnvelope<JudgeCompleted> event = EventEnvelope.fact(
                EventTypes.LEETCODE_JUDGE_COMPLETED_V1, 1, "leetcode-judge-worker",
                "leetcode-submission", submission.getId().toString(), 1,
                UUID.randomUUID(), UUID.randomUUID(), null,
                new JudgeCompleted(submission.getId(), "ACCEPTED", 2, 2, 12, null));
        String json = mapper.writeValueAsString(event);

        rabbitTemplate.convertAndSend(MessagingConfig.EXCHANGE, MessagingConfig.RESULT_KEY, json);
        rabbitTemplate.convertAndSend(MessagingConfig.EXCHANGE, MessagingConfig.RESULT_KEY, json);

        long submissionId = submission.getId();
        await(Duration.ofSeconds(10), () -> inbox.count() == 1 && submissions.findById(submissionId)
                .map(row -> "ACCEPTED".equals(row.getStatus())).orElse(false));
        assertEquals(1, inbox.count());
        assertEquals("ACCEPTED", submissions.findById(submissionId).orElseThrow().getStatus());
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && !condition.getAsBoolean()) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), "judge messaging flow did not converge");
    }
}
