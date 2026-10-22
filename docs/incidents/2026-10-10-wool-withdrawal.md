# Wool withdrawal verification failure

## Observed

Unbans was running client 0.13.86 against standalone host 0.13.71. The previous Tango2 transfer failed with no confirmed pickups while approaching the upper source at `[coordinates redacted]` for 200 ticks.

The wool-only job `6dec312d-4153-4978-9cb9-8e7805a9fc9a` failed on its first load with `Bulk stash transfer remains unverified; no uncertain move replayed`. Its four exact-slot pairs were `0→75`, `30→76`, `36→77`, `38→78`, all Asaanriasa's Wool. After reopening menu 3, zero moves had been verified. This is a different failure from the upper-chest approach timeout. An earlier generated plan failed validation (`Invalid kit identity fields`) before gameplay and was corrected separately.

## Diagnosis and change

The shared burst verifier only acknowledges exact predicted inventory prefixes. Four preselected source slots on a hopper-fed container can change before all the clicks finish; unknown inventory changes also prevent prefix verification. The host diagnostics do not prove which specific change occurred, nor whether any of the four boxes actually arrived. Do not equate zero receipts with zero physical pickups.

0.14.7 rejects dynamic-source withdrawal bursts in the shared planner. Callers fall through to the existing guarded single-kit move, confirming each pickup before choosing the next. Static source bursts and deposit verification remain intact. No uncertain click is replayed or converted into success.

The earlier approach timeout now defers unread sources in until-empty solo Carry transfers, preserving unresolved diagnostics and the workflow's bounded revisit policy. It does not mark them empty or bypass fixed-count/crew requirements.

## Verification and handoff

Native tests cover planner fallback, static bursts, exact receipts, deferred-source cursors/checkpoints and pending-move guards. Live server testing remains required. Inspect any carried wool before starting a fresh fetching job; inventory from the failed burst is not accounted for by its zero verified receipts. Installing the new client does not retroactively reconcile that burst.
