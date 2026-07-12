package com.example.bff.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Downstream endpoints, per-call timeouts, and the overall composition budget. */
@ConfigurationProperties("bff")
public class BffProperties {

    private final Downstream downstream = new Downstream();
    private final Composition composition = new Composition();

    public Downstream getDownstream() { return downstream; }
    public Composition getComposition() { return composition; }

    public static class Downstream {
        private final Endpoint tweeter = new Endpoint();
        private final Endpoint comment = new Endpoint();
        private final Endpoint media = new Endpoint();
        private Duration connectTimeout = Duration.ofMillis(500);
        private Duration readTimeout = Duration.ofMillis(1000);

        public Endpoint getTweeter() { return tweeter; }
        public Endpoint getComment() { return comment; }
        public Endpoint getMedia() { return media; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration v) { this.connectTimeout = v; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration v) { this.readTimeout = v; }
    }

    public static class Endpoint {
        private String baseUrl;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public static class Composition {
        private Duration deadline = Duration.ofMillis(1500);
        private int poolSize = 16;
        public Duration getDeadline() { return deadline; }
        public void setDeadline(Duration deadline) { this.deadline = deadline; }
        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
    }
}
