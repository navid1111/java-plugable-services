package com.example.tweeter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.support.OutboxRelay;
import com.example.platform.messaging.support.MessagingTopology;
import com.example.tweeter.repository.PostRepository;
import com.example.tweeter.repository.FollowRepository;

@SpringBootTest(properties = "platform.messaging.outbox.scheduling-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostOutboxIntegrationTest {
    private static final String ALICE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String BOB_ID = "550e8400-e29b-41d4-a716-446655440001";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container @ServiceConnection
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Autowired PostService posts;
    @Autowired PostRepository postRepository;
    @Autowired OutboxMessageRepository outboxRepository;
    @Autowired FollowRepository followRepository;
    @Autowired RollbackProbe rollbackProbe;
    @Autowired OutboxRelay outboxRelay;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbitTemplate;

    @BeforeEach
    void clearDatabase() {
        outboxRepository.deleteAll();
        followRepository.deleteAll();
        postRepository.deleteAll();
    }

    @Test
    void committedPostAlwaysHasCreatedEventInOutbox() {
        String userId = java.util.UUID.randomUUID().toString();
        var post = posts.create(userId, "alice", "hello from a transaction");

        assertThat(postRepository.findById(post.getId())).isPresent();
        assertThat(post.getAuthorUserId()).isEqualTo(userId);
        assertThat(outboxRepository.findAll()).singleElement().satisfies(message -> {
            assertThat(message.getEventType()).isEqualTo(EventTypes.POST_CREATED_V1);
            assertThat(message.getAggregateId()).isEqualTo(post.getId().toString());
            assertThat(message.getPayload()).contains("hello from a transaction", "alice", userId);
        });
    }

    @Test
    void forcedRollbackStoresNeitherPostNorOutboxEvent() {
        assertThatThrownBy(() -> rollbackProbe.createThenFail())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced rollback");

        assertThat(postRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void updateAndDeleteEmitOneEventPerVersionAndRejectStaleWrites() {
        var created = posts.create(ALICE_ID, "alice", "version one");
        var updated = posts.update(created.getId(), ALICE_ID, "version two", 1);
        assertThat(updated.getVersion() + 1).isEqualTo(2);

        assertThatThrownBy(() -> posts.update(created.getId(), ALICE_ID, "lost update", 1))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(outboxRepository.count()).isEqualTo(2);

        var deleted = posts.delete(created.getId(), ALICE_ID, 2);
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getVersion() + 1).isEqualTo(3);
        assertThat(outboxRepository.findAll()).extracting(message -> message.getEventType())
                .containsExactlyInAnyOrder(EventTypes.POST_CREATED_V1,
                        EventTypes.POST_UPDATED_V1, EventTypes.POST_DELETED_V1);
        assertThat(outboxRepository.findAll()).extracting(message -> message.getPayload())
                .anySatisfy(payload -> assertThat(payload).contains("\"aggregateVersion\":3"));
    }

    @Test
    void repeatedFollowAndUnfollowProduceOnlyEffectiveEvents() {
        posts.follow(ALICE_ID, "alice", BOB_ID, "bob");
        posts.follow(ALICE_ID, "alice-renamed", BOB_ID, "bob-renamed");
        assertThat(followRepository.count()).isOne();
        assertThat(outboxRepository.findAll()).extracting(message -> message.getEventType())
                .containsExactly(EventTypes.FOLLOW_CREATED_V1);

        posts.unfollow(ALICE_ID, BOB_ID);
        posts.unfollow(ALICE_ID, BOB_ID);
        assertThat(followRepository.count()).isZero();
        assertThat(outboxRepository.findAll()).extracting(message -> message.getEventType())
                .containsExactlyInAnyOrder(EventTypes.FOLLOW_CREATED_V1, EventTypes.FOLLOW_DELETED_V1);
    }

    @Test
    void committedPostPublishesThroughRealBrokerAndOnlyThenCompletesOutbox() {
        String probeName = "tweeter-component.post-created-probe";
        rabbitAdmin.deleteQueue(probeName);
        Queue probe = new Queue(probeName, false);
        TopicExchange exchange = new TopicExchange(MessagingTopology.EVENTS_EXCHANGE, true, false);
        rabbitAdmin.declareQueue(probe);
        rabbitAdmin.declareBinding(BindingBuilder.bind(probe).to(exchange)
                .with(EventTypes.POST_CREATED_V1));

        var post = posts.create(java.util.UUID.randomUUID().toString(), "alice", "broker delivery");
        assertThat(outboxRepository.findAll()).singleElement()
                .extracting(message -> message.getPublishedAt()).isNull();

        assertThat(outboxRelay.drainOnce()).isEqualTo(1);
        var delivery = rabbitTemplate.receive(probeName, 10_000);
        assertThat(delivery).isNotNull();
        assertThat(new String(delivery.getBody()))
                .contains(EventTypes.POST_CREATED_V1, "\"postId\":\"" + post.getId() + "\"");
        assertThat(outboxRepository.findAll()).singleElement()
                .extracting(message -> message.getPublishedAt()).isNotNull();
    }

    @TestConfiguration
    static class RollbackConfiguration {
        @Bean
        RollbackProbe rollbackProbe(PostService postService) {
            return new RollbackProbe(postService);
        }
    }

    static class RollbackProbe {
        private final PostService postService;

        RollbackProbe(PostService postService) {
            this.postService = postService;
        }

        @Transactional
        public void createThenFail() {
            postService.create(java.util.UUID.randomUUID().toString(), "rollback-user", "this must disappear");
            throw new IllegalStateException("forced rollback");
        }
    }
}
