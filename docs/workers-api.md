# Monocle Workers API

The Monocle Workers API is the public contract for coordinating workers made by
Monocle or another client. It will sit beside the current private protocol until
the new contract has proven compatible in real jobs.

## Contract boundaries

The API has two surfaces:

- **Worker protocol:** a long-lived WebSocket for registration, capabilities,
  observations, task control, progress, events, cancellation and reconnects.
- **Operator API:** HTTP endpoints used by the launcher, in-game Workers tab and
  third-party control panels to manage workers, crews, workflows and jobs.

Both surfaces use the same versioned JSON Schemas. The HTTP description will be
published as OpenAPI; WebSocket messages will use the shared schemas directly.

## Compatibility rules

- Every message carries `api`, `version`, `type`, `id` and `payload`.
- Unknown optional fields are ignored; unknown required capabilities reject the
  task before it starts.
- Commands are idempotent and identify both a task and its execution generation.
- Reconnecting workers report their last acknowledged event before receiving
  replayed state.
- Capabilities are namespaced and versioned, for example
  `monocle.highway.build.v1` and `monocle.stash.scan.v1`.
- Third-party workers do not need Lua. A host assigns resolved actions that the
  worker explicitly advertises; portable workflows remain an optional ability.

## Internet sharing boundary

The existing crew key is suitable for trusted local testing, not public worker
sharing. The public API will identify a worker independently from a crew and use
revocable, scoped credentials over HTTPS/WSS. Grants will limit which owners,
crews and capabilities may assign work. Audit events must record assignment,
control and credential changes without exposing secrets.

## Migration

1. Keep the current protocol as the internal v0 adapter.
2. Extract and publish schemas from messages already used in production.
3. Add a v1 adapter to the host and Monocle worker without changing job logic.
4. Add conformance fixtures for registration, task lifecycle, cancellation and
   reconnect before accepting another client implementation.
5. Retire v0 only after mixed Monocle/third-party crews complete real jobs.

The Java `Bots` types, `bot-*.json` files, Lua `bot.*` namespace and `.bot`
command alias remain compatibility details. Public UI and documentation use
**Workers**; new integrations target only the versioned API.
