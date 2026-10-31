# Single-kit recovery and unrelated inventory changes

Whole-inventory equality can reject a recoverable single-kit withdrawal when food or another unrelated non-kit slot changes. The mismatch alone does not prove cargo conservation.

Reuse the shared cargo-slot comparison to permit benign changes while keeping every existing shulker exact. Fresh-menu, source, cursor, requested-count and ambiguity checks remain mandatory, including relocated cursor returns.

Per-slot item/count/damage diagnostics distinguish unrelated consumption from actual cargo changes. Recovery must preserve confirmed pickups and never replay an uncertain click merely because the rest of the job is intended to run unattended.
