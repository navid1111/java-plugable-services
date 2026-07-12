package com.example.bff.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

/**
 * One {@link RestClient} per downstream, each with strict connect/read timeouts (the per-call
 * deadline), and a bounded executor for fan-out. The pool is capped and overflow runs on the
 * calling thread, so composition parallelism can never exhaust resources.
 */
@Configuration
public class DownstreamConfig {

    private final BffProperties properties;

    public DownstreamConfig(BffProperties properties) {
        this.properties = properties;
    }

    private RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getDownstream().getConnectTimeout());
        factory.setReadTimeout(properties.getDownstream().getReadTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Bean
    RestClient tweeterClient() {
        return client(properties.getDownstream().getTweeter().getBaseUrl());
    }

    @Bean
    RestClient commentClient() {
        return client(properties.getDownstream().getComment().getBaseUrl());
    }

    @Bean
    RestClient mediaClient() {
        return client(properties.getDownstream().getMedia().getBaseUrl());
    }

    @Bean
    ThreadPoolTaskExecutor compositionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int size = properties.getComposition().getPoolSize();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(size * 4);
        executor.setThreadNamePrefix("bff-compose-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
