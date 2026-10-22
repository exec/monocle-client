# Tango obsidian deposit verification timeout

## Evidence

Job `cf295534-4c86-4144-bdd6-5eb1fbceb9c1`, run `69a1122e-d648-4415-aa2b-4fe55bad8f66`, stopped depositing into `main` at `[coordinates redacted]`. The saved workflow had 607 completed earlier deliveries, 525 completed in its current source part, and 18 confirmed in the pending batch: 1,150 confirmed kit deposits in total. The next move reached the 100-tick stage-1 timeout. The worker landed safely and stayed connected.

The old journal recorded `pending: true` but only the previous menu/state/cursor verification fields. Evidence was refreshed only when the matching carried count changed. It did not retain the pending source slot, current cursor and unchanged-count responses, so a rejected click versus missed confirmation cannot be established. The repair addresses this recovery/diagnostic gap, not a proven server rejection.

## Change

0.13.57 captures the server-confirmed 36-slot player inventory and source index before each kit move. Recovery uses at most two same-chest reopen barriers per kit, beginning after 20 ticks. An unchanged inventory and empty cursor, or the exact kit on the cursor with only its source emptied, permits a controlled close. Closing a held kit leaves the original source slot available for vanilla cursor return. A server-closed menu can also enter reconciliation without replaying a click.

Only a different, newly opened menu with authoritative contents, the original inventory restored, empty destination/cursor, and compatible column stock can retry. Vanilla may return the held kit to another previously empty inventory slot: only that exact one-kit relocation is allowed, with the retry targeting its actual slot. Other inventory changes reject replay. A snapshot proving the original exact destination move completes it instead. Inventory depletion without a destination receipt remains uncertain, including possible hopper drainage. Pending restart checkpoints still require inspection. No unrelated inventory items are thrown, and confirmed deposits are not counted twice.

Every accepted pending full-content response updates structured source/destination/cursor evidence. Timeouts expose compact transaction fields over the existing status channel and log the full evidence locally. No host restart or workflow migration is needed. The existing failed job was left untouched; a new client and inspected carried inventory are required before a live retry.

## Verification

Offline checks cover unchanged and cursor-held moves, exact cursor-return relocation, unrelated inventory changes, occupied destinations, fresh-menu binding, old-menu rejection, two-attempt bounds, exact receipt completion, duplicate receipts, hopper uncertainty and restart refusal. Live behavior still requires testing against the server.
