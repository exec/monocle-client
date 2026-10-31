# Worker reliability notes

These are technical summaries of failure modes and recovery boundaries. Private operational originals, player identities, job IDs, inventory reports and locations are kept outside the repository. These summaries are not live telemetry or a guarantee that every case has been reproduced after later changes. Use invented synthetic inputs for reproductions.

- [Crew transfer catalog starvation](2026-10-06-crew-transfer-catalog-starvation.md)
- [Crew pickup confirmation](2026-10-06-crew-pickup-confirmation.md)
- [Scan catalog stutter](2026-10-08-stash-scan-catalog-stutter.md)
- [Incidental crying-obsidian kit during pickup](2026-10-09-stash-incidental-kit.md)

## Rejoin and verification

- [0.7.29 rejoin and predictions](0.7.29-rejoin-and-predictions.md)
- [0.7.30 return verification gap](0.7.30-return-verification-gap.md)
- [Manual supplier rejoin](2026-09-14-manual-supplier-rejoin.md)
- [Restored supplier rejoin](2026-09-14-restored-supplier-rejoin.md)

## Restocking and placement

- [Owned eating and combat path pauses](2026-10-13-owned-path-pauses.md)

- [Baritone ownership and deferred cancellation](2026-10-13-baritone-ownership-handoff.md)

- [Top-chest flight and walking handoff](2026-10-13-stash-top-chest-flight.md)

- [Initial shulker restock](2026-09-13-initial-shulker-restock.md)
- [Pickaxe exchange](2026-09-13-west-highway-pickaxe-exchange.md)
- [Independent restocking](2026-09-14-independent-restocking.md)
- [Shared restocking](2026-09-14-shared-restocking.md)
- [Supply placement arrival](2026-09-14-supply-placement-arrival.md)
- [Supply placement clearance](2026-09-14-supply-placement-clearance.md)
- [Supply prediction packet guard](2026-09-14-supply-prediction-packet-guard.md)

## Recovery boundaries

- [Recovery area and disconnect](2026-09-14-recovery-area-and-disconnect.md)
- [Recovery startup detour](2026-09-14-recovery-startup-detour.md)

## Stash transfers

- [Escape left a stale source menu ID](2026-10-13-stash-source-menu-escape.md)
- [Stash walking-to-flight input handoff](2026-10-13-stash-movement-handoff.md)
- [Wool withdrawal burst and unread-source timeout](2026-10-10-wool-withdrawal.md)
- [Completed deposit timed out awaiting its host receipt](2026-10-10-stash-deposit-ack-timeout.md)
- [Removed source chest survived a rescan](2026-10-09-missing-stash-source.md)
- [Withdrawal reconciliation](2026-10-09-stash-withdrawal-reconciliation.md)
- [Deposit reconciliation](2026-10-09-stash-deposit-reconciliation.md)
- [Changing hopper connections during pickup](2026-10-08-stash-hopper-connection-change.md)
- [Stash flight stall and interrupted home](2026-10-07-stash-flight-and-restart.md)
- [Baritone cleared-goal deposit failure](2026-10-06-stash-baritone-cleared-goal.md)
- [Deposit packet before destination opening](2026-10-06-stash-deposit-before-opening.md)
