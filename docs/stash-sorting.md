# Category sorting: preview and experimental write mode

The host owns the planner. A game client need not be online, and the preview
never submits tasks, reserves columns, changes categories, writes the stash,
opens containers, or moves items. From **0.15.9**, a separate explicit start
operation runs the experimental single-courier writer on the standalone host.

In the WebUI, open **Stashes → a stash → Category sorting · read-only preview**.
Choose a buffer reserve (default four columns) and carried kit slots (default
34), then request a preview. It is fetched on demand, not recomputed on every
dashboard refresh. After the first preview, select the category rules you want
included and refresh; shared rules made for another stash can be excluded without
changing their definitions. The preview remains visible until refreshed or disconnected.

## Host API

Categories have an optional `usage`: `Storage`, `Kitbot`, or `Both`. Omitted
means `Both`, preserving existing catalogs. Sorting uses only Storage/Both rules;
whisper requests and listings use only Kitbot/Both rules. A kit can therefore
have a broad storage group and a narrower request name without creating a
sorting ambiguity. Edit this in the panel's category editor under **Used for**.
The shared catalog supports 128 definitions. Existing column reservations still
honor their referenced rule regardless of usage; changing usage never moves
stock or rewrites reservations. Deposit workers receive the original five-field
placement rule, so older clients need no update for this host-side distinction.

Authenticated resource read:

```text
GET /v1/stashes/{stashId}/sort-preview?buffers=4&carrySlots=34&growthColumns=1
```

An optional `categoryIds` query parameter selects comma-separated category IDs;
omitting it selects all storage-enabled rules, and an empty value selects none.
`growthColumns` accepts 0–2; the API retains zero as its compatibility default,
while the panel and writer default to one. No other query parameters are
accepted. Limits are 1–16 buffers and 1–36 carried kit slots (a maximum; the
writer probes actual free inventory space before each load). The corresponding
private Monocle control operation is:

```json
{
  "op": "stash-sort-preview",
  "crew": "crew-id",
  "scope": "example.org\nminecraft:the_end",
  "name": "main",
  "buffers": 4,
  "carrySlots": 34,
  "growthColumns": 1,
  "categoryOrder": [],
  "expected": "optional-current-stash-revision"
}
```

`categoryIds`, when omitted, uses all storage-enabled rules; an explicit empty array deliberately
classifies nothing. Select distinct storage-enabled IDs from the current category catalog.
`categoryOrder`, when supplied through control, lists distinct selected category
IDs to put first. Remaining stocked categories follow the host's default order:
obsidian, crying obsidian, building resources, redstone/storage, equipment,
consumables, PvP and specialties. Unknown category slugs follow by name. This
changes layout order, **not** category-match precedence: overlapping rules remain
ambiguous and must be corrected rather than silently selecting a winner.
Storage-only aliases may use a `storage-` prefix (for example,
`storage-obsidian`); the default layout order recognizes the unprefixed slug.
This keeps broad storage rules separate from existing request words.

The response includes `planId`, `stashRevision`, `categoryRevision`, policy,
summary, proposed `categories`, all mapped `columns`, aggregate category `moves`,
unclassified kit IDs and warnings. `executionSupported` indicates that a writer
exists, not that this preview has performed writes or that its prerequisites pass.
`planId` is a deterministic content fingerprint, not authorization or a durable
job ID. A later executor must revalidate both revisions and current containers.

Calculation and catalog reads run outside the host heartbeat/receipt monitor.
Category classification is memoized by canonical kit ID and observed item-key
set within the preview, rather than rechecking every rule for every identical
box. No persistent cache or extra database is introduced.

## What the layout means

- Chest halves with the same recorded physical footprint count once; the newer
  observation wins. Removed containers are excluded.
- Bottom mapped chests define columns. Regular linear banks support vertical
  and stepped layouts along either horizontal axis. Multiple bottom banks are
  preserved until explicit layout support is added; unsupported containers are
  reported, never invented to fill a cuboid.
- Hoppers directly below a mapped chest inherit its column. Otherwise, a hopper
  must have one unambiguous adjacent mapped chest. This is a geometry estimate,
  **not proof of hopper direction or chain connectivity**.
- Unknown/inferred reads, missing internal rows, ambiguous geometry, missing
  exact occupancy, loose items, and unclassified/overlapping categories preserve
  the affected column in place. The preview does not label these columns empty.
