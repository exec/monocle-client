# Bounded recovery and nested movement release

A drop-search radius around each journal entry does not bound travel to that entry. Generic recovery must checkpoint a fixed search origin, restrict candidate records and destinations to that area, and optionally filter by execution. Excluded historical obligations remain intact.

Disconnect, finish, pause and cancel must revoke nested recovery Travel input before waiting for checkpoints or cleanup. Stopping custom input clears both keys and its computed movement vector immediately.

Tests use invented recovery sites to cover nearby, boundary, remote and different-execution records, restart stability and nested disconnect wiring. Stale disconnected observations cannot prove continued movement or acknowledged cancellation.
