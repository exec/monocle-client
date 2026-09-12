# Programmable bot workflows

Monocle includes its own Lua interpreter. **You do not install Lua, LuaJ, or a separate scripting mod.** Each worker needs the same Monocle build and a trusted connection to the host. See [Bots setup](bots.md) for connecting accounts.

## Start from the GUI

Open **Right Shift → Bots → Workflows**. **Common Tasks** contains Travel, Drop items, TPA, Wait, Run configured modules, and Set profile. Queue one directly, or duplicate a definition to edit its Lua. Custom workflows have names, folders, declared workflow dependencies, and declared profiles. The editor validates syntax on save; editing never executes a draft.

**Stash Hunting → Distributed stash hunt** is an immutable built-in program; duplicate it to customize it. The Jobs tab also has a dedicated **Create stash hunt** form. It uses the same queues, priorities and profile package as other programmable jobs. All targets receive the same bounds; host-assigned worker indices divide that area, including when a nested workflow calls the survey action.

Use **Queue workflow** in Jobs or on a workflow card. Choose a crew, its target workers, arguments, and priority. The queue supports one selected remote bot or several; each receives its own resumable execution of the program. Worker dashboards are read-only. The host controls pause, resume, cancel, and priorities.

The **Highway Builder** folder also contains native highway presets. These describe recurring excavation/paving duties and supply fallback order, not Lua programs. They remain usable by the saved highway job forms and can also be called from Lua.

## Execution model

A program returns a function. That function makes **one decision** and returns **one action**. Monocle completes the action before evaluating the function again. Store your program counter and other persistent data in `ctx.state`:

```lua
return function(ctx)
    if not ctx.state.waited then
        ctx.state.waited = true
        return bot.wait(40)
    end
    return bot.done()
end
```

Every evaluation has fresh Lua globals and closures. A local variable outside the returned function is **not** a durable counter. Do not loop while waiting for Minecraft: return an action and inspect its result on the next decision. A tight loop exceeds the instruction budget and fails the task.

| Context | Contents |
| --- | --- |
| `ctx.state` | Your durable JSON object, initially `{}`. Changes and the next action are checkpointed together. |
| `ctx.args` | Arguments supplied by the caller/host. Custom task arguments are a JSON object. |
| `ctx.result` | Previous action result: `ok`, `detail`, and action-specific fields. A child returning `bot.done(value)` supplies `ctx.result.value`. Initially `{}`. |
| `ctx.world` | This worker's `id`, `name`, `x`, `y`, `z`, `dimension`, `health`, `inventory`, and `nearby`. |

`inventory` maps item IDs such as `minecraft:obsidian` to counts in the 36 normal inventory/hotbar slots; it does not inspect shulker contents. `nearby` contains at most 64 other loaded players within 32 blocks, with their IDs, names, and coordinates. These are observations, not Java/Minecraft objects.

State must remain an acyclic JSON object. Use `bot.array()` for an empty array and `bot.null` for a JSON null; ordinary `{}` is an empty object. Functions, game objects, cyclic tables, and non-finite numbers cannot be saved. Mutate `ctx.state`, not `ctx.args` or `ctx.result`, for persistent changes.

## Action reference

Numbers below describe the Lua API. GUI forms may offer narrower ranges or prefilled values. Durations are client ticks; 20 ticks is approximately one second when the client is ticking normally.

| Action | Arguments and behavior |
| --- | --- |
| `bot.done(value)` | Finish this workflow. Optional JSON `value` is returned to its parent; no parent means the task finishes. |
| `bot.fail(reason)` | Fail with a readable reason, then safely release controls/settings. |
| `bot.wait(ticks)` | Wait `0–1,728,000` ticks. |
| `bot.call(id, args)` | Execute a declared workflow dependency with an optional argument object. Uses its **stable ID**, not its display name. |
| `bot.profile(name)` | Apply a bundled gameplay profile to subsequent actions in this task. The profile must be declared in the editor. |
| `bot.travel({...})` | Required `x`, `y`, `z`; optional `radius` (default `2`, range `0.15–8`) and `dimension`. Arrival requires safe, grounded footing. |
| `bot.stash_hunt({...})` | Required inclusive `minX`, `maxX`, `minZ`, `maxZ`, and flight `y`. Optional `radiusChunks` (default `2`, range `1–8`), `maxSpeed` (blocks/sec, default `60`, range `1–120`), `acceleration` (blocks/sec², default `4`, range `0.1–40`), and `dimension` (defaults to the host-captured job dimension). X/Z bounds are within ±29,900,000, Y within ±2048 and the dimension's actual limits, at most 1,048,576 chunks. The runtime supplies `workerIndex`/`workerCount`; scripts cannot override their partition. Scans received chunks, saves worker and host notebooks, and lands before returning. |
| `bot.drop({...})` | Required item ID `item` and exact `count` (`1–1,048,576`); optional recipient **UUID**. Drops real inventory items. A recipient must be visible and within four blocks; this action aims at them but does not chase them. |
| `bot.modules({...})` | Required `modules` object such as `{["auto-eat"]=true}`; optional `ticks` (default `0`, meaning until cancelled). Temporarily changes activation states, using configured settings, and restores them afterward. Native Highway Builder/Printer Helper cannot be started this way. |
| `bot.tpa({...})` | Required `target` (host/crewmate name or UUID). Optional `warmupTicks` (default `100`), `timeoutTicks` (default `1200`), `radius` (default `8`, range `1–16`). Timeout must exceed warmup. Uses `/tpa` and the host's matching acceptance handshake, then verifies actual arrival. |
| `bot.highway({...})` | Required `workflow`, the ID of a bundled native highway preset. Optional `length` (`16–4096`, default `128`) and `x`, `y`, `z` override the captured start. Uses the host-captured highway geometry/settings and the existing verified crew builder. |

