# Workflow supply recovery (0.7.8, first stage)

`RecoverSupplies` is a native workflow action, available as `bot.recover(args)` in Lua and as **Common Tasks / Recover supplies** in the workflow library. Duplicate the built-in task to customize it. Built-in highway programs yield this action before yielding their Highway action. Bundled programs/policies travel from either host to workers; no launcher is required.

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

`searchRadius` accepts 4–32 blocks; `flyBeyond` accepts 4–32 blocks (default 8); `retryTicks` accepts 20–1200 ticks (default 100). Flight uses existing safe Travel/ElytraFly behavior and its running fallback. Recovery does not place or replay a dropped item. A Highway action also performs mandatory recovery on restart; a script cannot bypass unresolved ownership by omitting the explicit recovery step. Its `recovery` argument supplies the same policy.

Bot restocking writes `workflow-supply-recovery.json` before container placement and breaking. Records include account, server/dimension, workflow/execution, location, serialized item identity, expected recovery count, inventory baseline and an observed drop UUID where available. Shulker matching retains color and custom name; contents can legitimately change during restocking. Both members of a placed ender-chest pair are recorded separately. Normal confirmed pickup retires the corresponding obligation.

Recovery scans loaded terrain near recorded sites, approaches owned containers/drops, preserves unrelated containers, and requires fresh server inventory plus resolved world evidence before finishing. Air alone or an unloaded chunk does not prove pickup. Cancellation stops the action without deleting its obligations. A recovery worker can remain detached while other workers cover the highway; restoration uses the same execution and fresh verification rather than recreating a job.

## Current boundaries

This first stage does not reconstruct missing identities from old journals, automatically reconcile old uncertain peer-transfer receipts, import ender-chest farming into this ledger, reuse arbitrary nearby ender chests, or divide a placed shulker's contents between multiple recovering workers. Missing inventory capacity or a usable tool keeps the obligation pending. Host-process recovery remains inspection-gated. These are explicit remaining stages, not cases treated as successful recovery.

## Banter Mode

**Config / Bots / Banter Mode**, disabled by default, makes the worker that received fewer items from a witnessed shulker-pickup incident say `OtherWorker Fuck you!` in public Minecraft chat. Only known crew members qualify. Equal shares do not trigger it. The worker waits two quiet seconds to tally observed pickup packets, deduplicates the drop UUID, and limits public messages to one per 30 seconds. Turning the setting off also suppresses queued messages. It does not enable looting or weaken resource ownership checks.
