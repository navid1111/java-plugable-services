package com.example.leetcode.service;

import com.example.leetcode.messaging.*;
import com.example.leetcode.model.*;
import com.example.leetcode.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;

@Service
public class SubmissionService {
    private final ProblemRepository problems; private final SubmissionRepository submissions;
    private final CompetitionRepository competitions; private final CompetitionProblemRepository competitionProblems;
    private final OutboxEventRepository outbox; private final ObjectMapper mapper; private final int timeout;
    public SubmissionService(ProblemRepository problems, SubmissionRepository submissions, CompetitionRepository competitions,
            CompetitionProblemRepository competitionProblems, OutboxEventRepository outbox, ObjectMapper mapper,
            @Value("${leetcode.runner.timeout-seconds:5}") int timeout) {
        this.problems=problems; this.submissions=submissions; this.competitions=competitions;
        this.competitionProblems=competitionProblems; this.outbox=outbox; this.mapper=mapper; this.timeout=timeout;
    }
    @Transactional
    public Submission submit(String username,String problemId,String competitionId,String language,String code,String key) {
        if (key!=null&&!key.isBlank()) { var old=submissions.findByUsernameAndIdempotencyKey(username,key); if(old.isPresent()) return old.get(); }
        Problem problem=problems.findById(problemId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Problem not found"));
        String lang=language.toLowerCase();
        if(!java.util.Set.of("python","javascript","java").contains(lang)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Language not supported");
        Instant now=Instant.now();
        if(competitionId!=null) {
            Competition c=competitions.findById(competitionId).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Competition not found"));
            if(c.getStartTime()==null||c.getEndTime()==null||now.isBefore(c.getStartTime())||now.isAfter(c.getEndTime())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Competition is not active");
            if(!competitionProblems.existsByCompetitionIdAndProblemId(competitionId,problemId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Problem is not in this competition");
        }
        Submission s=new Submission(); s.setProblemId(problemId); s.setUsername(username); s.setCode(code); s.setLanguage(lang);
        s.setStatus("QUEUED"); s.setPassedCount(0); s.setTotalCount(0); s.setCompetitionId(competitionId); s.setSubmittedAt(now); s.setUpdatedAt(now); s.setIdempotencyKey(key);
        s=submissions.saveAndFlush(s);
        EventEnvelope<JudgeRequested> event=EventEnvelope.create("leetcode.submission.judge.requested","leetcode-service",new JudgeRequested(s.getId(),problemId,lang,code,problem.getTestCases(),timeout));
        OutboxEvent row=new OutboxEvent(); row.setId(event.eventId()); row.setAggregateId(s.getId().toString()); row.setEventType(MessagingConfig.JUDGE_KEY); row.setPayload(mapper.writeValueAsString(event)); row.setOccurredAt(now); outbox.save(row);
        return s;
    }
}
