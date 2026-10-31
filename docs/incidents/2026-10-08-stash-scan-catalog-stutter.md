# Scan catalog growth and stutter

Synchronous read/parse/rewrite/fsync of a growing catalog on the Minecraft thread can make scans progressively stall. Repeated full summaries and unnecessary whole-job checkpoints add overhead.

The initial correction moves immutable catalog mutations to a bounded daemon writer, polls completion on the game thread and requires local durability before upload. A failed write retains the previous file; restart reobserves the selected target rather than claiming completion.

Observation ingestion skips unused aggregates, duplicate acknowledgements do not checkpoint again, and display-only changes use periodic status. This historical stage still used whole-file JSON and one host receipt; later SQLite and scan-pipeline documentation supersedes those storage limits. Performance attribution requires profiling rather than inferring it from ping.
