package com.example.leetcode.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.leetcode.model.Submission;
import com.example.leetcode.repository.CompetitionProblemRepository;
import com.example.leetcode.repository.CompetitionRepository;
import com.example.leetcode.repository.ProblemRepository;
import com.example.leetcode.repository.SubmissionRepository;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.support.SafeEventSerializer;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SubmissionIdentityTest {
    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void usernameRenameDoesNotChangeSubmissionIdempotencyIdentity() {
        ProblemRepository problems = mock(ProblemRepository.class);
        SubmissionRepository submissions = mock(SubmissionRepository.class);
        Submission existing = new Submission();
        existing.setId(42L);
        existing.setUserId(USER_ID);
        existing.setUsername("alice");
        when(submissions.findByUserIdAndIdempotencyKey(USER_ID, "request-1"))
                .thenReturn(Optional.of(existing));
        SubmissionService service = new SubmissionService(problems, submissions,
                mock(CompetitionRepository.class), mock(CompetitionProblemRepository.class),
                mock(OutboxMessageRepository.class), mock(SafeEventSerializer.class), 5);

        Submission result = service.submit(USER_ID, "alice-renamed", "two-sum", null,
                "python", "print(1)", "request-1");

        assertSame(existing, result);
        verify(submissions).findByUserIdAndIdempotencyKey(USER_ID, "request-1");
        verifyNoInteractions(problems);
    }
}
