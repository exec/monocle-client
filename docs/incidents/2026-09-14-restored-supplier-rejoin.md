# Restored suppliers waiting for crew synchronization — 0.7.41

Execution `07b4807a-3ff6-456b-bfaf-aaa3f74c3b73` resumed after the worker
upgrade. All three fresh worker reports showed detached/returning, state Center,
no travel target, no placement/break confirmations pending, no active restock
session, and no resource request (`exchange.need = -1`, stage idle). All were
near the job origin. The initial donor-shortage explanation was incorrect;
the speculative resource-pool change was removed before packaging.

Two client defects prevented restored returners from becoming ready:

- Assignment restoration set a front position without stamping its freshness,
  so it was immediately rejected by the existing destination expiry check.
- Startup enters Center, but rejoin readiness required Forward. The return wait
  deliberately prevents normal road work, so Center could never reach Forward.

Restoration now stamps its received assignment position, and rejoining accepts
Center or Forward only after the existing restock, cursor, footing and cleanup
checks. The normal completion of rejoining establishes the new work origin and
enters Forward. Supply/recovery states still cannot bypass cleanup.

The UI now names missing host destinations rather than overwriting the reason
with the generic synchronization label. Shared coordinator telemetry and the
standalone API expose each supplier's return gate and revision inputs.

The live host had no recent service-front commands despite reporting returning
workers. Existing telemetry did not capture all inputs needed to identify that
separate discrepancy. The new authenticated socket regression holds all three
workers detached, requires repeated front updates before landing, and checks
that only the ready worker rejoins. It passes for widths three and five.

The live task was paused for the upgrade. No journals were discarded and no
resource recovery was marked successful by an operator command.
