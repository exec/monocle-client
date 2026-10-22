# Escape left a stale source menu ID — 2026-10-13

Unbans's no-elytra rotation (`d97f3691-239f-44de-9169-8fae7c27a917`) stopped making progress while withdrawing chest/hopper kits at Tango2. The user reported pressing Escape while a container was opening/open. The host showed `Collecting stash shulker 2/34`, `Container contents`, a roughly 92-second progress age, menu 44, empty cursor and a ready, healthy player. The job was cancelled at the user's request; it was not automatically restarted.

`BotStashResupply` retained its old `menu` after an external close. Its normal opening path would request another opening, but both `acceptMenu` and the tick loop rejected a different menu ID; the mismatch branch returned without a deadline. Destination deposits already reset an idle closed/replaced binding, and read-only scans already update their opening binding and have a read deadline.

0.14.37 shares snapshot invalidation between explicit source closure and idle external closure. With no pending script transfer and an empty current cursor, the old menu/snapshot/opening marker is forgotten, a replacement menu is closed, and the intended source is opened afresh. Late old-menu packets cannot satisfy the new opening. Confirmed receipts and source selection remain intact. Pending pickups/bulk transfers use their existing read-only reconciliation rather than replaying uncertain clicks; ender-chest/discard confirmations and unresolved mismatches remain bounded.

Regression checks simulate Escape to the player inventory, an already-reopened different menu, stale snapshot/cursor data, preserved receipts, and pending single/bulk operations. In-game Escape reproduction still needs testing on the updated client. No rescan or host update is required.
