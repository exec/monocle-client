# SQLite stash storage — 0.15.0

## Fresh start, intentionally

0.15.0 replaces stash JSON snapshots and delta directories with SQLite on workers, ingame hosts and standalone hosts. There is **no automatic import or migration**. Redefine each stash, including its required `/home` name, then scan or observe it normally. Existing JSON files remain untouched but are not listed or used for stock decisions.

Old host-summary caches, passive-update outboxes and solo-export outboxes are not replayed. The new cache/outbox names contain `sqlite-v1`. Existing job/configuration/history JSON, kit-category definitions and credentials are otherwise unchanged. Cancel old stash jobs before upgrading; redefine storage and create new jobs afterward. Do not resume pre-0.15.0 scans to populate the fresh catalog.

## Layout

Each stash is `stashes/<sha256(crew + newline + scope + newline + name)>.sqlite` under the client or coordinator data directory. Scope includes server and dimension. The filename and crew authorization still separate stashes; this is not a shared cross-host database.

Schema version is `PRAGMA user_version = 1`:

| Table | Contents |
| --- | --- |
| `containers` | One row per container key, with indexed `x`, `y`, `z`, `status`, `observed_at`, plus the authoritative observation JSON for that one container. Shulker contents and item counts remain in this bounded row. |
| `metadata` | Separate JSON values for definition/bounds, home routes, kit type exemplars/aliases, reservations, transfer accounting and other stash metadata. Not a serialized whole-catalog blob. |
| `scan_checkpoints` | Recovery state keyed by execution/action binding, with the plan hash, discovery cursor, pending containers, bounded observation outbox and delivery cursor. |

Coordinate indexes use `(x,z,y)` and freshness indexes use `(status,observed_at)`. Kit classification, inferred-versus-observed stock, double-chest merging, transfer receipt accounting and public API payloads retain their existing semantics. Stash revision hashes are independent of JSON object-key ordering, so a SQL reload alone does not invalidate edit/reservation controls.

## Write and read behavior

Observation ingestion loads metadata and only the affected chest/physical aliases—not every container. It updates those rows and changed metadata in one SQLite transaction. The database can materialize a full detached JSON view for management, stock summaries or existing transfer planning, but the scan write path does not make whole-catalog copies. Full definition/reservation replacements remain transactional and less frequent.

Coordinated scans commit their chest update **and** recovery checkpoint in the same transaction. A worker advertises a read as durable/uploadable only when that commit completes. The old separate scan-checkpoint file is no longer written. Host findings are acknowledged after committed catalog data and the existing task-receipt checkpoint; retries remain idempotent. Solo host exports and passive observations retain their separate durable, versioned delivery outboxes, rather than becoming fire-and-forget.

The existing background writer is retained. SQLite uses WAL and `synchronous=FULL`; durability is not traded for a favorable benchmark. Independent read-only connections get consistent snapshots without holding the Java writer lock while parsing the full stash. Writes are serialized and cached writer connections are bounded to 32. A five-second SQLite busy timeout handles external lock contention; failures remain visible, not successful ACKs. Unknown schema versions and incomplete version-1 schemas are rejected instead of silently recreated.

This fixes the whole-catalog work at the storage boundary. It does not remove movement, reach, eating, server-content confirmation or bounded queue safety gates, and it is not a guarantee of a particular Minecraft scan rate.

### Responsive catalog reads (0.15.2)

Unchanged stash summaries reuse a bounded in-memory read cache. In-process committed writes and SQLite's external-commit counter invalidate it; reopening a connection also invalidates its token. API callers receive detached summaries, and transfer decisions still read authoritative databases. Revisions retain their existing canonical SHA-256 format, now streamed without building a second recursively sorted JSON tree.

The standalone host refreshes its display and native-worker watch catalogs on a separate reader thread. Status API reads obtain fresh summaries before entering the coordinator monitor; worker receipts, heartbeats and roster checkpoints do not wait for catalog summarization. Background failures retain the last good snapshot and appear as `catalogError` in status. This is a read-path change, not a schema migration: 0.15.0/0.15.1 scans can continue after updating.

### Full deposit columns (0.15.4)

The allocator skips columns with observed, non-inferred full chest rows at every height through the definition's top, as well as durable full-column markers from exhausted deposits. A full bottom chest alone is not sufficient proof. Known-full columns are not revisited as a last resort. Confirmed withdrawals remove matching full-column markers; newer observations of free space also restore eligibility. A reserved bottom chest invalidated by a confirmed withdrawal may be reopened for validation, but still requires a compatible server-confirmed observation before any deposit is permitted.

## Inspection and backups

Open an idle copy with a SQLite browser or `sqlite3`:

```sql
PRAGMA user_version;
PRAGMA integrity_check;
SELECT key, value FROM metadata WHERE key IN ('name', 'crew', 'scope', 'bounds');
SELECT status, COUNT(*) FROM containers GROUP BY status;
SELECT x, y, z, observed_at FROM containers ORDER BY x, z, y;
SELECT x, y, z, json_extract(value, '$.occupiedSlots') FROM containers;
```

Do not manually edit or replace a live database. For a live backup use SQLite's backup facility; copying only the `.sqlite` file can omit committed WAL data. For an ordinary filesystem backup, stop the client/host cleanly and copy the stash directory, including any remaining `-wal` / `-shm` companions. Do not delete WAL files to “unlock” a stash. Stash data includes private coordinates and kit inventories: protect backups just like coordinator data.

## Verification

Shared-core tests cover cold reopen, detached read views, SQL rollback of rows and receipts, atomic scan recovery, process termination before/after commit, concurrent WAL readers, unsupported schemas, ignored legacy catalogs, physical container aliases, stock accounting and reservations. A synthetic 16,000-container test verifies that a targeted update loads one row; a second benchmark exercises real scan classification and recovery commits with a bounded outbox. These are local synthetic measurements, not a live gameplay benchmark.
