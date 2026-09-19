# Independent highway restocking — 0.7.36

## Incident

Native execution `fbc39b0c-2abc-462c-95b4-0315f7ff9f92`, workflow task
`1e65ed14-25eb-404a-bbf7-265c03679837`, was started westbound at
(-53250, 116, 0), targeting X -60000. All three workers detached because the
five-wide collective pit stop expanded one stock-out into a crew-wide restock.
Native restocking still shared a single container reservation. With no active
builders, return navigation fell back to the old supply/origin coordinate rather
than the unfinished front. Observed positions included Unbans at X -53295,
UnbansBot2 back at -53250 and UnbansBot at -53328. The operator reported workers
running away and leapfrogging. The task was cancelled; all three acknowledged
idle with cleared movement inputs. No new live job was started during this fix.

## Changed model

New assignments negotiate independent-restock capability (supply protocol 2).
Both standalone and in-game hosts use the shared coordinator. A new job refuses
mixed old/new workers before sending any native PREPARE messages.

- Only the requesting worker detaches. Collective pit-stop/top-up logic is removed.
  Remaining builders immediately cover its duties without regrouping or resetting
  their block confirmations. Each detached bot admits its own local container work.
- Containers are observations, not a global mutex. Workers report up to two placed
  supply positions. Other workers protect those physical containers/drops and their
  footing; empty areas do not stop road work. Own-container recovery takes precedence
  over overlapping neighboring supply areas. Observations survive cancellation and
  host restart in the recovery archive; fresh empty reports remove old observations.
- Native restocking owns outbound travel, opening, inventory transfer and cleanup.
  Return travel cannot start while a restock task is unfinished. Withdrawing a shared
  shulker offer cancels its visitor's movement target too. Pending road confirmations
  settle without re-entering Forward while detached.
- A returner follows visible active crewmates, otherwise a revision-matched host
  front updated once a second. That fallback expires after 60 client ticks; no fresh
  target means no continued travel. Supply sites are never return destinations.
  When every worker is detached, the verified checkpoint remains the target; the
  first ready worker resumes there without waiting for all suppliers.
- Existing builders keep their state during admission. A returning worker that has
  not accepted its new revision within 20 host ticks is detached again; it does not
  bound everyone else's work window while the handoff is pending.
- A supplier disconnect or individual failed Highway workflow no longer pauses or
  ends the whole native job. Failed workflow workers stay off duty, with their cleanup
  retained until job ending. Same-execution connection recovery resumes only that
  worker after a fresh correct-world report; deliberate pauses remain deliberate.

The existing resource policy is retained: carried supplies first, optional echest
search/farming according to the workflow, and ordinary shared donor restocking as
fallback. Ender chest contents are private; visitors use the donor's advertised
shulker, whose owner performs cleanup. An owned container/drop absent for 40 loaded
observations is logged unavailable and retried, not claimed as an inventory pickup.
Visible drops and unloaded terrain do not count as absence.

## Verification and rollout

Runnable policy and authenticated socket checks cover simultaneous restocks on
widths 3 and 5, protected-container observations, all-detached return at a nonzero
checkpoint, dropped rejoin acknowledgments, continued progress with an offline
supplier or failed worker, cancellation/reconnection, durable recovery archives,
stale targets, invalid observation coordinates, and mixed-protocol refusal.
Existing world-change, 100k job, shared-resource, web transport and workflow checks
remain in the test run. Game behavior in socket tests is simulated: physical
flight, inventory transfers and placement still require a live Minecraft retest.

Install 0.7.36 on every worker and use the matching host. Replace the cancelled
test job after updating; do not replay its historical recovery workflow. Standalone
Highway Builder (no bot assignment) keeps its existing behavior. No dependencies
or alternate movement engine were added.