Native action tables can include `allowFailure=true` to continue after a failed action and inspect `ctx.result.ok`. Without it, a native failure fails the whole task. This does **not** bypass safe cleanup or permit replaying an uncertain item drop/teleport.

Travel coordinates accept X/Z within `±29,999,984` and Y within `±2048`. A dimension is a namespaced ID such as `minecraft:the_nether`. Normal travel cannot cross dimensions; use a supported teleport first. The API intentionally has no arbitrary chat-command execution, filesystem access, or direct packet access.

## Nested calls and return values

Create a child workflow, copy its ID with **Copy workflow ID for bot.call**, then select it under the parent's workflow dependencies. For example, this child waits and returns a computed result:

```lua
return function(ctx)
    if not ctx.state.waited then
        ctx.state.waited = true
        return bot.wait(ctx.args.ticks or 20)
    end
    return bot.done({answer = ctx.args.number * 2})
end
```

Replace `CHILD_WORKFLOW_ID` below with that copied ID:

```lua
return function(ctx)
    if not ctx.state.called then
        ctx.state.called = true
        return bot.call("CHILD_WORKFLOW_ID", {number=7, ticks=40})
    end
    if not ctx.result.ok then return bot.fail(ctx.result.detail) end
    ctx.state.answer = ctx.result.value.answer
    return bot.done(ctx.state.answer)
end
```

The parent frame, child frame, their arguments/state/results, and current native action token are checkpointed separately. Resuming does not restart the parent or reissue a completed child action. Call nesting is limited to 16 frames; declared dependency cycles are rejected.

## Call the existing highway builder

Declare `highway-default` as a dependency. This program waits, then builds a 128-block section using the captured highway layout:

```lua
return function(ctx)
    if not ctx.state.waited then
        ctx.state.waited = true
        return bot.wait(20)
    end
    if not ctx.state.built then
        ctx.state.built = true
        return bot.call("highway-default", {length=128})
    end
    return bot.done()
end
```

`highway-excavate` and `highway-pave` are alternative built-in dependencies. Calling a native preset wraps the same `bot.highway` action; it does not implement a second builder. Multiple targeted workers must reach identical Highway actions before the host starts their shared native job. Keep those arguments independent of worker-specific coordinates/IDs.

**Join running job** on a directly assigned/queued native highway preset first transfers that task's captured workflow and profiles to the new worker, then waits for its prepared Highway action before redistributing lanes. A custom Lua program cannot currently accept a new worker midway through its Highway action: rerunning its entry could repeat earlier item transfers or profile changes. Select its workers before starting. This restriction does not affect returning workers, whose original Lua frames are already saved.

Highway source steps reuse inventory shulkers, ender-chest contents, and ender-chest farming in the preset's configured order. Native supply recovery, filler disposal, cursor cleanup, and protected container handling remain enabled. Eligible suppliers temporarily leave the active lanes for a safe rear supply site while the others continue, then rejoin in their original roster order. There is no separate Lua stash scanner or generic `bot.restock()` action in this build; calling a highway preset reuses its native restocking.

## Profiles, priority, and returning to work

The host captures workflow code, dependencies, arguments, highway geometry, and declared gameplay profiles **when queuing**. Workers receive the complete immutable package before execution. A later library/profile edit does not silently change queued work. `Current` means the host's captured current gameplay setup, not the worker's personal settings. Accounts, connection keys, chat/IRC, and unrelated profile files are not shared.

