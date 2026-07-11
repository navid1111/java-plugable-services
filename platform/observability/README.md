# Platform Observability (T052)

Dashboards and alerts for the messaging backbone: outbox age, outbox backlog, queue
depth/age, projection lag, consumer retries, DLQ count, circuit breakers, and BFF partial
responses. Built on the `messaging.*` Micrometer meters (see `MessagingMetrics`).

## Run

```bash
docker compose --profile observability up -d      # prometheus :9090, grafana :3000
```

Grafana auto-provisions the Prometheus datasource and the **Platform Messaging** dashboard
(`platform-messaging`). Default login `admin`/`admin` (override `GRAFANA_*`).

## Enabling metrics on a service

A service is scraped at `/actuator/prometheus`. Each service that should appear needs:

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```
and `management.endpoints.web.exposure.include=health,info,metrics,prometheus`. RabbitMQ
queue metrics come from the management image's built-in `rabbitmq_prometheus` plugin
(`:15692`), already present in the platform broker.

Register a `MessagingMetrics` bean per service, wiring the gauges to that service's outbox
age/backlog, projection lag, and DLQ depth (broker-side depth via a scheduled read of
`rabbitmqadmin`/management API, or the RabbitMQ metrics job).

## Alerts

`prometheus/rules/messaging-alerts.yml` — grouped rules with `severity` labels and runbook
links (`docs/architecture/runbooks/messaging-recovery.md`):

| Alert | Fires when | Severity |
|-------|-----------|----------|
| `OutboxBacklogAgeHigh` | `messaging_outbox_age_seconds > 60` for 5m | page |
| `OutboxPublishFailing` | `rate(messaging_publish_failures_total[5m]) > 0` for 10m | page |
| `DeadLetterQueueNotEmpty` | `messaging_dlq_count > 0` for 5m | page |
| `ConsumerRetriesHigh` | `rate(messaging_consumer_retries_total[5m]) > 0.2` for 10m | warning |
| `ProjectionLagHigh` | `messaging_projection_lag_seconds > 30` for 5m | warning |
| `QueueDepthHigh` | `rabbitmq_queue_messages_ready > 1000` for 5m | warning |
| `QueueBacklogAgeHigh` | head message older than 5m | warning |
| `CircuitBreakerOpen` | `resilience4j_circuitbreaker_state{state="open"} == 1` for 1m | page |
| `BffPartialResponsesHigh` | `rate(bff_partial_responses_total[5m]) > 0.1` for 10m | warning |

## Verify — synthetic failure triggers the expected alert

`prometheus/rules/messaging-alerts.test.yml` feeds synthetic metric series (a parked DLQ
message, an aging outbox, accruing publish failures, an open breaker) and asserts each alert
fires with the exact labels/annotations — plus a healthy `messaging_dlq_count = 0` case that
must **not** alert. Run it with no live stack:

```bash
docker run --rm --entrypoint promtool \
  -v "$PWD/platform/observability/prometheus/rules":/w \
  prom/prometheus test rules /w/messaging-alerts.test.yml
# => SUCCESS
```

This is the automated form of the T052 verify. For an end-to-end check, bring up the
`observability` + a service profile, stop RabbitMQ, and watch `DeadLetterQueueNotEmpty` /
`OutboxBacklogAgeHigh` move to firing in Prometheus (`:9090/alerts`).
