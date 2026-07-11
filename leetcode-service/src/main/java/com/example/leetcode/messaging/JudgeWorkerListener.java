package com.example.leetcode.messaging;

import com.example.leetcode.service.runner.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

@Component
@ConditionalOnProperty(name="leetcode.role",havingValue="worker")
public class JudgeWorkerListener {
    private final List<CodeRunner> runners; private final RabbitTemplate rabbit; private final ObjectMapper mapper;
    public JudgeWorkerListener(List<CodeRunner> runners,RabbitTemplate rabbit,ObjectMapper mapper){this.runners=runners;this.rabbit=rabbit;this.mapper=mapper;}
    @RabbitListener(queues=MessagingConfig.JUDGE_QUEUE)
    public void judge(String json){
        var root=mapper.readTree(json); var p=root.get("payload"); long id=p.get("submissionId").asLong(); String lang=p.get("language").asText();
        CodeRunner runner=runners.stream().filter(r->r.supports(lang)).findFirst().orElse(null);
        ExecutionResult result=runner==null?new ExecutionResult("SYSTEM_ERROR",0,0,0,"Unsupported language"):runner.runCode(p.get("code").asText(),p.get("testCasesJson").asText());
        UUID eventId=UUID.randomUUID();
        Map<String,Object> envelope=new LinkedHashMap<>(); envelope.put("eventId",eventId); envelope.put("eventType","leetcode.submission.judge.completed");envelope.put("eventVersion",1);envelope.put("occurredAt",Instant.now());envelope.put("producer","leetcode-judge-worker");envelope.put("correlationId",root.get("correlationId").asText());envelope.put("causationId",root.get("eventId").asText());
        envelope.put("payload",new JudgeCompleted(id,result.getStatus(),result.getPassedCount(),result.getTotalCount(),result.getExecutionTimeMs(),bounded(result.getErrorMessage())));
        rabbit.convertAndSend(MessagingConfig.EXCHANGE,MessagingConfig.RESULT_KEY,mapper.writeValueAsString(envelope));
    }
    private String bounded(String v){return v==null?null:v.substring(0,Math.min(v.length(),4000));}
}