Each worker saves its personal gameplay settings before applying a task profile. A named profile applies through `bot.profile("Profile name")`; add that name to the workflow's declared profiles. It remains effective for subsequent actions, including nested calls. Personal settings return after suspension, cancellation, failure, or completion, and the task's effective settings are restored when resumed. Profile changes/handoffs wait for safe grounding and native executor release. The standalone Set profile task is consequently a short-lived preview; put it before useful actions in a program to do work under that profile.

Priorities range from `-1000` to `1000`, with optional per-worker overrides. Higher numbers win; equal priorities are FIFO. A higher-priority task may borrow selected workers from a native highway only after a coordinated, acknowledged safe withdrawal. Their source job keeps its checkpoint, preferred roster order, and return reservations. The remaining crew keeps working. The scheduler cannot remove the last on-site worker or the only worker capable of a required duty; that task waits with an explanation instead.

Workers keep their authenticated crew connection when borrowed. Their old highway's movement/frontier/disconnect barriers exclude them until they return. Finishing the higher-priority work triggers a return to the current build front, using supported travel or the crew/host TPA rendezvous when required. The old task profile must be restored before native readmission. This is not cross-crew credential reassignment.

## Safety and recovery

- **Suspending** means a requested pause/cancel/failure/completion is still waiting for safe cleanup. It is not permission to start another task on that worker.
- Pending supply recovery, airborne travel, and sent teleport requests settle before settings or controls are handed off. Disabling a movement module manually in flight may require manual safe landing.
- Item drops record intent before sending and require server inventory confirmation. Restart recovery never blindly repeats an uncertain drop. Likewise, a checkpointed TPA request is observed through arrival/timeout rather than sent twice.
- A saved uncertain drop/teleport blocks starting a different task until the original checkpoint is reconciled. Cancelling its queue entry does not erase that uncertainty.
- After a process restart, unfinished tasks require inspection and an explicit host Resume. Connection recovery alone does not abandon containers or automatically replay scripts.
- A task remains tied to its captured Minecraft server address. A different server with the same dimension name does not authorize resuming travel or reconciling an old inventory action there.
- Pause/cancel controls are host-managed; don't manually toggle worker Highway Builders during ordinary scheduling.
- Cancellation takes priority over job selection and can replace an in-progress highway regroup. Road verification is not a prerequisite for ending an execution; physical supply/cursor cleanup and safe footing still are. The cancellation intent survives reconnects, and task heartbeats continue on menus/disconnect screens. Missing executed worker checkpoints require inspection rather than automatic replay. Install 0.5.1 on all accounts for these lifecycle fixes.
- If an interrupted/disconnected native execution must be ended before its supplies can be recovered, its supply location is archived in `bot-ended-<execution UUID>-supplies.json` and a warning is shown. This is an inspection record, not a claim that containers were picked up. Do not leave the area until those supplies have been recovered.

Travel reuses Monocle's bounded walking/clearance checks and Vanilla ElytraFly autopilot when a usable elytra is already equipped. It is **not Baritone**: it will not mine a tunnel, pave a bridge, traverse arbitrary unloaded terrain, open doors, or find a portal. An obstructed/unloaded corridor waits for a safe route. Equip/configure the task's flight profile and test a clear supported route before unattended travel.

There is no `/home` primitive, chest inventory database, schematic-print task, or arbitrary game API in this first executable runtime. Those need dedicated native actions with their own recovery contracts; Lua composes the actions listed above.

## Runtime limits and storage

Lua decisions run on the Minecraft client thread with bounded work; no Lua worker threads or live coroutines are persisted. The sandbox omits `io`, `os`, `package`, `require`, loaders, Java access, debug, coroutines, and allocation-heavy string/table libraries. Available helpers include `assert`, `error`, `pcall`, `xpcall`, `pairs`, `ipairs`, `next`, `select`, type/number/text conversion, and the safe math subset `abs`, `ceil`, `floor`, `min`, `max`, `sqrt`, `sin`, `cos`, `tan`, `rad`, `deg`, `pow`, `pi`. No random API is exposed.

Limits include 32,768 source characters, 12,000 Lua instructions per decision, 32 interpreter frames, 16 nested workflow calls, 4,096 JSON nodes/depth 16, and 32 KiB each for saved state/action. Loops cannot swallow a quota failure with `pcall`. A task package includes at most 32 workflows, 17 profiles including Current, and 4 MiB of UTF-8 data. Host and worker queues retain at most 64 task records; older terminal records may be pruned to make room.

Custom definitions use `monocle-client/bot-workflows.json`; the host task journal is `bot-tasks.json`, and each worker's independent execution journal is `bot-worker-tasks.json`. Journals use atomic replacement. Do not copy live worker journals between accounts or delete them to bypass recovery: they track issued actions and temporary profile restoration as well as program state.
