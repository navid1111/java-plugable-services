package com.example.leetcode.messaging;

import com.example.leetcode.repository.SubmissionRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;

@Component
@ConditionalOnProperty(name="leetcode.role",havingValue="api",matchIfMissing=true)
public class JudgeResultListener {
    private static final Set<String> TERMINAL=Set.of("ACCEPTED","WRONG_ANSWER","COMPILE_ERROR","RUNTIME_ERROR","TIME_LIMIT_EXCEEDED","MEMORY_LIMIT_EXCEEDED","SYSTEM_ERROR");
    private final SubmissionRepository repository; private final ObjectMapper mapper;
    public JudgeResultListener(SubmissionRepository r,ObjectMapper m){repository=r;mapper=m;}
    @RabbitListener(queues=MessagingConfig.RESULT_QUEUE) @Transactional
    public void complete(String json){
        var p=mapper.readTree(json).get("payload"); var s=repository.findById(p.get("submissionId").asLong()).orElse(null);
        if(s==null||TERMINAL.contains(s.getStatus())) return;
        String status=p.get("status").asText(); if(!TERMINAL.contains(status)) status="SYSTEM_ERROR";
        s.setStatus(status);s.setPassedCount(p.get("passedCount").asInt());s.setTotalCount(p.get("totalCount").asInt());s.setExecutionTimeMs(p.get("executionTimeMs").asInt());
        if(p.has("errorMessage")&&!p.get("errorMessage").isNull())s.setErrorMessage(p.get("errorMessage").asText());s.setCompletedAt(Instant.now());s.setUpdatedAt(Instant.now());
    }
}
