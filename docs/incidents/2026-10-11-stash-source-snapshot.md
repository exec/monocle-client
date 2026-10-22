# Idle source snapshot mismatch

## Observed

Unbans on client 0.14.9 failed job `af2c444f-62ce-470b-b76b-f4ce10a2f180` (Build, stone, chests/hoppers, Tango2 → main), at source `[coordinates redacted]`:

> Source inventory did not reconcile with its server snapshot; no further kit was moved.

Worker run `04368d1c-7e52-4d54-b097-73bb7cd3f03a` retained four exact Build-kit pickup receipts. No withdrawal, deposit or discard was pending. The failure occurred at the pre-click guard, not an uncertain click. Recovery job `0c950ff3-9b1a-4180-9cd3-c47e1182e5d9` first deposited those four carried kits, then resumed the same captured round-robin source plans. The worker reached fetching again on its existing client.

## Diagnosis

Both native stash executors cached full container-content packets but ignored server slot-update packets. Their next-click guard compares the entire local menu against that cached authoritative inventory, including the player's inventory. Hopper changes or unrelated player-slot changes can therefore make the idle baseline stale. The old code requested resynchronization repeatedly and then failed without reopening the idle menu or deferring the source.

The prior failure did not log differing slots, so the exact server/local discrepancy in this occurrence cannot be proven. This is a confirmed code-path gap and a plausible cause, not proof that a specific hopper update or food consumption caused this failure.

## Change and boundaries

0.14.12 reuses the existing client-thread slot-update event for both pickup and deposit. A full snapshot must seed the current menu first. Updates are bounded to that menu's slots and ordered with the existing wrap-aware menu state check. Updated stacks are detached from packet/local prediction ownership.

These deltas only refresh idle baselines: they do not confirm, replay or discard a pending move, bulk transaction, interrupted deposit or held cursor. Those retain full receipt reconciliation. Closed/unread menus cannot be seeded by deltas.

After 20 mismatching ticks with empty server/local cursors and no pending move, the idle executor can close/reopen twice. Each opening needs a fresh full snapshot. Repeated unresolved until-empty solo Carry sources use the existing deferred-source/revisit policy and preserve confirmed pickups; they are not reported as empty. Fixed-count requirements and unsafe cursor/discard cases retain guarded failures. Destination retry exhaustion retains the carried kits rather than claiming a deposit.

Recovery warnings log differing slots, carried counts, menu state, snapshot age and coordinates, without dumping full kit contents or credentials.

## Verification

Offline checks exercise delta ownership, unread/stale/out-of-range rejection, state rollover, idle-only routing, pending pickup/deposit exclusion and the bounded empty-cursor reopening policy. The restarted live job is on the old client; it does not validate this patch. Install 0.14.12 for live validation.
