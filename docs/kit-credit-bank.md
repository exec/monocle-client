# Kit credit bank

One credit purchases one kit. Earned credits and the free hourly kit have independent
accounting: a free claim never creates or credits a bank account, spending credits
does not consume or reset the free allowance, and unused free claims do not accumulate.
There is no automatic credit-earning system yet.

Free claims retain their normal **one-hour** cooldown regardless of credit balance.
Credit purchases have a separate **10-minute** cooldown, measured from the accepted
paid request, even if it is undeliverable or refunded. Existing zero-cooldown
exemptions and shorter configured intervals remain intact. Free claims do not reset
the purchase timer, and purchases do not reset the free timer. Changing the balance
does not change either timer. Failed affordability checks never start a purchase
cooldown. Cooldown replies report the relevant timer when sent.

Accounts use canonical Minecraft player UUIDs, not names. An unknown account reads
as zero without inserting a row. Only a positive credit creates an account. Accounts
that later reach zero retain their audit history. Credits are whole integers; balances
cannot be negative. Each adjustment is limited to 1,000,000 credits and balances to
1,000,000,000 credits.

## Using credits

- `kit <category> [quantity] [kit-reference]` uses the normal free allowance.
- `buy <category> [quantity] [kit-reference]` explicitly spends one credit per kit.
- `balance` or `credits` reads your own balance without creating an account.
- With Mike enabled, users can explicitly ask to buy kits or use their credits.
  Mike must ask when payment intent is unclear; it cannot silently switch a denied
  free request to a paid order. The host also checks the newest message for spending
  authorization. If Mike infers a paid order from context without that authorization,
  the host replies with the exact credit cost and an explicit `buy` command instead
  of a generic error; it does not place an order or charge a balance. One order uses
  either Free or Credits, not both.

When a concrete free request is on cooldown, an affordable credit purchase can be
offered instead, provided its separate purchase cooldown has ended and no order is
outstanding. This works for exact `kit` commands without Mike and natural-language
orders through Mike. The host states the cost, quantity, category and gift recipient
before asking for `yes` within **two minutes**, or `no`. The offered exact kit is
retained; confirmation cannot silently substitute another variant.

Only that requester's single-use reply confirms the offer. Confirmation rechecks
balance, stock, caps and cooldowns through the normal paid-order path. The offer
itself charges nothing, reserves no stock, creates no order and starts no timer.
Declining or expiry costs nothing. An unrelated message, another accepted order,
settings save or host restart invalidates the offer; ask for the kits again afterward.
The host handles confirmation directly, without asking the model to interpret consent.

Public free service remains one kit per hour. Credit orders can contain up to
**27 kits per delivery** for both public and whitelisted users, subject to available
balance and stock. Configured whitelisted request caps apply to free orders only. Category-specific stock floors (falling back to the normal minimum), exact-kit validation, requester
identity, gift-recipient validation, duplicate guards, and one outstanding delivery
per requester still apply. A gift order spends the requester's credits.

A charge and its accepted order commit in the same SQLite transaction. Replaying
that order cannot charge twice. A confirmed submission rejection refunds the charge.
After a worker reports a terminal outcome, confirmed undelivered kits are refunded
once. Monocle 0.16.0 reports cumulative confirmed drops and uncertainty from its
durable checkpoint. Host cancellation alone does not justify a refund while the
worker might still be dropping items. Uncertain drops retain their charge for
operator review instead of inventing a successful delivery or refund. Older workers
can confirm successful completion, but failed deliveries without a reliable receipt
require operator review.

The **Credit bank → Charges needing review** section lets an operator confirm the
number actually delivered and supply an audit reason. The undelivered remainder is
refunded atomically with the final settlement. Repeating the same resolution cannot
refund twice; conflicting final counts are rejected. A connected worker must finish
cleanup first. Delivery settlement receipts persist even after old kitbot request
history is pruned. The administrative-only control operation is `kit-credit-settle`
with `id`, `delivered`, and `reason`; balance-only API keys cannot call it.

## Administrator users and Mike

