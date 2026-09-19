# Workflow supply recovery

`RecoverSupplies` is a native workflow action, available as `bot.recover(args)` in Lua and as **Common Tasks / Recover supplies** in the workflow library. Duplicate the built-in task to customize it. Since 0.7.35, generated highway programs start their Highway action directly; historical recovery is an explicit workflow, not an automatic startup detour. Bundled programs/policies travel from either host to workers; no launcher is required. The following example explicitly opts into recovery before building:

```lua
return function(ctx)
  if not ctx.state.recovered then
    ctx.state.recovered = true
    return bot.recover({searchRadius = 16, flyBeyond = 8, retryTicks = 100})
  end
  if ctx.state.started then return bot.done() end
  ctx.state.started = true
  return bot.highway(ctx.args)
end
```

`searchRadius` accepts 4–32 blocks; `flyBeyond` accepts 4–32 blocks (default 8); `retryTicks` accepts 20–1200 ticks (default 100). Flight uses existing safe Travel/ElytraFly behavior and its running fallback. Recovery does not place or replay a dropped item. Native highways retain their own live container cleanup; starting a new Highway action does not implicitly travel to unrelated historical records.

Since 0.7.39, `searchRadius` also bounds which journal entries this action may
recover. The worker saves its starting position as the search origin before
moving; walking, pausing and restarting do not move that origin. Supply records
outside the area remain saved, and completing a nearby pickup never selects a
farther historical site. Supply approach and drop targets stay inside the area.

To recover at a specific worksite, supply all three coordinates. Optionally
provide the native highway **execution ID** to restrict recovery to that job:

```lua
return bot.recover({
  x = ctx.args.x, y = ctx.args.y, z = ctx.args.z,
  execution = ctx.args.execution,
  searchRadius = 16, flyBeyond = 8
})
```

An explicit origin can intentionally be far from the worker. The trip remains
bound to that selected site. Omit `execution` to inspect any of the account's
own records inside the area. The policy is carried in the workflow from either
host type; workers need 0.7.39 or newer. Earlier versions ignore these filters.
Pause/cancel requests and connection/world loss release nested recovery travel
input immediately; existing landing and outstanding-transfer cleanup still run.

Bot restocking writes `workflow-supply-recovery.json` before container placement and breaking. Records include account, server/dimension, workflow/execution, location, serialized item identity, expected recovery count, inventory baseline and an observed drop UUID where available. Shulker matching retains color and custom name; contents can legitimately change during restocking. Both members of a placed ender-chest pair are recorded separately. Normal confirmed pickup retires the corresponding obligation.

Recovery scans loaded terrain near recorded sites, approaches owned containers/drops, preserves unrelated containers, and requires fresh server inventory plus resolved world evidence before finishing. Air alone or an unloaded chunk does not prove pickup. Cancellation stops the action without deleting its obligations. A recovery worker can remain detached while other workers cover the highway; restoration uses the same execution and fresh verification rather than recreating a job.

## Current boundaries

New 0.7.36 bot assignments use [independent restocking](incidents/2026-09-14-independent-restocking.md), shared by both host types. Only hungry workers detach; no collective top-up or global container lock remains on this path. Fresh container observations protect nearby physical supplies without holding the whole crew. Native recovery may log a container unavailable after 40 loaded observations with neither the block nor a matching drop present; this is not proof of inventory pickup. Explicit historical `RecoverSupplies` retains its conservative reconciliation behavior.

Since 0.7.13, after an operator explicitly confirms a stale crew transfer is settled, a one-off recovery workflow can use `bot.recover({inspectTransfersBefore = <confirmation-time-in-Unix-milliseconds>})`. This works before native assignment, from either kind of host. It archives the original receipt and marks the transfer cancelled, never replaying a drop or claiming a server-confirmed pickup. Native jobs and transfers must be inactive. Receipts modified after the approval cutoff are refused, so resuming an old workflow cannot clear a later transfer. Omit this field from ordinary/reusable workflows. Actual container recovery obligations remain unchanged. Workers must run 0.7.13 or newer; earlier versions do not implement this option.

Since 0.7.16, an active crew exchange retries pickup and fresh server inventory confirmation using the **same transfer ID and sequence**. Recipients approach matching stacks within six blocks of the meeting point on the highway's Y level; without a visible stack they retry a short sweep toward the donor. Normal supported-route checks still apply. Partial pickup counts survive reload. The shared host does not expire an authorized drop merely because 60 seconds passed; it advances only after both sender and recipient confirm. This requires both the host and workers to be updated. A genuinely missing item or unsafe route can still require operator intervention: retries are not permission to abandon a resource or issue a duplicate drop.

Before another stack is authorized, a recipient checks whether it already has its target or newly accessible carried shulkers/echests. A changed container inventory permits another local restock attempt; unchanged, previously exhausted sources do not repeatedly cancel donations. Cancellation before a drop retains the original supply request for retry. Ending an unconfirmed issued transfer retains its receipt, even if the sender confirmed the throw. Explicitly inspected cancellations remain retired.

This first stage does not reconstruct missing identities from old journals, automatically reconcile old uncertain peer-transfer receipts, import ender-chest farming into this ledger, reuse arbitrary nearby ender chests, or divide a placed shulker's contents between multiple recovering workers. Missing inventory capacity or a usable tool keeps the obligation pending. Host-process recovery remains inspection-gated. These are explicit remaining stages, not cases treated as successful recovery.

## Banter Mode

**Config / Workers / Banter Mode**, disabled by default, makes the worker that received fewer items from a witnessed shulker-pickup incident say `OtherWorker Fuck you!` in public Minecraft chat. Only known crew members qualify. Equal shares do not trigger it. The worker waits two quiet seconds to tally observed pickup packets, deduplicates the drop UUID, and limits public messages to one per 30 seconds. Turning the setting off also suppresses queued messages. It does not enable looting or weaken resource ownership checks.
