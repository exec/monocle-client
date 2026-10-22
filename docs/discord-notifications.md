# Discord owner notifications — 0.14.3

Both the standalone coordinator and the in-game Workers host can DM one owner using a Discord **bot token** and the owner's **Discord user ID**. No gateway connection, inbound port, Discord library, slash commands or privileged intents are needed. Delivery uses Discord's HTTPS REST API; it never controls gameplay.

## Setup

1. Create an application/bot in the [Discord Developer Portal](https://discord.com/developers/applications) and copy its bot token. Never use a personal account token. Invite the bot to a server you share with it and allow its DMs.
2. Enable Discord Developer Mode and copy your **user ID** (not username, server ID or channel ID).
3. In the standalone WebUI, open **Notifications**. Enter the ID and bot token, enable notifications, save, then **Send test DM**. A queued test is not proof of delivery: check the delivery status and your DMs.
4. For an in-game host, use **Right Shift → Workers → Discord owner notifications**, or `.worker discord`. Paste the token using the private clipboard button, enter your ID, save and test. `.worker discord status`, `test` and `reload` are also available. Workers cannot configure their host's Discord credentials.

The private `discord-notifications.json` file lives in the host data directory, or the client host's Monocle directory. It contains `enabled`, `ownerId` and `botToken`. It is separate from job profiles, worker settings and telemetry; POSIX systems use owner-only file permissions. Keep the directory and its backups private. Windows relies on the account/directory ACLs. Blank token input keeps the existing token; **Forget token and disable** clears it. The token is not returned by the API. The browser keeps an unsaved token only in this tab's memory, never browser storage; disconnect clears it.

Remote configuration should use the host's existing HTTPS reverse-proxy setup. Saving a token over a plain HTTP/LAN control connection does not encrypt that connection.

## What triggers a DM

- A job or worker run enters **Failed** or **Inspection required**, including pending failure cleanup. Failed worker runs identify the worker UUID, job and crew, and include the reported reason.
- A worker reports that a live configuration change failed; unchanged error telemetry does not repeat the alert.
- A native worker reports a blocked/failed/controller-error phase, a worker connection drops, a worker message is rejected, or the host records a coordinator/send/logging error.
- A fresh worker death or known-hostile safety logout appears in the incident timeline. Damage, speculative crystal attribution, neutral sightings and routine reconnect progress alone do not alert.
- A new host-wide known-hostile alarm (0.14.4), including observer, crew, server and sighting position. Repeated current views refresh the alarm without another DM. Detected lobby sightings/combat events are not captured or alerted.
- A whole job **completes** or is **cancelled**. Individual completed workflow steps/inventory loads do not produce completion messages.

Routine eating, restocking and manual pauses do not alert. State transitions are deduplicated; a later failure after resuming can alert again. Identical connection/error reports are suppressed for five minutes. Existing finished history is not replayed when the host starts. Restart-generated inspection states do alert. Events observed while notifications are disabled are not replayed when enabling them.

## Delivery, recovery and limits

- One asynchronous sender caches the owner's DM channel and disables mentions. Up to **256** messages are retained in `discord-outbox.json`, alongside bounded deduplication state. Pending messages survive ordinary host shutdown/restart. Changing the owner discards the previous owner's backlog, preventing cross-recipient disclosure.
- Outbox writes are off the coordinator/gameplay thread during operation and precede delivery. An abrupt process/machine failure can lose notifications queued since the latest write (normally within a second). An unreadable outbox is left untouched and delivery is held; restore it or move it aside while the host is stopped, then restart. Notification disk/network failures never fail a Minecraft job.
- HTTP 429 honors Discord's retry timing. Server/network failures retry with bounded waits. Other rejected requests, including invalid tokens or forbidden DMs, hold the queue until settings are saved or a test retry is requested. Errors expose HTTP status and a safe explanation, not Discord's raw response or credentials.
- Retries use a stable `nonce` with `enforce_nonce`; Discord deduplicates it for a short window. A rare duplicate remains possible after a long outage or a crash after Discord accepted a message but before the outbox acknowledgement was saved. This is **best-effort**, not exactly-once delivery.
- Delivery status exposes enabled/configured/blocked, pending, this-session delivered/dropped counters, last delivery time, next attempt and error. A full outbox drops additional notifications and reports that loss. A powered-off/crashed host cannot send a notification about its own disappearance; an external uptime monitor is a separate feature.
- DMs contain job names, failure reasons and sometimes Minecraft coordinates. Consider those private operational information. Discord receiving a DM does not guarantee the owner's device displays a notification.

Discord references: [authentication](https://docs.discord.com/developers/reference#authentication), [create DM](https://docs.discord.com/developers/resources/user#create-dm), [create message](https://docs.discord.com/developers/resources/message#create-message), [rate limits](https://docs.discord.com/developers/topics/rate-limits).

## Authenticated host API

Use the existing operator token and origin rules. `POST /v1/control` (native) or `POST /ui/api/control` (WebUI) accepts:

```json
{"op":"discord-settings","enabled":true,"ownerId":"123456789012345678","botToken":"YOUR_BOT_TOKEN"}
```

Omit `botToken` or send an empty string to retain it. To forget it:

```json
{"op":"discord-settings","enabled":false,"clearToken":true}
```

```json
{"op":"discord-test"}
```

Settings/test replies contain only public delivery status. `/v1/status` exposes the same object under `discordNotifications`. The existing operator API is the authority; public RWP worker sessions cannot configure Discord. Configuration controls are not workflow actions and are not exported to workers. Slash commands are intentionally deferred.
