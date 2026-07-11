package com.example.leetcode.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "leetcode.role", havingValue = "api", matchIfMissing = true)
public class StaleSubmissionReconciler {
    private final SubmissionService submissions;
    private final Duration staleAfter;
    private final int batchSize;

    public StaleSubmissionReconciler(SubmissionService submissions,
            @Value("${leetcode.judge.stale-after:2m}") Duration staleAfter,
            @Value("${leetcode.judge.reconcile-batch-size:100}") int batchSize) {
        this.submissions = submissions;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${leetcode.judge.reconcile-delay-ms:30000}")
    public void requeueStaleSubmissions() {
        submissions.requeueStale(staleAfter, batchSize);
    }
}
