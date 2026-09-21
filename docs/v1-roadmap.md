# Road to Monocle 1.0

Monocle's identity is coordinated automation: workflows, workers, inventories,
stashes, and an operator interface that explains what is happening. Meteor
attribution and GPL notices remain prominent throughout.

Each stage produces a versioned test JAR. Feedback can interrupt the next stage;
unfinished work stays experimental. Version numbers below are milestones, not
deadlines. Client hosting and standalone hosting must support the same operations.

## Stage 1 — 0.8.0: Configuration visibility

- [x] Inspect a job's captured profiles and module settings in both host interfaces.
- [x] Distinguish captured configuration from pending/accepted/rejected live requests.
- [x] Explain overlay semantics: unspecified settings retain worker values;
      captured profiles are not a complete live-state readback.
- [x] Expose captured native supply capabilities independently of module toggles.
- [x] Produce a test JAR; verify inspection is non-mutating in automated checks.
- [ ] Operator visual/gameplay test of the inspector on both host interfaces.

Acceptance: an operator can answer which settings a job sends, what its supply
workflow permits, and whether the latest live update was acknowledged.

## Stage 2 — 0.8.x: Operator controls and configuration editing

- [x] Guided common-setting editors with explicit job/worker scope (0.8.1).
- [x] Guided editing of future-job defaults (0.8.3).
- [x] Compare received job profiles against personal/current settings; explicitly save local personal copies (0.8.2).
- [x] Compare and duplicate reusable host presets; preview changes before applying (0.8.3).
- [x] Start/Pause/Resume/Cancel/Detach controls and ownership explanations in both host interfaces (0.8.4; operator smoke test pending).
- [x] Preset-based launch and management of ordinary highway jobs without API/assistant access (0.8.4).
- [x] Preserve WebUI inspection drafts, selections, expansion and scroll; update in-game management controls in place (0.8.4).
- [x] Add on-demand actual worker configuration readback before labeling values "effective" (0.8.2).

Acceptance: a fresh operator can launch, modify, pause, and cancel a job from
either host UI, and distinguish a queued request from successful application.

0.8.1 is the first slice, not completion of this stage. Open a job's inspection
view and choose **Edit live job settings** in either host interface. Select one
worker or all unfinished workers, preview, then apply. Numbers are proposals,
not live readings. Acknowledgements refresh without rebuilding the editor.
The guided speed ranges are deliberately bounded; the existing advanced JSON
editor remains available. Flight mode, acceleration, food protections, and
future-job defaults are not changed by these numeric edits.

0.8.2 adds **Compare personal / host / actual settings** inside the configuration
inspector in both host UIs. Choose a worker, captured profile (or latest live
request), and module; request a fresh snapshot. Comparisons are explicit,
timestamped reads, not continuous telemetry or a merged desired-state policy.
Only the most recent live patch is retained. Missing legacy baseline settings
are unknown, not inferred defaults. Remote workers must run 0.8.2+.

Workers can open their received job's **Inspect configuration & supply
capabilities** even though host-only task controls remain unavailable. Under
**Save captured profile as personal copy…**, choose a captured profile and a
new name. This creates an independent local module profile, resolving omitted
settings against personal settings, without applying it. Host control APIs
cannot save or overwrite workers' personal profiles. Received profiles remain
job checkpoints, not automatically imported personal profiles.

Operator smoke test: request Speed readback, change speed through the guided
editor, wait for acceptance, refresh the comparison against the latest live
request, and confirm actual speed agrees. Switch workers/sources while a
readback is pending; old responses must not replace the new selection. Save a
personal copy, verify the active job keeps running, reject a duplicate name,
and check that the copy survives a client restart without changing contents.

## Stage 3 — 0.9.0: Right Shift workspace

0.8.4 closes the main operator-control slice. Validate safe native detach/rejoin,
whole-job pause/resume, preset dispatch and preservation of drafts on both actual
host interfaces before redesigning navigation. Native detach retains required
duties and a site anchor; it is not force-removal or abandonment of supplies.

