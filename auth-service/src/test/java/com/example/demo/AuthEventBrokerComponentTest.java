package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.support.MessagingTopology;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.support.OutboxRelay;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "platform.messaging.outbox.scheduling-enabled=false",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthEventBrokerComponentTest {
    private static final String PROBE = "auth-component.user-events-probe";

    @Container static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    @Container @ServiceConnection static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/service");
        // Auth scans the shared support package, which also contains optional inbox entities.
        // Its service migration owns the outbox; Hibernate creates unused optional tables here.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Autowired MockMvc mvc;
    @Autowired OutboxMessageRepository outbox;
    @Autowired OutboxRelay relay;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbitTemplate;

    @BeforeEach
    void clean() {
        outbox.deleteAll();
        rabbitAdmin.deleteQueue(PROBE);
    }

    @Test
    void registrationCommitsToPostgresThenPublishesConfirmedEventThroughRabbitMq() throws Exception {
        Queue probe = new Queue(PROBE, false);
        TopicExchange exchange = new TopicExchange(MessagingTopology.EVENTS_EXCHANGE, true, false);
        rabbitAdmin.declareQueue(probe);
        rabbitAdmin.declareBinding(BindingBuilder.bind(probe).to(exchange)
                .with(EventTypes.USER_REGISTERED_V1));
        String username = "broker-user-" + UUID.randomUUID();

        mvc.perform(post("/auth/register").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"secret-pass\"}"))
                .andExpect(status().isCreated());
        assertThat(outbox.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getEventType()).isEqualTo(EventTypes.USER_REGISTERED_V1);
            assertThat(row.getPublishedAt()).isNull();
        });

        assertThat(relay.drainOnce()).isEqualTo(1);
        var delivery = rabbitTemplate.receive(PROBE, 10_000);
        assertThat(delivery).isNotNull();
        assertThat(new String(delivery.getBody()))
                .contains(EventTypes.USER_REGISTERED_V1, username)
                .doesNotContain("secret-pass", "password", "access_token");
        assertThat(outbox.findAll()).singleElement()
                .extracting(row -> row.getPublishedAt()).isNotNull();
    }
}
