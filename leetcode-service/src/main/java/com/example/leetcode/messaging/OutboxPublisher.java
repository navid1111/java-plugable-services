package com.example.leetcode.messaging;

import com.example.leetcode.repository.OutboxEventRepository;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Component
public class OutboxPublisher {
    private final OutboxEventRepository repository; private final RabbitTemplate rabbit;
    public OutboxPublisher(OutboxEventRepository r,RabbitTemplate t){repository=r;rabbit=t;}
    @Scheduled(fixedDelayString="${leetcode.outbox.delay-ms:500}") @Transactional
    public void publish(){
        for(var e:repository.findByPublishedAtIsNullOrderByOccurredAtAsc(PageRequest.of(0,50))){
            try{rabbit.convertAndSend(MessagingConfig.EXCHANGE,e.getEventType(),e.getPayload(),m->{m.getMessageProperties().setContentType("application/json");m.getMessageProperties().setMessageId(e.getId().toString());m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);return m;});e.setPublishedAt(Instant.now());e.setLastError(null);}
            catch(RuntimeException x){e.setAttempts(e.getAttempts()+1);String v=x.getMessage()==null?x.getClass().getSimpleName():x.getMessage();e.setLastError(v.substring(0,Math.min(1000,v.length())));}
        }
    }
}
