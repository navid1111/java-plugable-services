package com.example.leetcode.messaging;

import com.example.platform.messaging.support.EventErrorClassifier;
import com.example.platform.messaging.support.EventOutcomeRouter;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

@Component
public class LeetcodeDeliveryFailureHandler {
    static final String ATTEMPT_HEADER = "x-platform-attempt";

    private final EventErrorClassifier classifier;
    private final EventOutcomeRouter router;

    public LeetcodeDeliveryFailureHandler(EventErrorClassifier classifier, EventOutcomeRouter router) {
        this.classifier = classifier;
        this.router = router;
    }

    public void route(String consumer, Message delivery, Throwable failure) {
        Object current = delivery.getMessageProperties().getHeaders().get(ATTEMPT_HEADER);
        int attempts = current instanceof Number number ? number.intValue() + 1 : 1;
        delivery.getMessageProperties().setHeader(ATTEMPT_HEADER, attempts);
        router.route(consumer, classifier.classify(failure, attempts), delivery);
    }
}
