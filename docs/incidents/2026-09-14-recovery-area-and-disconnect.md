# Unbounded recovery trip and nested movement release — 0.7.39

During the 0.7.38 test, the operator reported two workers running east away from
the westbound worksite at (-81955, 116, 0). The dispatched generic recovery task
was `e515528a-3c25-4837-a4a4-4d1b06b09c64`; it was cancelled. Its empty arguments
selected every pending journal entry for each account/server/dimension.
`searchRadius` limited the drop search around each recorded container, but did
not limit the distance to the selected journal entry. Thus even unrelated old
sites could become travel destinations. The task should have been scoped to
the supplies from native execution `9cebee5a-4fb0-4a0f-981e-f08d4c8aace5`.

The earlier report that cancellation was acknowledged by all workers was not
supported by the telemetry. The host accepted cancellation, but subsequent
inspection showed offline workers and pending cancellation delivery. Repeated
unchanged positions and forward-input values were stale observations, not proof
of continued movement after cancellation.

Code inspection did find a separate defect: `BotActions.disconnected()` released
its own input while `BotSupplyRecovery`'s nested Travel action could still own the
player input. The disconnect now reaches that child, and finish/pause/cancel
requests revoke movement before waiting on checkpoints or native cleanup.
`CustomPlayerInput.stop()` also clears the computed movement vector immediately.

Recovery now checkpoints a fixed search origin in its workflow action, bounds
journal selection and movement destinations to that area, and optionally filters
by native execution ID. Existing out-of-area records remain intact. Old action
checkpoints acquire a fixed origin before any resumed movement. Recovery travel
status now includes the actual destination coordinates.

Checks cover nearby/boundary/remote/different-job records, fixed origin across
serialization and resume, preserved excluded records, malformed arguments,
immediate input clearing and compiled calls through the nested disconnect path.
The workflow, highway supply, coordinator and bundled-library checks passed.
Workers were offline during verification; a live retest is still needed.
