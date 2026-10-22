# Top-chest jumping / walking handoff

On 0.14.37 the user reported a persistent jumping loop approaching a top chest and cleared it by relogging. Run `fdba54fe-2648-4a2a-9c51-9abf05aa907d` reported a Main deposit target `[coordinates redacted]`; a captured diagnostic had an airborne, non-gliding worker around Y=238 and detail `Waiting to reach the ground before yielding the task`. The exact Baritone movement at the original loop was not recorded, so that historical cause is not proven.

Source inspection found three concrete integration gaps: same-height flights required more than 64 horizontal blocks, vertical flights were limited to 24 horizontal blocks, and a failed interaction route immediately selected action-wide walking. Takeoff retries also had no limit. These paths could ask a no-building/no-mining Baritone lease to reach the top of a tall storage column.

The Meteor Baritone source was cloned into `~/Developer/baritone` for reference, not modified or bundled. `GoalGetToBlock` is an adjacency goal at the target elevation; it is not an opening-reach or visibility goal. `CustomGoalProcess` clears its current goal both on arrival and failed calculation. Monocle's lease recognizes that cleared goal and may request another path. Without executor diagnostics, repeated ascent/repath attempts were indistinguishable from useful travel.

0.14.38 prefers flight beyond interaction distance or for elevated destinations, counts failed takeoffs/route/clearance attempts per target, and falls back after three. All four stash approach callers share the change. Collision-reported walking recovery and inventory safety remain unchanged. Opening-reachable chests are still handled before navigation.

Host-readable walking detail now includes movement type, destination, forced jump and request count. Checkpoints retain additional Baritone goal/controller/path progress plus flight retry/launch/landing state. This should identify the concrete controller on a recurrence; it is not a claim that the original loop was reproduced.

Validation: helper and production-wiring checks cover the flight threshold, three-attempt budget, all shared approach callers and the diagnostic API calls. Live top-row takeoff/landing behavior still requires testing after installing the new client.
