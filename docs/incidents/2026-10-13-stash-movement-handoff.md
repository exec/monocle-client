# Stash movement handoff — 2026-10-13

The Tango scarce-supplies rotation (`bde567d9-9e1d-43b3-8965-0ac5564e4b7a`) failed while collecting wood at Tango2 after its preceding 34-kit redstone deposit was confirmed. The worker reported `Another feature took movement control`, then safely completed failure cleanup. Its last source progress was `Collecting stash shulker 25/34`; a recent collision was attributed to `flyIntoWall`, not another player. The original detail does **not** identify the actual replacement input, so attribution of this historical event to Baritone remains an inference.

The installed Baritone input override replaces the player's input whenever movement keys remain forced, even if no path is active. Its `PathingBehavior.cancelEverything()` clears forced keys through its executor-cleanup path only when an executor existed. Closing an owned completed goal could therefore leave residual input that races the worker's next flight lease.

0.14.36 clears forced keys only when releasing our retained goal with no foreign active controller and no remaining path executor. It restores only Baritone's exact idle input; it does not overwrite other custom controllers. Worker movement acquisition closes its own stash navigation first, then waits for safe path cancellation before taking input. Genuine replacement failures include the actual input class and player/action context in both the local warning and host-visible detail.

Regression checks cover ownership/type guards, preservation of active/foreign/custom input, and production call ordering. Live reproduction of the historical failure is still needed. The restarted transfer was launched on the existing client before this patch; no client or host restart was performed while it worked.