Under **Kits → Categories & whisper kitbot → Whitelisted users**, choose `User` or
`Administrator`. Existing entries default to User. Administrator assignment requires
the host's administrative API key; a player cannot assign themselves this role.
Administrators remain UUID-bound, and their incoming whisper must include the
courier's matching server-resolved sender profile. A username alone is insufficient.

Mike receives a separate verified Administrator system prompt. Shared kit ordering,
stock privacy, and untrusted-input rules remain in force; the administrative prompt
adds direct operational tone and credit-bank instructions. Only these sessions
receive `gift_credits` and `revoke_credits`. `get_credit_balance` can inspect a named
player for an Administrator; ordinary users can inspect only themselves. The host
checks current role, fresh identity and configuration again before executing a tool,
including after an asynchronous recipient lookup. Model-supplied tools do not grant
authority. Named targets are resolved only from the courier's current server
player list (Monocle 0.16.7+), never an external profile service or a guessed UUID.
If the server list cannot identify the player uniquely, no balance changes. Credit operations never grant host control or expose credentials/stashes.

Examples using independently invented synthetic players:

```text
gift ExamplePlayer 20 credits for helping build
revoke ExamplePlayer 5 credits
what is ExamplePlayer's credit balance?
```

Mutation receipts are deterministic and audited. Bank tools never launch kit jobs,
and acknowledgement generation cannot repeat a credit mutation.

## Web UI and restricted API keys

Open **Credit bank** with the existing administrative host API key to inspect
accounts/history, adjust balances, generate keys, or revoke them. A generated key
can have `balances:read`, `balances:write`, or both. It has no other host permission.
Only its SHA-256 digest is stored. Copy the secret when generated: it cannot be
retrieved later. Retrying a creation with the same idempotency key returns metadata
without the secret; if that first response was lost, revoke the key and create another.
Restricted keys cannot sign into the operator console or manage other keys.

All routes use the existing authenticated HTTP/HTTPS API binding. Browser routes
use the corresponding `/ui/api/v1/` prefix and retain same-origin checks.

| Method and route | Permission | Behavior |
| --- | --- | --- |
| GET `/v1/balances` | balances:read | Up to 200 most recently updated existing accounts. |
| GET `/v1/balances/{uuid}` | balances:read | Current balance; unknown UUID reads zero without insertion. |
| GET `/v1/balances/{uuid}/transactions` | balances:read | Up to 100 most recent immutable adjustment receipts. |
| POST `/v1/balances/{uuid}/transactions` | balances:write | Credit or debit a whole amount; requires UUID `Idempotency-Key`. |
| GET `/v1/balance-api-keys` | Administrative key | Key metadata, never secrets. |
| POST `/v1/balance-api-keys` | Administrative key | Generate a balance-only key; requires UUID `Idempotency-Key`. |
| DELETE `/v1/balance-api-keys/{id}` | Administrative key | Revoke a key; requires UUID `Idempotency-Key`. |

Balance-write keys may change balances for any UUID. They cannot change roles, jobs,
stashes, inventories, settings, or the free hourly allowance. Provide the recipient's
UUID and display name; player lookup is deliberately outside restricted permissions.

Adjustment body:

```json
{"name":"ExamplePlayer","direction":"credit","amount":20,"reason":"Build reward"}
```

Use `debit` to revoke credits. Retrying the same UUID idempotency key with identical
parameters returns the original receipt without another mutation; reusing it for a
different adjustment is rejected. Failed debits never initialize an account.

Key creation body:

```json
{"name":"Example rewards service","scopes":["balances:read","balances:write"]}
```

## Runtime storage

The standalone host uses private `kit-credits.sqlite` (WAL, synchronous FULL) for
accounts, immutable transactions, key digests, and kitbot configuration/request state.
The latter share a transaction with charges and refunds. Existing `kitbot.json` is
imported only when no SQLite kitbot state exists; afterward it is a legacy backup,
not an active configuration file. Back up the SQLite database consistently, including
uncheckpointed WAL data, rather than relying on the old JSON. Do not place runtime
databases, API tokens, player catalogs, or operational reports in the repository.
