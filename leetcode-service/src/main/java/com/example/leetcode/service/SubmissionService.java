package com.example.leetcode.service;

import com.example.leetcode.model.*;
import com.example.leetcode.repository.*;
import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.leetcode.JudgeRequested;
import com.example.platform.messaging.support.OutboxMessage;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.support.SafeEventSerializer;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

@Service
public class SubmissionService {
    private final ProblemRepository problems; private final SubmissionRepository submissions;
    private final CompetitionRepository competitions; private final CompetitionProblemRepository competitionProblems;
    private final OutboxMessageRepository outbox; private final SafeEventSerializer serializer;
    private final int timeout;
    public SubmissionService(ProblemRepository problems, SubmissionRepository submissions, CompetitionRepository competitions,
            CompetitionProblemRepository competitionProblems, OutboxMessageRepository outbox,
            SafeEventSerializer serializer,
            @Value("${leetcode.runner.timeout-seconds:5}") int timeout) {
        this.problems=problems; this.submissions=submissions; this.competitions=competitions;
        this.competitionProblems=competitionProblems; this.outbox=outbox;
        this.serializer=serializer; this.timeout=timeout;
    }
    @Transactional
    public Submission submit(String userId,String username,String problemId,String competitionId,String language,String code,String key) {
        String stableUserId;
        try { stableUserId=UUID.fromString(userId).toString(); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Stable user ID is required"); }
        if (key!=null&&!key.isBlank()) { var old=submissions.findByUserIdAndIdempotencyKey(stableUserId,key); if(old.isPresent()) return old.get(); }
        Problem problem=problems.findById(problemId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Problem not found"));
        String lang=language.toLowerCase();
        if(!java.util.Set.of("python","javascript","java").contains(lang)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Language not supported");
        Instant now=Instant.now();
        if(competitionId!=null) {
            Competition c=competitions.findById(competitionId).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Competition not found"));
            if(c.getStartTime()==null||c.getEndTime()==null||now.isBefore(c.getStartTime())||now.isAfter(c.getEndTime())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Competition is not active");
            if(!competitionProblems.existsByCompetitionIdAndProblemId(competitionId,problemId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Problem is not in this competition");
        }
        Submission s=new Submission(); s.setProblemId(problemId); s.setUserId(stableUserId); s.setUsername(username); s.setCode(code); s.setLanguage(lang);
        s.setStatus("QUEUED"); s.setPassedCount(0); s.setTotalCount(0); s.setCompetitionId(competitionId); s.setSubmittedAt(now); s.setUpdatedAt(now); s.setIdempotencyKey(key);
        s=submissions.saveAndFlush(s);
        enqueueJudgeRequest(s, problem, UUID.randomUUID(), null, now);
        return s;
    }

    @Transactional
    public int requeueStale(Duration age, int batchSize) {
        Instant cutoff = Instant.now().minus(age);
        var stale = submissions.findStaleQueued(cutoff,
                org.springframework.data.domain.PageRequest.of(0, batchSize));
        for (Submission submission : stale) {
            Problem problem = problems.findById(submission.getProblemId()).orElse(null);
            if (problem == null) continue;
            Instant now = Instant.now();
            submission.setUpdatedAt(now);
            submissions.saveAndFlush(submission);
            enqueueJudgeRequest(submission, problem, UUID.randomUUID(), null, now);
        }
        return stale.size();
    }

    private void enqueueJudgeRequest(Submission submission, Problem problem, UUID correlationId,
            UUID causationId, Instant now) {
        long aggregateVersion = Math.max(1L,
                submission.getVersion() == null ? 1L : submission.getVersion() + 1L);
        EventEnvelope<JudgeRequested> event = EventEnvelope.fact(
                EventTypes.LEETCODE_JUDGE_REQUESTED_V1, 1, "leetcode-service",
                "leetcode-submission", submission.getId().toString(), aggregateVersion,
                correlationId, causationId, null,
                new JudgeRequested(submission.getId(), submission.getProblemId(),
                        submission.getLanguage(), submission.getCode(), problem.getTestCases(), timeout));
        outbox.save(new OutboxMessage(event.eventId(), event.aggregateType(), event.aggregateId(),
                event.eventType(), event.eventVersion(), serializer.serialize(event), now));
    }
}
