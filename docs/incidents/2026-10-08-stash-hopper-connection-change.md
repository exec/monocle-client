# Stash withdrawal stopped by changing hopper topology (0.13.52)

## Observed

Unbans transferred from `tango2` to `main` under job `34c18599-f2bd-4760-8282-14d95839ad86`. During the beacon/build-kit stage, the worker reported `Hopper connection changed; source exhaustion is not verified` at `[coordinates redacted]`. It delivered its seven confirmed pickups before failing. The requested immediate retry, job `7fc8188d-779d-44f8-a927-0c25ab0c3da9`, hit the same guard with zero pickups. Neither failure was a home cooldown problem.

The native withdrawal compared a cached physical hopper column against a new world lookup and turned any difference into a source failure. The delivery workflow correctly stored confirmed partial loads, then failed on that source issue. Old telemetry did not include both column lists, so an actual layout change versus newly visible blocks cannot be distinguished for these attempts.

## Change

Shared `BotStashResupply` logic now refreshes the live column, updates its opening target, resets its refill/navigation wait, and closes/reopens the container for fresh server contents. Three consecutive no-progress refreshes bound a flapping column; confirmed pickup progress resets that budget. A repeatedly unstable kit source is recorded as deferred and the worker moves to the next candidate. Existing multi-kit workflow handling deposits the batch, processes other kit types, and retries deferred types once.

Losing a previously known upstream link does not establish an empty source. That uncertainty is retained through subsequent pickups and checkpoints and becomes a deferred source if no matching kit remains accessible. Rechecking cannot discard an unconfirmed inventory move or redirect a crew-local source lease to another chest. Ordinary inventory verification remains unchanged.

Logs now record both old and new physical chest lists. Native checks exercise newly visible links, lost links, refill reset, bounded retries, checkpoint compatibility, pending-click refusal, lease safety and deferred result semantics. `botsCheck` and the full `build` passed. No coordinator runtime restart is needed; workers require the new JAR. Truly unresolved sources may still yield a diagnosed failure after workflow retries rather than a false claim that the stash is empty.

## Deployment

The operator requested remote installation on the transferring worker, Unbans. The previous JAR is retained outside `mods` in the profile's `monocle-client/jar-backups` directory. The new local JAR SHA-256 is `f21ec182c94d675f6841db265a775ad1310e2f587e640fff0d28d4fd60d83dc2`; installation verifies the staged and installed copies. The other Minecraft instance and running host are not restarted. Live recovery evidence should be appended after the updated worker reconnects.

The Modrinth launch URI required the actual instance ID (`local:2fc463bb-bf1e-4ec6-961c-adb40dd5f359`), not the folder name. It selects an instance **but uses the launcher's active Minecraft account**; it launched as iotacism rather than switching to Unbans. The operator corrected that manually. Future remote launches must explicitly select/verify the account as well as the profile; do not assume the profile name establishes player identity. The temporary scheduled launch task was removed.

The restarted Unbans process's log confirmed `monocle-client 0.13.52`, and the host confirmed the expected Unbans UUID connected/reconciled in the End. Replacement job `226c7d0b-2ed4-44a0-a972-90863debd0ce` was submitted with fetching first, the same six-kit priority order, `/home tango2`, and the existing 16-second home cooldown. Long-running transfer completion is not yet verified.
