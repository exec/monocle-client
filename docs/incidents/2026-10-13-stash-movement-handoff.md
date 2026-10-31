# Stash walking-to-flight input handoff

Baritone can replace player input while keys remain forced even without an active path. Cancelling an already completed goal may leave those keys if cleanup only runs through an executor.

Release forced keys only for our retained goal with no foreign controller and no remaining executor. Restore only the exact idle Baritone input, never another custom controller. Worker movement closes owned stash navigation and waits for safe cancellation before taking input.

Diagnostics report the actual replacement input class. Ownership, active-path and production-order tests cover the identified code gap; a generic movement-control error alone cannot attribute every historical failure to Baritone.
