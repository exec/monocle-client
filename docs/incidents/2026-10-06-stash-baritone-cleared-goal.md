# Baritone cleared goals and hopper sources

CustomGoalProcess clears its current goal on arrival or path failure while retaining mostRecentGoal. A null current goal with our exact retained last goal is eligible for reacquisition; a different goal or active unrelated controller is not.

Monocle's concrete temporary REQUEST_PAUSE controller is also distinct from a takeover. An intact owned goal waits through it. Cleanup releases owned paused or cleared navigation without cancelling foreign work.

Connected hopper sources use their verified outlet and deposits use their input. Ambiguous or changing connections retain independent safety checks; crew leases must not silently collapse independently assigned sources into a shared outlet. Wrong-kit outlets can produce bounded deferral rather than fatal whole-job failure or false exhaustion.

A reconciled missing carried kit with no pending click becomes an explicit missing outcome, not a fabricated deposit. Uncertain clicks retain inspection requirements. Tests cover ownership, pauses, hopper deferrals, Lua accounting and cancellation. Use a fresh captured workflow when receipt semantics change.
