# Security policy

## Supported versions

Only the newest published Monocle release is supported. Security fixes are not routinely backported.

## Reporting a vulnerability

Do not publish exploitable details in a public issue. Use GitHub's private vulnerability reporting for this repository. Include the affected version, reproduction steps, impact, and any relevant logs with credentials removed.

## Deployment boundary

- Treat crew keys and the standalone host API token as passwords.
- Keep the administrative API on loopback. Do not port-forward it.
- Raw worker TCP is authenticated but not encrypted; restrict it to a trusted LAN or VPN.
- Use `wss://` behind an operator-managed TLS reverse proxy for remote worker traffic.
- Do not accept workflow packages or captured profiles from an untrusted source; they can direct connected workers to perform gameplay actions.
- Remove usernames, server addresses, coordinates, tokens, and crew keys before sharing logs or recovery journals.

Monocle is a utility client for servers where such modifications are permitted. Security reports about bypassing a server's rules or anti-cheat policy are out of scope unless they expose the user's machine, credentials, Monocle host, or other users.
