# Stable Identity Migration and Backfill Drill

This drill validates the username-to-stable-user-ID cutover before it is run on a
sanitized production dump. It uses PostgreSQL 16 and the same `UserIdentityBackfill`
implementation that Tweeter, comments, media, and post-search run in production.

## One-command verification

```bash
./platform/scripts/test-migration-backfill-drill.sh
```

The command first runs the Tweeter Flyway migration against an existing pre-lifecycle
posts schema, verifying preservation and rollback safety. A second Testcontainers fixture
then creates two independently owned legacy tables containing 50,000 total references
spread across 250 exported auth users. It:

1. records row counts and content checksums and takes table snapshots;
2. backfills only rows whose stable ID is null;
3. verifies 50,000 updates, zero unresolved rows, unchanged counts/checksums, and an
   idempotent report;
4. restores the pre-change snapshots and verifies the original null-ID state and
   checksums exactly;
5. reapplies the backfill successfully; and
6. injects a failure in a later target update and proves the earlier target update is
   rolled back by the shared transaction.

Latest local result (2026-07-12, PostgreSQL 16-alpine):

```text
identity_backfill_drill rows=50000 users=250 elapsed_ms=468 unresolved=0 rollback=passed
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

The elapsed time is evidence from this workstation, not a production forecast. Run the
same command on production-equivalent compute, then rehearse against a sanitized dump
before approving a change window.

## Lock and availability behavior

Each service runs its own backfill in one database transaction. PostgreSQL takes a
`ROW EXCLUSIVE` table lock and row locks only for matching `... user_id IS NULL` rows.
Ordinary reads continue. Writes touching the same legacy rows can wait until commit, so
the operational run should use small owner-by-owner windows and monitor lock waits:

```sql
SELECT pid, wait_event_type, wait_event, state, query_start, left(query, 160)
FROM pg_stat_activity
WHERE datname = current_database() AND wait_event_type = 'Lock';
```

The drill creates a partial username index over unresolved rows. On a restored dump,
verify an equivalent index with `EXPLAIN (ANALYZE, BUFFERS)` and create it concurrently
before the window when needed. Do not run every service's backfill simultaneously merely
to shorten the change window.

## Sanitized production-copy procedure

For each owner database (`postsdb`, `commentsdb`, `mediadb`, `postsearchdb`):

1. Restore a current sanitized `pg_dump` into an isolated PostgreSQL 16 instance.
2. Record table counts, null-ID counts, and checksums of non-identity business columns.
3. Take a fresh pre-migration snapshot. Keep it until the acceptance window closes.
4. Deploy the candidate image so Flyway/additive schema changes run first.
5. Start only that owner with `IDENTITY_BACKFILL_RUN_ON_STARTUP=true` and capture its
   `identity_backfill=Report[...]` audit line.
6. Require `unresolvedRows=0`. Any nonzero row is exported with username, table, and
   primary key to an owner-reviewed exception file; it is never silently discarded.
7. Re-run with the flag enabled. A completed cutover must report `updatedRows=0` and
   `unresolvedRows=0`.
8. Compare pre/post counts and business-column checksums. Differences outside the stable
   ID columns block release.

## Rollback

If schema validation, lock duration, counts, checksums, or unresolved references fail:

1. stop the affected service so no writes race the restore;
2. retain the failed database for diagnosis;
3. restore the pre-migration snapshot into a new database;
4. point the previous service image at the restored database; and
5. verify row counts/checksums and the previous release's smoke tests before reopening
   traffic.

Never edit Flyway history or null stable IDs in place as a rollback. The automated drill
proves both transaction rollback and snapshot restore; the production-copy rehearsal
proves the actual dump/restore timing for the deployment environment.
