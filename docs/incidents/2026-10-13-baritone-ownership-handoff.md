# Baritone movement ownership and deferred cancellation

The movement audit identified two source-backed handoff errors, not a newly reproduced live incident:

- `IPathingBehavior.isPathing()` means a path is executing and is not paused. It excludes retained paused paths and first-segment calculations. Workers used it as an ownership gate, so a temporarily inactive path could resume after worker steering started.
- `cancelEverything()` cancels controlling processes but returns false when the current movement cannot safely stop. The stash lease ignored that result, immediately restored restrictions and discarded its owner object.

0.14.39 leaves the existing walking-status API alone. Worker movement uses an ownership check covering retained paths, calculations and accepted navigation processes. Workflow profile application also waits for this ownership to end. Passive temporary pause processes do not alone mean a navigation job is active.

Stash cleanup returns an explicit completed/deferred result. On deferral it retains the lease, forbids further goals, keeps read-only settings and polls safe cancellation on client ticks. Worker steering, a new walking target, suspension and next-action preparation cannot pass that gate. The tick listener survives action teardown and unregisters once cleanup completes. Replacement goals and pending foreign navigation processes prevent blanket cancellation.

The behavioral test uses the real lease with simulated public Baritone APIs and an actual event bus. It verifies settings remain restricted across unsafe cancellation and cancelled-but-undrained calculations, then restore once; paused and calculating paths remain busy; foreign goals and newly active foreign processes survive cleanup.

The live job was not changed or restarted. Shared pause ownership, water-bucket fall restrictions and whole-action inventory protection remain separate audit findings; they are not part of this fix. No forceCancel or Baritone source modification is used.
