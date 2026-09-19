# Bot web transport — 0.7.26

0.7.27 adds a separate same-origin [operator WebUI](bot-webui.md) under `/ui/`. The native routes and worker handshake restrictions described below are unchanged.

This first stage adds web connectivity without replacing job, workflow or resource-recovery logic. LAN TCP remains the default. A crew can mix TCP and WebSocket workers, and client-based hosting uses the same web ingress and authenticated protocol as the standalone service. No launcher is required.

## Worker connection

In **Right Shift → Workers → Connection → Address & port**, put `wss://workers.example.com/v1/workers` in **Host address**, or include a non-default TLS port: `wss://workers.example.com:8443/v1/workers`. The URL supplies its own port; the old TCP port is ignored. Keep the existing crew key, worker connection and trusted-host execution settings.

Workers initiate the connection; their machines need no incoming port forwarding. TLS uses normal JVM certificate trust and hostname validation. Do not disable validation or use a trust-all certificate setup. URLs containing credentials, query parameters, fragments or a different endpoint are rejected. `ws://` is allowed only for explicit loopback hosts (`localhost`, `127.0.0.1`, `::1`) for local testing. Use `wss://` for LAN web connections too. Existing IP/hostname + TCP port settings retain their LAN-only behavior.

Reconnect uses the existing automatic backoff and durable execution reconciliation. Cancelled executions remain cancelled when an offline worker reconnects; old reports cannot restore their work authority. This does not change the existing host-restart inspection policy or automatically resolve uncertain drops/containers.

## Host ingress

Standalone: stop the service between jobs, add `"webPort": 6971` to its existing `host-config.json`, then start the updated distribution. An absent field or 0 disables web ingress, including in newly initialized configs. Do not change existing crew keys or delete journals.

In-game host: while connections are stopped, set **Host web port (0 = off)** to 6971 in the same Workers connection panel, apply settings, and start the host. The field is host-only in effect. The normal TCP listener and coordinator remain available.

Web ingress always binds to `127.0.0.1`, independently of the TCP bind address. For local testing, workers on that machine can use `ws://127.0.0.1:6971/v1/workers`. For remote access, run a TLS reverse proxy on the **same machine**. Do not expose the plaintext backend or forward port 6971 directly. Deploying a proxy, domain, certificates, firewall rules or Cloudflare is an operator action, not something this build performs automatically.

### Example worker-only Caddy routing

Replace the domain with one you control and configure valid certificates/DNS for your deployment. This deliberately does **not** publish the administrative API:

```caddyfile
bots.example.com {
    handle /v1/workers {
        reverse_proxy 127.0.0.1:6971
    }
    handle {
        respond "Not found" 404
    }
}
```

Caddy handles WebSocket upgrades through its [reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy) and supports [automatic HTTPS](https://caddyserver.com/docs/automatic-https). Keep the original path intact; do not use a path-stripping route. Preserve `Origin` headers: the native worker listener intentionally rejects browser-origin handshakes. Establish access restrictions and connection/rate limits at your perimeter; this is not a public anonymous service. If using Cloudflare later, [connection termination during infrastructure changes](https://developers.cloudflare.com/network/websockets/) is expected; workers reconnect through the same recovery path.

## Versioned standalone control API

The standalone API still binds only to `127.0.0.1:<apiPort>` (6970 by default). New endpoints are:

| Endpoint | Behavior |
| --- | --- |
| `GET /v1/status` | Existing full host status snapshot |
| `POST /v1/control` | Existing JSON control operations: submit, pause, resume, cancel, etc. |
| `POST /control` | Backward-compatible CLI/API route |

All require `Authorization: Bearer <apiToken>`. POST requires `Content-Type: application/json`. These native routes reject browser-origin requests and query parameters. The 0.7.27 dashboard uses separate same-origin `/ui/api/*` routes. Existing request byte limits, explicit submission UUIDs, idempotent submissions and durable cancellation rules are unchanged. The CLI still talks to the local route.

An operator can proxy the versioned API routes through HTTPS on a private/VPN/access-controlled endpoint. Preserve the authorization and Origin headers and the original paths. **The API token grants full host control across crews.** Keep the example worker-only route as-is until you have an appropriate perimeter; never hand the API token to workers or publish it in a URL, config export, screenshot or repository. A crew key is not an admin token. TLS encryption alone does not make full administration safe to delegate.

The in-game host receives the same worker web transport in this stage; the standalone administrative HTTP API is not newly exposed by Minecraft. Both still invoke the same coordinator policies through their existing controls.

## Protocol and limits

`SwarmConnection` continues to own nonce-based mutual crew authentication, sequence-bound message MACs, bounded queues and encrypted crew-key handoffs. WebSocket carries one existing protocol record per text frame; it does not invent a second execution format. Socket/WebSocket callbacks never touch Minecraft state. The WebSocket library is [Java-WebSocket 1.6.0](https://github.com/TooTallNate/Java-WebSocket); its [MIT notice](../licenses/Java-WebSocket-MIT.txt) ships with both builds.

The shared host limit remains 16 connections across TCP and WebSockets, including unauthenticated protocol connections. WebSocket frames/messages are capped at 64,000 UTF-8 bytes and protocol records at 16,000 Java characters; binary records are rejected. Incoming frame and protocol queues are bounded, and output waits for library buffers to drain with a timeout rather than accumulating an unlimited library outbox. Authentication/idle reads and stalled output time out. Congestion closes the connection and uses normal reconciliation; no new telemetry coalescing or reserved cancellation queue is introduced in this stage.

Crew keys remain shared trusted-execution credentials. There are no owner accounts, individually revocable worker tokens, invitations, delegated permissions or audit identities yet. Keep deployment restricted to trusted operators; this is the transport foundation for future sharing, not the sharing product itself.

## Checks

Run `./gradlew :host-service:check botsCheck swarmCrewCheck bundledLibrariesCheck`. Service tests exercise actual HTTP/HTTPS and TCP/WebSocket connections with simulated game workers: mixed-transport highway completion, crew isolation, priorities, pause/resume, cancellation while offline and reconciliation without replay. A test-only TLS proxy verifies certificate trust and hostname checks, Unicode frame ordering, endpoint/origin rejection and oversized-frame rejection while a healthy worker remains connected. No test changes production certificate trust or starts a persistent/public service. Actual Minecraft movement over a deployed reverse proxy still needs testing.

Next stages: scoped worker identities/ownership and revocation, explicit sharing permissions, browser/controller authentication, resource-oriented REST endpoints, then telemetry coalescing and control-message prioritization under measured congestion. Keep those policies shared between host types.
