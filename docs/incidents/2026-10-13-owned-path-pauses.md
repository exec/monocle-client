# Overlapping path pauses

A shared backend pause boolean lets one module resume a path another module still needs paused. An already-paused path must remain discoverable to additional owners.

Identity-based pause ownership sits ahead of the backend: the first owner pauses and the last release resumes. Duplicate operations and unrelated releases are no-ops. Built-in meal and combat callers release on disable and their own invalid-target or completion paths.

Behavioral and compiled-call checks cover overlapping owners and cleanup. Third-party callers using the legacy backend pause API remain outside this ownership guarantee.
