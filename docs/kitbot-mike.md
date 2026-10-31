# Mike: optional Ollama kit handler

Mike runs in the **standalone host** and uses Ollama's native
[`/api/chat` tool calls](https://docs.ollama.com/capabilities/tool-calling).
There is no MCP service or separate game module. Existing workers
execute the normal verified pickup, TPA, handoff and return workflow.
Player deliveries do not require enchanted golden apples or a personal food reserve.
Auto Eat still uses food on hand; elytra maintenance remains active. Long-running
stash transfers and scans retain their food restocking policy.

Player deliveries have bounded host recovery, including deliveries launched from the web UI.
A brief interruption can resume the saved checkpoint once; a second interruption,
an inspection-required checkpoint, or exhausted navigation retries cancels the job.
Two minutes without an available courier also cancels it. Each active action has a
five-minute idle limit and a fifteen-minute total limit, extended by configured home
cooldown/warmup time. Operator pauses and jobs still waiting their turn are excluded.
Cancellation never repeats an uncertain drop or starts a replacement pickup. Kits
still carried remain with the worker, and transfer receipts remain available for
inspection. Monocle 0.15.54 also lets cancelled uncertain drops release the executor
after their confirmation timeout; safe landing and profile restoration still apply.

## Setup

Under **Kits → Categories & whisper kitbot**, configure a primary source, optional additional sources in the same crew/world, courier, public
request categories and username/UUID whitelist, or enable **Public access**. Enable the whisper kitbot and
**Enable Mike**, then enter a tool-capable model and API key. The default endpoint
is **Ollama Cloud**, `https://ollama.com`; no local installation is required.
A requester can whisper “could I get two paving kits?” if that category exists.

Mike is **off by default**. Exact `kit <category> [count]`, `menu [page]` and `kits [page]`
commands always use the deterministic path, even with Mike enabled. They work
without Ollama. The host never downloads models or starts a model server.

`menu` lists exact category request words once each, grouped under `In:` and
`Out:`, with available categories first. The host packs each page against the
256-character command limit including `/msg <username>`, page totals and a
`menu 2` continuation hint. Request words are never shortened or paraphrased.
`kits` retains the raw shulker-name listing. Both are read-only and consume no
delivery quota. Natural-language list requests use `list_kits(page)` to select
the same host-formatted menu; Mike does not compose the list as prose. The page
number retained in conversation lets a requester ask for “next” or “more”.

Whispers blocked by a request cooldown receive a standard reply with the remaining
hours, minutes and seconds. The countdown is calculated when the DM is sent,
rounded up to the next second, and shares the five-second outgoing message limit.
Repeated queued cooldown replies coalesce. They do not call the model, create an
order, or reset the cooldown; exact menus remain available during cooldown.

Optional **Public kit provenance** is a short, public description Mike can use when
asked where kits came from. It defaults to blank, is limited to 256 characters,
and is included in model context, so never put private stash details there. Mike
must not invent specific sources, owners or permissions. Older settings saves
preserve the text; explicitly saving an empty string clears it.

**Fictional stash coordinate replies** (`coordinateJokes`, off by default) adds a
tool for deadpan coordinate jokes. It accepts no arguments and reads no stash
records. The host generates integer X/Z values within ±27,500,000, more than
100,000 blocks from each axis and both diagonals (measured perpendicularly), then
sends a short reply through the existing DM queue. It cannot submit a delivery or
consume a delivery quota. Existing admission and DM rate limits still apply.

Mike uses the native `https://ollama.com/api/chat` endpoint with
`Authorization: Bearer <key>`. Ollama's `/v1` API is its OpenAI-compatible binding;
Mike uses its native API instead. See [Ollama Cloud](https://docs.ollama.com/cloud).
For local Ollama, use `http://127.0.0.1:11434` and an installed model; no API key is
normally needed. `localhost` means the standalone host machine. URLs cannot contain
credentials, paths, queries or fragments, and redirects are not followed.
Authenticated remote endpoints require HTTPS.

The complete `kitbot-save` config accepts an optional nested object:

```json
{
  "mike": {
    "enabled": false,
    "endpoint": "https://ollama.com",
    "model": "",
    "apiKey": ""
  }
}
```

Use the normal config revision check. An enabled Mike requires a nonempty model
tag and an API key for cloud use. Old configs without `mike` work unchanged. `kitbot-get` adds `mikeStatus`:
pending inference count, last response time and generic provider error.

The key is stored only in the host's private `kit-credits.sqlite` kitbot state (owner-only permissions
on POSIX). Read/save responses omit `apiKey` and report `apiKeyConfigured` instead.
An omitted or blank key preserves the existing value; `clearApiKey: true` explicitly
removes it. An endpoint change requires replacing or clearing a saved key so a
credential cannot silently follow a URL edit. Legacy saves omitting Mike preserve
its settings. The key is sent only in the HTTP authorization header, never model
context, worker jobs, chat replies, operator receipts or logs.

## Information boundary

Each inference receives the verified requesting player’s Minecraft username, the current incoming whisper (at most 512 characters),
up to six previous exchanges with that player, the user's request quantity cap,
and a freshly projected public menu. Menu entries contain
category slug, public display description, an explicit `inStock` flag, qualitative availability and up to
eight sample **item types**, without per-kit item quantities.

The system prompt routes kit lists through the host's compact menu tool. Pages
distinguish in-stock and out-of-stock categories and show their page/total plus a
continuation command when needed. Availability is recalculated
from current host stock for each inference; conversation history is not a stock source.
The requesting username may be used naturally for rapport.

The model receives **no exact stock counts**, kit/variant UUIDs, source/destination
stash names, homes, coordinates, dimensions, worker roster, credentials, request
journals, delivery logs or unrelated chat. Data is removed before building the HTTP
body, not concealed by a prompt instruction. The HTTP builder projects the menu
again to ignore unexpected extra fields. Category descriptions are intentionally
public metadata; write them for requesters.

### Advanced contents and specific variants

Ordinary requests stay category-based. The optional `kit` argument to
`request_kits(category, quantity, kit)` is for a player explicitly asking for a
particular contents variant; the compact menu is unchanged.

`inspect_kits` reads only kits published through Kitbot/Both categories in the
configured sources. Optional filters are `category`, public `kit` reference,
partial `name`, `items`, exact-item `minimums`, `page`, and `includeUnavailable`.
Item filters match any selected item ID or whole item words; all specified
minimums must be met. For example, stone ranks stone-related items without
matching redstone; `minecraft:stone` matches only that exact item. `blocks` uses
the current vanilla BlockItem registry and `wood` selects wood families.

Results rank by the number of matching items **inside one kit**, descending,
with four variants per page. Each includes a public `k-` reference, kit name,
shulker type, category request words, full item/count contents, placeable-block
total and qualitative availability. Category/filter ranges cover all matching
variants, not just the page. By default only currently orderable complete kits
are included; `includeUnavailable: true` can describe unavailable variants but
does not make them orderable. Counts describe learned complete-kit exemplars;
unknown and incomplete boxes are not promised to contain those amounts.

Mike can query, read the result, then explain or order the chosen variant in the
same incoming request using Ollama's native tool-result exchange. At most four
tool steps (including validation repair), a 90-second request lifetime and the existing context/response
budgets apply. Tool results are transient: conversation history retains only the
request and final bounded reply. Exact **per-kit quantities** are now disclosed
on request; total **stored copies** remain private. Future inspection callers
cannot add source/stock fields to the permitted public result schema.

The host resolves the public reference back to an exact known kit, rechecks its
category and current stock under the requester's normal limits, and supplies its
own source and exemplar to the delivery workflow. Remembered references trigger a
fresh host lookup and a tool-result exchange if Mike has not inspected them in
the current request. Changed contents trigger the same reconsideration. No
delivery is accepted until contents still match the current inspection. No substitution is made for an
unavailable exact choice. Advanced users can also bypass the LLM with
`kit <category> <count> <public-reference>`; for example,
`kit build 2 k-0123456789abcdef` uses an explicitly synthetic reference.

The bundled `minecraft-block-items.json` contains only vanilla registry metadata.
`kitBlockItemsCheck` verifies it against Minecraft; regenerate it with
`kitBlockItemsCheck --args=--generate` when the game registry changes.

Conversation memory keeps the latest **12 messages (six user/reply pairs)** per
case-insensitive username, scoped to the crew and server. Dimension changes retain
that conversation. Up to 128 recent conversations are kept, expiring after 24 hours
of inactivity. Host restart or a successful kitbot settings save clears the memory.
This first version keeps memory in RAM; it does not create a transcript database.

Only incoming private whispers and the sanitized, length-limited replies queued
for that player enter history, including explicit command replies. Queueing a reply
does not confirm its receipt or a kit delivery. Raw provider responses, reasoning,
tool calls, internal receipts, public chat and other players' conversations are
excluded. Provider failures and invalidated proposals do not add exchanges.
The regular host chat feed still records actual incoming/outgoing Minecraft
messages, and accepted delivery receipts remain durable.

History can resolve a clarification such as “what kind?” → “PvP, two please”. It
cannot change permissions, stock or quantity limits. The prompt explicitly forbids
replaying earlier orders; existing admission checks remain authoritative. Normal users retain one outstanding
delivery; zero-cooldown users may order for different recipients concurrently, with
one outstanding delivery per requester/recipient pair. Active cooldowns still return the remaining time.

## Qualitative stock

The same policy as deterministic kitbot applies: complete directly observed kits,
subtracting outstanding delivery reservations. Optional **Category stock minimums**
in whisper kitbot settings override the normal floor for specific request words.
For example, an independently invented specialty category can use
`specialty = 54` while other categories keep the default. Each value is 0–1,000,000
complete confirmed kits per variant; at most 128 overrides are allowed. The same
category-specific floor applies to menus, raw listings, contents inspection and
both free and credit orders. A cheap category does not lower another category's
floor when ordering an exact reference. Unscoped inspection shows eligible request
words for an available variant. Project users keep the lower of 109 and that
category's configured floor.

These are admission thresholds, not hard personal reserves: an accepted order can
reduce remaining stock below the threshold. Estimates, incomplete boxes and pending
reservations cannot be used to bypass the policy. The optional kitbot config field
is `categoryMinimumStock`, an object mapping request words to integer floors (0–1,000,000). Older
settings saves that omit it preserve the map; an explicit empty object clears it.
Storage categories, column reservations and worker wire formats are unchanged.

Each variant must individually meet
its category-specific minimum, or the configured default (270 for new WebUI configurations; existing settings are preserved). Incomplete, ambiguous, inferred and
transfer-estimated stock does not qualify. Qualifying variants are added within a
category; overlapping categories are not independent inventories. With additional sources,
the menu uses the highest confirmed count per variant rather than adding overlapping
selections. Each variant must meet the minimum in at least one selected source.
The host dispatches from the source with the strongest eligible stock and uses that
stash’s own bounds and home. Stock is checked again when accepting a request.

**Categorize stocked kits** adds request categories for every qualifying variant
in the displayed source. Run it for each source after a new scan. It preserves
storage reservations and existing operator definitions, except that the broad
`building` alias becomes storage-only. Distinct building kits get their own request
categories instead of being selected interchangeably. New categories are Kitbot-only.
A new variant excluded by an existing group receives its own request category.
Categories can remain defined after stock drops; their low-stock variants are
automatically unavailable through both Mike and explicit commands. The same cutoff
applies to the `kits` listing. Estimated, incomplete and ambiguous kits do not qualify.

Fixed, approximately geometric bands saturate early:

| Eligible category stock | Label |
| --- | --- |
| No qualifying stock | Out of stock |
| Below 350 | Very little |
| 350–499 | Not a lot |
| 500–699 | A little bit |
| 700–999 | Some |
| 1,000–1,399 | A reasonable amount |
| 1,400–1,999 | Quite a bit |
| 2,000+ | Plenty |

Only the label reaches Ollama, not this table or the amount. Menu order follows
category slugs, not stock ranking. Thresholds do not depend on the richest stash.
Recorded stock can still be stale; actual pickup/handoff determines delivery.

## Public service or whitelist-only service

Public access is **off by default** and separate from the enable-kitbot switch.
When enabled, a worker on 0.15.48+ attaches the whisper sender's exact username/UUID
from the server's current player list. The host requires that profile to match the
incoming whisper before admitting an unlisted player. It does not invent offline
UUIDs or use an external account lookup. Unresolved profiles cannot queue deliveries.
Older workers can still serve explicitly whitelisted accounts; public users require
an updated courier. No player account record is created merely to receive a whisper.

Public users get **one free kit per hour**. The [credit bank](kit-credit-bank.md)
allows explicit paid orders at one credit per kit with a separate ten-minute cooldown, independently of the hourly free allowance.
Use `buy <category> [quantity]` or explicitly ask Mike to spend credits; `balance`
reads your own balance. An affordable free request blocked by the hourly cooldown
can instead receive a host-generated credit offer, with its exact cost and gift
recipient. Reply `yes` within two minutes or `no`; nothing is charged until a valid
single-use confirmation passes current balance, stock and purchase-cooldown checks.
Unrelated messages, settings saves and host restarts clear pending offers. With public access on, whitelist entries grant **project access**: no hourly
cooldown and access to any categorized variant with **more than 108 confirmed kits**
(109 minimum). Their configured free-order cap (credit orders allow up to 27 kits), five-second duplicate guard, and one
outstanding delivery per requester/recipient pair still apply. Mike and explicit commands use the same per-player
stock policy; the model never sees another player’s expanded menu. In whitelist-only
mode, the configured per-user cooldown and normal stock cutoff apply. Disabling public
access restores whitelist-only behavior without deleting those entries. Public identity
caches are bounded and reset on configuration changes; accepted orders and cooldowns
remain durable. Recent cooldown records cannot be evicted to make room for another
user; admission waits if the bounded journal has no safely expired record to remove.
The free allowance uses the existing per-request cooldown and does not accumulate.
Mixed-category player deliveries are not supported yet.

Server aliases are configured independently under **Servers**; see
[server identities](minecraft-server-aliases.md). The worker's canonical server must
match the configured kitbot/stash world. Unknown alternate addresses are not guessed.

## Actions and authorization

The only delivery tool is `request_kits(category, quantity, kit?, recipient?)`. Its schema exposes
public category choices and the requester's quantity cap. Unknown tools, multiple
calls, fractional quantities, unlisted categories and extra arguments such as
`recipient` UUIDs, `source` or `command` are rejected. Free-form text cannot run code,
issue game commands, change permissions or create arbitrary worker jobs.
`list_kits(page)` only reads the public menu and sends its compact page through
the existing DM queue; optional fictional-coordinate replies are read-only too.
Rejected multi-tool/category/reference responses can be retried with fixed host
sequencing instructions. None of their calls are executed, and retries share the
same four-step/lifetime limits. Final validation failures expose only fixed reason
codes in host activity and `mikeStatus.error`, never raw provider text or arguments.

Self-delivery is the default. An explicit gift request can supply another exact
Minecraft username; the model cannot supply a recipient UUID. The host reuses its
selected courier's current server player list through a bounded, asynchronous
native lookup. The courier must be ready and on Monocle 0.16.7+. No external profile
service, connected-worker roster fallback or guessed offline UUID is used. The
server-provided UUID and canonical name are retained. Missing or ambiguous names,
world changes, disconnection and a five-second response timeout fail without a job
or cooldown. Named credit gifts/revocations use the same lookup; an unresolved
player never changes a balance. Only the requested profile is returned, not the
whole server roster. Lookups do not pause movement, run game commands or occupy a
worker job. Before accepting, the host rechecks ready-world/worker/crew/server state, unchanged
configuration, requester permission, requester cooldown, requester quantity cap and current stock. Any intervening
request from that player invalidates the old proposal, even with zero cooldown.
Disabling Mike or changing config invalidates in-flight proposals.

Accepted requests use the existing persist-before-dispatch path. The durable
`requester` owns the cooldown and pending-order limit; `recipient` and
`recipientName` describe the actual destination. Older records without `requester`
retain their original self-delivery owner. A gift never consumes the receiver's
allowance or grants them worker/host/Auto TPY privileges.

Only after acceptance does Mike generate one short acknowledgement from a separate
public order projection: requester, recipient, category, kit name and quantity.
It has no tools and cannot submit another delivery. This also applies to ordinary
`kit` commands when Mike is enabled; disabled/unavailable Mike uses the existing
deterministic fallback. For gifts, the requester is acknowledged first, then the
recipient receives a notice naming the giver, number and actual kit name. The
pair enters the DM queue atomically, with the global five-second spacing applied
between them. If the delivery has already failed before acknowledgement finishes,
the recipient receives no gift notice and the requester gets a failure reply.
Acknowledgement and DM queues are session-only and never replay an old session.

Rejections use deterministic stock or
limit replies. Greetings, questions and clarifications can get text replies
without jobs or delivery cooldowns; active cooldowns use the standard remaining-time reply. Zero-cooldown users can
continue a conversation and order for another recipient during an existing delivery;
same-recipient duplicates receive an explicit in-progress reply. Server whisper text retains the
existing server-text trust boundary; Mike does not authenticate players.

## Timing and failure behavior

Inference runs off the coordinator thread, with eight outstanding requests maximum,
one per person, and a five-second admission guard. The complete response including
body has a 45-second deadline and 32 KiB limit. Proposals expire after 90 seconds.
The inference queue is session-only; accepted jobs and cooldowns remain durable.

Generation requests a **256-token** output budget with reasoning disabled, and the
system prompt asks for one short whisper. The provider response is capped at
32 KiB; text content over 4,096 characters is rejected. Outgoing text is stripped
of formatting/control characters and truncated with an ellipsis to fit the hard
**256 UTF-16-unit command limit**, including `/msg <username> `. This leaves
234–249 units for reply text, depending on username length. The same limits apply
with history enabled. Replies share the existing one-DM-per-five-seconds queue.
Provider failure or invalid tool output cannot create a delivery or disconnect
workers. A fallback points to explicit commands. No internal host errors,
catalogs or job receipts are sent back to the model.

Synthetic tests cover HTTP projection, privacy canaries, response bounds, tools,
real authenticated worker sockets, stale-config rejection, deterministic delivery
acceptance and WebUI settings. They do not establish a particular model's request
understanding or verify live gameplay.

Unanswered player deliveries use a 60-second wall-clock arrival deadline, preserved
across reconnects. After a failed TPA, the courier returns to the source and stores
undelivered kits in a nearby accessible ender chest, then ends that delivery so the
queue can continue. It preserves the requester cooldown. Existing ender-chest items
are retained; only matching delivery cargo is moved. If no accessible ender chest or
space is available, cargo stays in inventory and the delivery reports the problem.
Confirmed undelivered paid kits are refunded without resetting the purchase timer.

Courier handoffs aim at the recipient’s latest visible position, rather than the
initial TPA snapshot. A moving recipient does not cause pursuit: the courier holds
safe footing and throws in their current direction. Stationary handoffs prefer a
three-block offset, then shorter safe offsets when space is limited; movement still
checks ground and body clearance throughout. Item throws do not require a walkable
corridor all the way to the recipient. Confirmed delivery starts a temporary Derp
celebration (one-tick teabag, spin and periodically random direction) during departure.
It changes no saved Derp settings and is disabled on stash arrival, cancellation,
failure or disconnect. Confirmed undelivered cargo from later handoff failures uses
the same source ender-chest recovery as failed TPA attempts.

Successful kitbot deliveries return by `/kill` and automatic respawn. The courier’s
bed or spawn point must already be in the source stash. This deliberately discards
incidental inventory and equipped items: use an expendable courier for this mode.
It verifies the loaded respawn is within 32 blocks of the source bounds in the
correct dimension; an incorrect spawn ends the job without another kill. `/kill`
has a separate persisted 61-second server timer and is sent on the first eligible
tick. Intent is saved before sending, so a restored checkpoint waits through that
cooldown before any retry, with at most three unsuccessful attempts. Manually sent
`/kill` commands observed by the worker also start this timer. Failed or partial
handoffs return by the existing stash route and secure remaining kits in an ender
chest; they never opt into this inventory-discarding return. Ordinary web UI player
deliveries retain their existing return route unless `returnByDeath: true` is chosen
in their job arguments alongside `returnHome: true`.

The optional `excludedKitFamilies` configuration array blocks up to 128 canonical
kit-family UUIDs from all kitbot selection, menus, listings and content inspection,
including exact public references, credit purchases and Administrator requests.
Accepted requests that become excluded before dispatch are failed and refunded
once. Family identity uses the shulker item/color and display name, so the exclusion also
covers newly observed content variants in that family. Stash catalog counts and
operator-managed storage jobs are unchanged. A settings save that omits this array
preserves it; send an explicit empty array to clear the policy. Excluded live family
identities belong only in private host configuration, never in repository examples.

TPA-timeout notices go only to the player who ordered the delivery, even when they
named a different gift recipient. Paid notices are stored with the confirmed full
refund; free notices say no credits were spent. Both explain that the cooldown still
applies. Pending notices remain in the private SQLite request journal through host
restarts and use the existing one-DM-per-five-seconds queue when the original courier
is ready. A courier dispatch acknowledgement completes the notice. An unacknowledged
attempt can retry after 30 seconds with the same command ID, allowing the client to
acknowledge a duplicate without re-sending it in the same session. This confirms a
client send, not that an offline player received or read a message.

### Limited-availability categories

`kitbot-save` accepts `categoryCooldownSeconds`, mapping exact category request
words to whole seconds (1–2,592,000, up to 128 entries). For example,
`{"synthetic-specialty":86400}` permits **one kit globally every 24 hours**.
The settings dialog exposes the same map as `request-word = seconds` lines.
This is independent of a requester's free and credit cooldowns. Whitelisted users,
administrators, gifts, credit purchases and exact kit references cannot bypass it.
Matching aliases share the restricted kit's slot. Requests for multiple kits are
refused without charging credits or using the requester's allowance.

An accepted order reserves the slot durably before dispatch. A confirmed handoff
starts the interval, even if returning home later fails. A confirmed terminal
zero-delivery receipt or a never-submitted order releases the reservation. Unknown
or uncertain handoffs stay unavailable for review rather than risking a second
copy. Existing credit-settlement review can resolve a paid uncertain handoff.
Timestamps live in the SQLite-backed kitbot journal independently of pruned order
history, and reconnecting or restarting the host does not reset them. Old settings
clients preserve the map when omitted; an explicit empty object clears the rules.

Limited kits appear out of stock in menus, raw listings and contents inspection
while reserved or cooling down, then become available automatically when the
interval expires **and complete recorded stock remains**. Category stock floors
can now be zero: this removes the reserve threshold but never makes an empty,
incomplete or merely estimated kit available. Other stock floors and kit-family
exclusions continue to apply.

### Two couriers

The optional `backupWorker` config field names a second native worker UUID. The
primary `worker` remains the default courier. Both accounts accept kitbot whispers
and use the same host-side Mike conversations, bank, user allowances, outstanding
orders, stock reservations and limited-category availability. A zero-cooldown or
administrator user still cannot order the same limited kit on both accounts.
The WebUI exposes a **Second courier / whisper receiver** selector. Older settings
clients preserve an omitted backup; an explicit empty string disables it.

New orders prefer the account receiving the whisper. If it already has work, the
host can assign the other account only when connected, reconciled, fresh, ready,
idle, and in the configured crew and Minecraft server. Otherwise the order stays
queued on the receiver. Assignment is persisted with the accepted order and any
credit charge. The busy receiver names the assigned courier in its acknowledgement;
gift notices identify whose TPA the recipient should accept. Each account keeps
its own five-second DM queue. Up to two provider requests run concurrently, so one
slow model response does not block the other account; the shared eight-request
bound and serialized host admission still apply. Source pickup is serialized to avoid simultaneous
withdrawals from the same chest; travel and handoffs run independently afterward.

Named gift recipients and credit targets still require server-player-list lookup
support on the account receiving the request (native client 0.16.7+). Primary-account
requests can dispatch to the second courier without moving those lookups off the
primary account. No external identity-service fallback is added.

Each worker needs valid source-stash homes and its own bed/spawn at storage for
the current `/kill` return workflow. Adding a backup does not sign it in, set its
spawn, or copy an account's homes. An accepted delivery stays with its assigned
worker if it disconnects: existing recovery/refund rules apply rather than starting
a second delivery that might duplicate cargo. New orders can still use the ready
account. Courier names are public acknowledgement data; private roster details and
stash locations are never passed to Mike.

### Requests while a courier waits for acceptance

An outstanding gift belongs to the requesting user for charging and cooldowns, but
is delivered to its selected recipient. Users with an effective zero cooldown may
have deliveries to different recipients in flight together, so a pending gift does
not block a request for themselves or another player. The second order still uses
normal ready-courier selection and the shared queue. A requester cannot create a
second simultaneous order for the same recipient, even through the other account.

The five-second duplicate guard, normal public free-hour and credit timers,
quantity caps, eight-order queue bound, stock reservations and global limited-kit
slots all remain in effect. Admission failures outside the duplicate-suppression
window produce a safe explicit reply instead of a silent model-tool outcome. No
credits are charged and no extra order is created for an in-progress refusal.
