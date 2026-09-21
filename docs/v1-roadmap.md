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

- [ ] Guided common-setting editors, with explicit job/worker/future-job scope.
- [ ] Compare and duplicate presets; preview changes before applying.
- [ ] Consistent Start/Pause/Resume/Cancel/Detach actions and ownership explanations.
- [ ] Start and manage every ordinary highway job without API/assistant access.
- [ ] Preserve drafts, expanded sections, selections, and scroll during updates.
- [ ] Add actual worker configuration readback before labeling values "effective".

Acceptance: a fresh operator can launch, modify, pause, and cancel a job from
either host UI, and distinguish a queued request from successful application.

## Stage 3 — 0.9.0: Right Shift workspace

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
