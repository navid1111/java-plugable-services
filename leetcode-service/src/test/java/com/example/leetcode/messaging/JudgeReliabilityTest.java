package com.example.leetcode.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.leetcode.model.Submission;
import com.example.leetcode.repository.SubmissionRepository;
import com.example.leetcode.service.runner.CodeRunner;
import com.example.leetcode.service.runner.ExecutionResult;
import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.leetcode.JudgeCompleted;
import com.example.platform.messaging.leetcode.JudgeRequested;
import com.example.platform.messaging.support.InboxIdempotency;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.support.SafeEventSerializer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;

class JudgeReliabilityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resultRedeliveryConvergesToOneTerminalMutation() {
        SubmissionRepository submissions = mock(SubmissionRepository.class);
        InboxIdempotency inbox = deduplicatingInbox();
        LeetcodeDeliveryFailureHandler failures = mock(LeetcodeDeliveryFailureHandler.class);
        Submission submission = new Submission();
        submission.setId(42L);
        submission.setStatus("QUEUED");
        when(submissions.findById(42L)).thenReturn(Optional.of(submission));

        JudgeResultListener listener = new JudgeResultListener(
                submissions, mapper, inbox, failures);
        EventEnvelope<JudgeCompleted> event = EventEnvelope.fact(
                EventTypes.LEETCODE_JUDGE_COMPLETED_V1, 1, "judge-worker",
                "leetcode-submission", "42", 1, UUID.randomUUID(), UUID.randomUUID(), null,
                new JudgeCompleted(42, "ACCEPTED", 2, 2, 18, null));
        Message delivery = message(mapper.writeValueAsString(event));

        listener.complete(delivery);
        listener.complete(delivery);

        assertEquals("ACCEPTED", submission.getStatus());
        assertEquals(2, submission.getPassedCount());
        verify(submissions, times(1)).save(submission);
        verify(failures, never()).route(anyString(), any(), any());
    }

    @Test
    void requestRedeliveryRunsCodeAndStoresOneDurableResult() {
        InboxIdempotency inbox = deduplicatingInbox();
        OutboxMessageRepository outbox = mock(OutboxMessageRepository.class);
        LeetcodeDeliveryFailureHandler failures = mock(LeetcodeDeliveryFailureHandler.class);
        CodeRunner runner = mock(CodeRunner.class);
        when(runner.supports("python")).thenReturn(true);
        when(runner.runCode("print(1)", "[]"))
                .thenReturn(new ExecutionResult("ACCEPTED", 1, 1, 4, null));
        JudgeWorkerListener listener = new JudgeWorkerListener(
                java.util.List.of(runner), mapper, inbox, outbox,
                new SafeEventSerializer(mapper), failures);
        EventEnvelope<JudgeRequested> event = EventEnvelope.fact(
                EventTypes.LEETCODE_JUDGE_REQUESTED_V1, 1, "leetcode-service",
                "leetcode-submission", "7", 1, UUID.randomUUID(), null, null,
                new JudgeRequested(7, "two-sum", "python", "print(1)", "[]", 5));
        Message delivery = message(mapper.writeValueAsString(event));

        listener.judge(delivery);
        listener.judge(delivery);

        verify(runner, times(1)).runCode("print(1)", "[]");
        verify(outbox, times(1)).save(any());
        verify(failures, never()).route(anyString(), any(), any());
    }

    private InboxIdempotency deduplicatingInbox() {
        InboxIdempotency inbox = mock(InboxIdempotency.class);
        HashSet<UUID> processed = new HashSet<>();
        doAnswer(invocation -> {
            UUID eventId = invocation.getArgument(1);
            if (!processed.add(eventId)) return false;
            Runnable action = invocation.getArgument(3);
            action.run();
            return true;
        }).when(inbox).process(anyString(), any(UUID.class), anyString(), any(Runnable.class));
        return inbox;
    }

    private static Message message(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