- Both complete and incomplete shulkers are counted if their observed contents
  identify exactly one category. Empty/unknown kits remain protected. Canonical
  aliases are respected; kit names and colors are not classification authority.
- Actual observed box locations are used. Transfer-adjusted aggregate counts
  cannot invent spatial locations and are not substituted into the plan.
- Recorded boxes in mapped hoppers count as stock needing accommodation. Hopper
  slots do **not** count as guaranteed destination capacity. A currently packed
  column can therefore need more chest space after its hopper stock is drained.
- Verified empty columns at the end of map order are reserved as buffers only
  in the proposal. Remaining eligible columns receive contiguous category blocks
  in ascending map order, skipping preserved columns. Existing reservations are
  shown for comparison and remain unchanged.
- Within each category block, stock already in the right category is retained
  up to chest capacity before other kits are allocated. Different exact kits may
  share a category; their catalog identities remain untouched.
- Growth columns have zero planned kits and the category ID of their immediate
  physical left neighbor (ascending map order). There is no trailing gap after
  the last stocked category. Preserved/buffer columns can force a category block
  to extend to another eligible column before its adjacent gap; extra empty
  category capacity is still reserved, not advertised as unassigned space.

## Metrics and limits

`emptyColumns` is the recorded, verified-empty count. `projectedEmptyColumns`
includes the reserved empty buffers, and `netColumnsFreed` compares the two.
`growthColumnsReserved` counts explicit left-owned gaps; `unassignedEmptyColumns`
excludes those, buffers and any additional empty category-owned capacity.
`growthColumnsShortfall` must be zero before writing.
For a layout that does not fit, projected assignments are incomplete proposals,
not a promise of attainable free space. `capacityShortfall` reports kits that
cannot fit in eligible chest slots. No move list is produced when capacity or
the requested empty buffer reserve is insufficient.

`feasibleForMovableColumns` only means the classified, eligible subset fits with
the requested buffers. `completeMappedCoverage` additionally requires that no
columns were preserved and no containers were unmapped. Neither flag means the
observations are physically current or that unmapped cuboid space is empty. Oldest/newest observation timestamps are
included when available; scans and hopper flow are not atomic snapshots.

`kitsToMove` counts category relocations before temporary staging. The lower
bound `minimumInventoryLoads` assumes arbitrary mixing across the whole plan;
`pairwiseInventoryLoads` separately rounds each source/destination pair. Neither
includes travel, container opens, confirmation, recovery or buffer double moves.
They are **not time estimates**. Cyclic source/destination dependencies are
flagged because these need a staging schedule before execution.

`bufferSlots`, `largestMovableColumnKits` and `canStageLargestColumn` show whether
the proposed buffers can hold one entire recorded movable column, including its
mapped hopper stock. This is a conservative working-space comparison, not a
mathematical proof that every possible permutation or mixed-column cycle can be
executed with that space. `minimumBuffersForWholeColumn` counts how many of the
largest currently empty columns would hold that whole column (null if they
cannot); it is not the minimum needed for an arbitrary sorting algorithm. Four
buffers remain the starting recommendation.

Moves are aggregate source-column → destination-column category flows, not
clicks or withdrawals. The first 2048 flow details are returned; truncation is
explicit and totals still cover the whole proposal. Up to 2048 bottom columns
are supported. The packing heuristic is deterministic, not globally optimal.

## Experimental writer (standalone host + client 0.15.9)

The writer is deliberately a Monocle job, not a new universal RWP task type.
It reuses the existing StashScan, StashResupply and StashDeposit engines, movement,
mending, server inventory verification, withdrawal/deposit receipts and task
checkpoints. `StashSortPlan` supplies the next journaled instruction. The ingame
host rejects this job explicitly; it does not leave a worker waiting indefinitely.

From the stash page, refresh the preview, choose a sorting-capable courier and
explicitly start write mode. The host rejects stale plan IDs, old clients,
insufficient buffers and concurrent jobs in that crew/world. It leases that
crew/world until completion or an explicit stopped-sort release. Existing jobs
are not cancelled to make room. Protected and unmapped storage stays untouched.

Authenticated private operator start (also used by the panel):

```json
{
  "op": "stash-sort-start",
  "id": "unique-job-uuid",
  "crew": "crew-id",
  "scope": "example.org\nminecraft:the_end",
  "name": "main",
  "worker": "courier-player-uuid",
  "planId": "fingerprint-from-current-preview",
  "buffers": 4,
  "carrySlots": 34,
  "growthColumns": 1
}
```

