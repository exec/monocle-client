# Minecraft server identities and aliases

A server definition has a display name, one primary Minecraft address and explicit
alternate addresses for the **same world**. In the standalone WebUI, open
**Servers**, add a definition and save. Use the address already used by existing
stash catalogs as the primary; do not invent a new identity for populated storage.

The catalog is private runtime configuration in `minecraft-servers.json` under the
host data directory. It contains no connection credentials. Example addresses below
are invented and do not refer to a real server:

```json
{
  "version": 1,
  "servers": [
    {
      "name": "Synthetic network",
      "address": "primary.example.invalid",
      "aliases": ["relay.example.invalid", "backup.example.invalid:25566"]
    }
  ]
}
```

Hostnames, IPv4 and bracketed IPv6 addresses are accepted, with optional ports.
Matching ignores hostname case, a trailing DNS dot and an explicit default port
25565. Other ports stay distinct. A normalized address can belong to exactly one
server definition. URLs, wildcards, credentials and overlapping definitions are
rejected. The primary address is the stored identity; unknown addresses retain their
original identity. No DNS lookup, suffix heuristic or automatic server inference is
performed. Dimensions remain separate.

## Worker behavior

Native Monocle workers on 0.15.48+ advertise `serverAliasesProtocol: 1`, their actual
`serverAddress`, and their applied `serverAliasesRevision` in their hello. The
trusted host replies with `task-server-aliases` and the validated catalog. Workers
apply it and announce again before the host schedules work or processes kitbot
whispers. An idle client adopts the policy automatically. A solo highway or local
scan that would change identity defers adoption until it ends.

The worker keeps connecting to its original address. Logical identity is shared by
native task checks, highway coordination, stash scans/observers/exports, personal
supply caches, home cooldowns, chat and safety observations. Reconnecting through
an alias therefore does not create a different world key. Retained worker task
receipts are not rewritten. A reconnect can recover the host policy before resuming
its original canonical task.

Old workers continue operating at the primary address. They cannot adopt aliases;
status reports `serverAliasesReady: false` when an update is required. Status also
shows the actual `serverAddress`, separately from canonical `scope`. No protocol
message is sent to a worker that did not advertise support.

For solo use without a native host, place the same catalog under the client data
directory and restart the client. A connected native host's catalog is authoritative
for that connection. This native policy is not an RWP core message; independent RWP
workers still supply their configured logical server identity themselves.

## Editing and existing data

Finish jobs and outstanding cleanup before editing definitions. Saves use a revision
check and persist before workers receive the policy. Primary/alias changes start a
fresh Mike conversation context. Historical stash catalogs, observations, incidents
and job history under another address are **not merged or deleted**. Configure the
existing primary address before new scans or jobs; moving previously split catalogs
requires explicit operator work.

## Operator control API

`server-aliases-get` returns `version`, `servers` and `revision`.
`server-aliases-save` takes `expected` and a complete `servers` array. It rejects stale
revisions, conflicting addresses and changes during unfinished work. Both use the
existing authenticated control API; workers cannot edit the host catalog.
Limits: 32 definitions, 16 aliases per definition, 255 characters per address and a
64 KiB encoded catalog.
