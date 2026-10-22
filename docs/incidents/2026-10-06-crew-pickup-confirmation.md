# Crew pickup confirmation failure

Task `6f1b5f9c-39e9-4020-b1f8-9e5c8476a8b5`, `zungle1` to `newstorage`, Unbans leader, UnbansBot and iotacism followers.

After correcting catalog starvation, all three reached the source. The first two kit types were already exhausted by previous transfers. On `TSR Build Kit V.3` (`2dd5502e-731b-3e8b-91cc-442eac05dd43`), Unbans confirmed 35 pickups. Both followers recorded three pickups, then failed with `Source kit vanished without a server-confirmed inventory pickup`. Unbans subsequently failed at the crew barrier because a follower needed attention. No delivery was confirmed.

The saved follower native frames retained `withdrawPending: true`; receipts are lower bounds, not proof of current inventory counts. Their assigned source coordinates were distinct. The previous verifier threw immediately when a full contents packet lacked a matching source stack but did not yet show the carried gain. It also started predicted shift-clicks without the local/server baseline reconciliation already required by deposits. These are reproducible verification weaknesses. The old telemetry did not retain the triggering packet's source/destination/count/cursor values, so its exact ordering is not proven.

0.13.39 uses the existing forced, non-predicted two-click move in both directions. Pickup requires a reconciled baseline and a receipt showing a changed source slot, exact destination stack, one matching-kit gain and empty cursor. Intermediate or late negative snapshots wait within the existing 60-tick verification deadline; no uncertain click is replayed. Pending checkpoints include verification diagnostics. Confirmed receipts remain confirmed until consumed even if a late packet arrives.

Next live run must deposit carried loads first. Use per-worker `task-stash-transfer` recovery with `depositCarried: true, depositOnly: true`, targeting `newstorage` and selecting the V.3 kit above. Determine quantities from current worker inventory; do not assume the followers carry exactly their three confirmed receipts. Recover separately before starting another crew transfer. Keep the pure obsidian kit excluded from that transfer. Unbans maps the destination to `/home newstorage`; followers can TPA there to avoid relying on their separate home names/cooldowns.

Assertion checks cover intermediate cursor snapshots, stale negative receipts, exact component/count mismatch, duplicate confirmations, shared move dispatch and recovery completion without new pickup. Live validation remains pending installation.
