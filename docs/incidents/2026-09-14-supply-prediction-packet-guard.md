# Supply placement canceled by its own predicted container

Observed on the westbound crew test before 0.7.44: UnbansBot2 reported
`Restocking: placement interaction not sent at -81958, 116, 0`, then
`Waiting to verify canceled placement`. Its snapshot had `owned=false`,
`placementSent=true`, `pendingPlaceCount=0`, `predictionFlush=true`, and no
tracked drop. The user confirmed shulkers appeared to be placed locally but
never appeared in the server world.

## Cause

Minecraft's `MultiPlayerGameMode.startPrediction` runs the local prediction
before calling `ClientPacketListener.send`. HighwayBuilder sets `placingTarget`
around that operation, but Restock sets `placementSent` only after it returns.
During `PacketEvent.Send`, SwarmCrew sees the locally predicted shulker inside
an overlapping peer supply area. Its own-container exemption previously only
used Restock's later flags, so it canceled its own placement packet. The same
ordering affects the second ender chest.

## 0.7.44

- Include the synchronously validated in-flight supply target and its footing
  in the local ownership exemption. Existing peer containers are checked before
  prediction; neighboring cells remain protected during the send.
- Require server resolution before promoting a predicted container to owned.
- Restore canceled-prediction reconciliation removed in 0.7.43. Skipping that
  correction did not fix the canceled packet and could promote a ghost to owned.
- Report the crew packet-block reason, latest placement send, and probe timing
  in worker diagnostics. Preserve walking's specific blocker instead of replacing
  it with the generic stepping-clear status.

Validation: `highwaySupplyCheck`, `swarmCrewCheck`, `jar`, and
`bundledLibrariesCheck` passed. The regression reproduces the before-send
prediction with overlapping supply areas, verifies the packet's target/footing
exemption, and checks that the exemption ends after the callback and preserves
neighboring container protection. Minecraft bytecode confirms the prediction/send
ordering. Full in-game verification requires installation on the Windows workers;
they disconnected during this investigation. No recovery records were discarded.
