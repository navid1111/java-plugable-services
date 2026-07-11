# Messaging Recovery Runbook

## Ownership

The producing service owns event correctness and outbox recovery. Each consuming service owns its inbox, projection, retry queue, and DLQ. RabbitMQ availability is a platform responsibility.

## Backlog or Broker Outage

1. Confirm producer databases remain writable; committed outbox rows are the recovery source.
2. Check RabbitMQ health, disk/memory alarms, connections, queue depth, and oldest message age.
3. Restore the broker before scaling publishers. Never delete unpublished outbox rows.
4. Resume publishers gradually and watch confirm failures, queue age, and consumer saturation.
5. Reconcile source aggregates against projections after the backlog drains.

## DLQ Inspection and Redrive

1. Pause redrive for a repeating poison message.
2. Record event ID, type/version, producer, aggregate ID/version, failure class, and redacted diagnostics.
3. Correct code/schema/configuration first. Do not edit authoritative payloads in place.
4. Redrive into the original exchange with the original event ID so inbox deduplication remains effective.
5. Audit operator, reason, count, start/end time, and outcome.

## Projection Rebuild

1. Stop the affected consumer.
2. Create a checkpoint and back up the projection database.
3. Rebuild from the authoritative owner export or supported replay source.
4. Resume at the checkpoint and compare counts, aggregate versions, and sampled content.
5. Roll back to the backup if unexplained divergence remains.

## Schema Rollback

Consumers must tolerate additive fields. For a breaking deployment, restore the previous consumer and continue routing the previous event version. Producers must not remove the old version until every registered consumer has migrated.

## Credential Rotation

Create the replacement least-privilege RabbitMQ credential, deploy consumers/producers with it, verify connections and permissions, then revoke the old credential. Never place credentials in events, logs, or committed Compose files.
