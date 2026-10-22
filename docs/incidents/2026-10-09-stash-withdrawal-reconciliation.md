# Tango obsidian withdrawal verification timeout

## Evidence

Job `eb388c77-f7b3-414f-a7d2-cd9a761185cb` failed withdrawing after 18 confirmed pickups. Its unchanged carried count, empty cursor and empty destination did not confirm the next move; the source still matched the expected kit. It was canceled and replaced at the operator's request.

The replacement job `1d30cd3b-878d-42e8-a208-46c422fe942e`, run `55f195d3-73b3-4ee8-91da-977d0ae01fa4`, failed after 28 pickups at `[coordinates redacted]`. State ID 16 showed source slot 36, destination 83, carried count unchanged at 28, empty cursor, and destination air. This time `sourceMatchesExpected: false`; `sourceMayRefill: true`. A source-change race, including hopper movement, is consistent with this evidence but not established as the server's precise cause. No unconfirmed pickup was counted.

## Change

0.13.58 captures the full menu and its 36-slot player inventory before pickup. Pending verification now runs before approach/chunk/container gates, holding position and retaining its deadline. After 20 ticks, unchanged player inventory with an empty cursor or the exact expected held kit permits a same-source close/reopen barrier, at most twice per unsuccessful pickup.

Only a different newly opened menu with authoritative contents can reconcile the move. Unchanged player inventory and an empty cursor prove an unapplied pickup and permit reselection from current source contents, not a blind replay of the old slot. Changed source contents do not by themselves mark the chest empty. The retry counter survives reselection and resets on an actual pickup or a new source.

Closing a held withdrawal kit returns it to player inventory rather than back to the chest. A fresh snapshot proving exactly one expected kit gained in a previously empty player slot, with the rest unchanged and cursor empty, can confirm that pickup at its actual slot. Ordinary sources additionally require source removal or the previously observed exact held kit. Wrong kits, unrelated inventory changes, old-menu packets and ambiguous outcomes cannot authorize replay. Pending restart checkpoints remain inspection-only.

Diagnostics record snapshot/menu, source/destination, cursor, retry count and reconciliation outcome through existing worker status and local logs. No host or workflow change is required. The failed replacement job was left untouched while preparing the build.

## Verification

Offline regression checks exercise source movement, fresh-menu binding, late-packet rejection, unchanged inventory reselection, exact cursor return, ordinary-source provenance, unrelated inventory rejection, retry bounds, immutable confirmed receipts and pending-checkpoint refusal. Live server behavior requires testing on the updated client.
