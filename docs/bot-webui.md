# Operator WebUI — 0.7.54

The standalone Java host now bundles a dark-and-gold operator dashboard. No Node, frontend server, or browser extension is needed. It uses the existing host coordinator and job controls; the in-game Workers GUI remains available without this dashboard.

## Open it

Update the standalone host distribution between jobs, keeping the existing protected data directory, crew keys and journals. Start it normally, then open **http://127.0.0.1:6970/ui/** on the host machine (or the API port configured in `host-config.json`). Enter the **API token**, not a worker crew key. The token grants full host administration; do not share it with workers or clanmates.

The token stays in memory in the open tab. Reloading or disconnecting requires entering it again. It is not saved in browser storage, cookies or URLs. The read-only demo works without connecting to any workers.

## MVP controls

- Overview and Crews show connection health, native highway progress, supply recovery and exchange state.
- Click a worker name or Manage to open its management desk: resources, live game chat, new work, assigned job actions, worker priority and live module configuration. Click a crew name or Manage for combined resources, crew chat, all assigned jobs, renaming/deletion and membership management. Job pause/resume/cancel still affects the whole selected job, not just the worker whose panel is open.
- Crews can be created directly. Moving workers uses the existing host admission checks; active jobs and unfinished cleanup cannot be orphaned.
- Jobs support pause, resume, cancellation and global or worker-specific priorities. Terminal jobs live separately in History; deletion respects outstanding recovery/cleanup obligations.
- New Job accepts an exported `.bot export-workflow <workflow-id>` JSON package, a simple wait workflow, or custom Lua plus JSON arguments. Select workers in one crew and matching server/dimension. Review uploaded gameplay profiles before dispatching.

Status polls once per second, slowing to five seconds in a hidden tab. Stale snapshots disable writes. If a submission response is lost, **Retry same submission** reuses the identical payload and UUID; it never blindly creates a second job. Cancellation goes through the existing durable host controls, not a browser-only state change.

Chat requires 0.7.54 workers and is independent of an active job. The latest 256 lines are retained globally in host memory, filtered by worker or crew; restart clears the feed. Message bodies are plain text (Minecraft component colors are not preserved); timestamps, sender labels, sent messages and errors use dashboard colors. Slash commands go to the Minecraft server. Crew sending explicitly confirms broadcast through every connected member. Delivery is limited to one command per second per worker, never queued for offline workers, and never automatically retried. A sent receipt confirms submission by the client, not acceptance by the Minecraft server. Uncertain delivery should be inspected before resending. The in-game host uses the same authenticated messages via Manage & chat, showing the latest 40 matching lines. Both hosts remain supported; neither requires the launcher.

Live configuration uses the existing configure command, scoped to a worker or all runs in the selected job. JSON maps module IDs to `{"active":true,"settings":"{}"}` (settings are SNBT strings). Job-owned executors remain protected; client configuration acknowledgements/errors are shown in the panel. This dashboard does not yet provide a visual workflow editor, account launching or permission-scoped sharing.

## Optional remote access

The API still binds only to loopback. Do not expose it directly or port-forward it. For an operator-managed HTTPS reverse proxy, set `"uiOrigin": "https://bots.example.com"` in the protected host config, restart between jobs, and proxy `/ui` and `/ui/*` unchanged to `127.0.0.1:6970`. Preserve the original `Host` and `Origin` headers and the returned security headers. The configured HTTPS origin must match exactly; wildcard origins, insecure remote HTTP and cross-site requests are rejected. Protect access and rate-limit at the perimeter. This is full operator access, not the future shared-bot permission model.

The browser uses only `/ui/api/status` and `/ui/api/control`. Native `/v1/status`, `/v1/control` and legacy `/control` retain their no-browser-Origin policy. Worker WebSockets remain separate; see [web transport](bot-web-transport.md).

The unmodified Glacial Indifference font is bundled with its OFL license and attribution notice under `/ui/GlacialIndifference-OFL.txt` and `/ui/GlacialIndifference-NOTICE.txt`.

## Development checks

Run `./gradlew :host-service:check` with Java 25. For a completely isolated demo host with simulated workers, run `./gradlew :host-service:webUiPreview`; it prints a temporary URL and fake test token. Stop it with Ctrl+C. Optional browser QA lives in `host-service/src/test/webui-browser-check.mjs` and uses an existing Playwright installation only as a development tool, not a runtime dependency.