- [ ] Persistent navigation for Modules, Workers, Workflows, Stashes, HUD,
      Profiles, and Settings; retain the familiar module-category view.
- [ ] Search module names, descriptions, and settings; active/favorite filters.
- [ ] Module detail panels with common settings first and advanced disclosure.
- [ ] Visible movement/inventory ownership and links to the responsible job.
- [ ] Consistent spacing, typography, keyboard navigation, and non-color status cues.

Acceptance: common tasks take fewer navigation steps; small-window and keyboard
use remain practical. Test layout before replacing the existing default view.

## Stage 4 — 0.10.0: HUD and notifications

- [ ] Reusable Activity, Crew, Supplies, Navigation, and notification panels.
- [ ] Quiet healthy state; actionable recovery/error details.
- [ ] Explicit stale/estimated resource information and observation timestamps.
- [ ] HUD snapping, alignment, anchors, scaling, preview data, and presets.
- [ ] Notification grouping, deduplication, and inspectable history.

Acceptance: Highway Operator and Minimal presets are readable at different GUI
scales without obscuring chat or gameplay.

## Stage 5 — 0.11.0: Autonomous logistics

- [ ] Finish stash scan → home → inventory/echest refill → TPA return.
- [ ] Respect per-worker home cooldowns and configured teleport warmups.
- [ ] Stable managed inventory that protects unrelated belongings.
- [ ] Combine resource needs into fewer stops; reliable donor travel and pickup.
- [ ] Explain planned retention, disposal, and resource exhaustion.

Acceptance: a crew completes repeated stash runs and continues through individual
worker departures without losing job ownership or leaving unverified road gaps.

## Stage 6 — 0.12.0: Performance and unattended reliability

- [ ] Batch paving item switches; preserve valid slow-block mining progress.
- [ ] Opportunistic speculative paving and safe spare-budget work during mining.
- [ ] Measure configuration/verification latency before changing coordination.
- [ ] Pace confirmation retries; correct mining progress under changing conditions.
- [ ] Replay server restarts, full inventories, missing containers, exhausted
      resources, cancellation/reconnect, and worker departures.
- [ ] Persistent incidents, diagnostic export, and one external alert integration.
- [ ] Repeated five-hour unattended runs with measured completed-road throughput.

Acceptance: bounded recovery or a clearly reported failure for every tested fault;
no unresolved permanent waits, silent road gaps, or unsafe cancellation.

## Stage 7 — 1.0 release candidates

- [ ] Config migration/rollback tests and documented supported dependencies.
- [ ] First-run guidance for solo use, workers, and both host options.
- [ ] Audit defaults, tooltips, terminology, licensing, and Meteor attribution.
- [ ] Release artifact validation and installation/upgrade instructions.
- [ ] Document remaining experimental features and known limitations.
- [ ] Resolve release-blocking regressions from staged user testing.

## Stretch goals, not 1.0 gates

- Launcher account integration, launching workers, and local log viewing.
- Embedded game windows and headless operation.
- Public Workers API stabilization and cross-host worker sharing.
- Additional inherited-module overhauls when they serve a concrete use case.

## Test ledger

| Build | Scope | Automated checks | Operator result |
|---|---|---|---|
| 0.8.0 | Configuration visibility | Clean build, shared report tests, authenticated API, JS syntax passed | Pending; browser automation unavailable; live host unchanged |
| 0.8.1 | Guided live job setting edits | Clean build, catalog validation, NBT overlay preservation, authenticated preview API, JS syntax passed | Pending; live host unchanged |
| 0.8.2 | Worker readback and local personal copies | Clean build and JS syntax passed; chunk correlation/timeout/isolation/Unicode, comparison values, copy preservation and authenticated API checks passed | Pending; live host unchanged |
| 0.8.4 | Worker/crew management and preset launch | Clean build; authenticated detach/rejoin and rapid native handoff checks; browser controls, draft preservation, retry identity, stale gating and mobile layout | Live Minecraft testing pending; live host unchanged |