If the preview selected `categoryIds` or `categoryOrder`, supply the same values
on start. Retrying the same submission ID with the same payload is idempotent.

The host freezes a per-column/per-kit ledger and physical container footprint in
the task journal. Before first use, it requests an exact-container full read of
each relevant column, including mapped hoppers. The client checks that actual
hopper outlets and incoming feeds stay inside that footprint. Unknown reads,
changed chest sizes, unrecognized kits and discrepancies require inspection;
they are not converted into guessed free slots. Targeted reads do not reconcile
the entire stash's stock baseline.

From host **0.15.12**, unread verification containers trigger up to three fresh
full-column scans before stopping. Earlier evidence is discarded on each retry
so hopper movement cannot combine a new read with an old partial snapshot.
Changed geometry, unknown/loose kits and an observed stock mismatch still stop
without authorizing inventory moves. Retries and duplicate replies survive host
restart without resetting their budget.

For a stopped verification failure with **no cargo**, Resume (or **Retry failed
verification** on the stash page) creates a fresh worker run on the same job and
saved ledger. It preserves settled columns, reservations and move counters,
rereads the failed column, and retains the last failure's run ID and detail.
The worker must be connected, reconciled and idle in the original world; changed
stash/category definitions or outstanding cargo prevent this recovery. Other
finished jobs remain non-resumable. Existing sorting-capable clients need no
update for this host-side recovery.

Client **0.15.13** detects physical hopper feeds independently of a sort's
single-container withdrawal route. A refilled source slot may still contain an
identical kit: confirmation requires the exact kit in the previously empty
destination, exactly one carried-kit gain and an empty server cursor. Refilling
sources do not use static-container bulk withdrawal; footprint checks remain.

Columns are settled in map order, keeping the original empty buffers available
until last. Unwanted/excess kits move to unfinalized working columns with spare
capacity; mixed temporary columns accept only known kits. The destination is
then filled to its planned category quota, reread, and reserved. Growth columns
are emptied, reread, and reserved for their left-hand category. Earlier completed
columns are not borrowed again. Temporary staging can increase the number of
confirmed moves beyond the preview's lower bound.

Each load has an intent before movement and exact server-confirmed pickup and
deposit evidence. Cargo stays attached to that batch until its deposit is
confirmed. Replayed requests return the same instruction; repeated receipts
cannot increment counts twice. Inventory contains only ordinary personal gear
between loads: pre-existing or incidental shulkers cause a visible inventory wait,
not automatic disposal. The courier adapts each batch to free slots. Internal
withdrawals decrement, and deposits increment, the **same stash**; only cargo
in transit temporarily reduces its contents. `moved` counts confirmed kit moves,
including staging, not distinct kits acquired.

Pause/resume/reconnect reuse existing worker/native checkpoints. An absent or
uncertain worker checkpoint is not recreated by replaying a pickup. Cancellation
and failure retain the lease and cargo journal; recover any carried kits first,
wait for the worker to acknowledge cleanup, then use the panel's release button
or `stash-sort-release` with `id` and boolean `confirmCargoRecovered: true`.
Release preserves the old ledger for inspection and permits new jobs; it does
not deposit cargo or assert that a cancelled sort finished. Active leases cannot
be removed by history expiry or deleting a job.

This first writer supports at most 64 mapped containers per column, 128 moved kit
variants, and unstacked shulkers. It does not perform loose-item cleanup,
automatically replan changed stock, or promise an atomic snapshot of flowing
hoppers. Keep the first live run attended. A discrepancy stops further sorting
rather than guessing which kits moved.

## Cleanup / later improvements

The approved broad grouping is tools/pickaxes, armor, flight, nature, building
palettes, wood, valuables/collectibles, grief supplies, PvP, and storage/workstations,
with pure bulk resources kept separate. Names such as "empty" are not evidence
of emptiness: classify the known contents. Main's TSR Build Kit V.4 is a wood
kit, not a mixed building palette. Loose items remain protected; a later cleanup
may move them to an explicitly designated dump chest, never silently discard
them. No dump chest or cleanup executor is configured by a category edit.

Future automatic replanning must occur only at a verified cargo-free boundary.
Do not release an uncertain batch simply because its worker is offline. A later
cleanup executor needs an explicit dump chest; it must not discard loose items.
