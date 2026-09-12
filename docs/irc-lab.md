# Temporary LAN community chat

CT137 on eqr6 (10.0.0.246) now runs Ergo 2.19.1 and nginx.
Its current DHCP address is **10.0.0.2**; this is not a reserved/static address.

Route: Monocle → ws://10.0.0.2:8080/irc → nginx → 127.0.0.1:8097 → Ergo.
Only LAN 10.0.0.0/24 can reach the proxy. Raw IRC/WebSocket backend ports
are not exposed. Cloudflare is not involved. This is plaintext LAN testing:
do not send credentials or sensitive information.

## Client

- Join a Minecraft world (singleplayer is fine).
- Run `.chat irc` to select IRC and enable Community Chat; defaults select the LAN endpoint,
  a random independent nickname, and #monocle.
- Type normally to send to IRC. `.chat game` switches typed messages back to
  Minecraft without disconnecting IRC. Slash commands always go to Minecraft;
  local client and Baritone commands retain their normal handling.
- IRC routing remains selected if the connection drops or the module is
  disabled: failed messages are blocked, never silently sent to the game.
  Routing starts in game mode on a new client launch and is not saved.
- Only typed chat is routed; automated module messages are not redirected.
  The chat input shows an [IRC] hint while IRC is selected.
- `.irc say hello` remains an explicit-send shortcut.
- `.irc status` reports connection status; `.irc disconnect` disconnects.
- Endpoint, nickname and channel live in Misc → Community Chat.
  Reconnect to apply changes. Remote endpoints must use wss:// with normal
  certificate validation. Plain ws:// is limited to private IPv4 addresses.
- Disconnecting from the Minecraft world closes IRC. Saved profiles never
  enable it automatically. Connection failures require explicit reconnect.
- Inbound messages render as literal cyan text with an IRC prefix, without
  executable commands, automatic links, or rich-message parsing.

There is no account authentication or verified identity in this test setup.
Channel messages are supported; private messaging, history UI, moderation UI,
mute lists, automatic reconnect and a separate tab are not implemented yet.
Ergo message history is disabled and sample operators are removed.
The client bounds queues, frame size, display output and outbound message rate.

## Server flood protection

The LAN server was tightened on 2026-09-09 using Ergo's native controls:

- `fakelag`: enabled, four-command burst, then one command per second;
  five seconds idle restores the burst allowance. Excess commands are delayed,
  not automatically banned. This is per connection, not per person.
- `server.ip-limits`: six concurrent connections and 24 connection attempts per
  ten minutes per IPv4 address (IPv6 /64). Shared NATs share the allowance.
- nginx overwrites `X-Forwarded-For` with its peer IP; Ergo trusts only localhost
  proxies. Clients cannot provide their own exempt IP through that header.
- Existing account-creation/login throttles, IP cloaking and default channel
  modes `+ntC` remain. Outside-channel messages and channel CTCP are blocked;
  only channel operators can change the topic.

These are flood controls, not duplicate/link/content filtering or a distributed
bot defense. Operators can use UBAN and DEFCON, but an authenticated operator
and ownership of #monocle still need provisioning before a public launch.
No operator credential is embedded in the client. Before adding Cloudflare,
configure trusted real-IP handling at nginx; never blindly trust incoming
forwarded headers or count every user as a Cloudflare IP.

Config backup: `/srv/ergo/ircd.before-spam-20260909.yaml` inside CT137.
Restore it to `/srv/ergo/ircd.yaml` and send SIGHUP to Ergo to roll back.
Rehash succeeded without restarting/disconnecting users. New connections get
the updated per-session fakelag settings.

After compiling the client, run a bounded 12-message check in an isolated
temporary channel (not #monocle):

```sh
java -cp build/classes/java/main docs/IrcFloodCheck.java
```

## Server administration

Run commands inside CT137 with `ssh root@10.0.0.246 'pct exec 137 -- COMMAND'`.

- Services: `ergo.service`, `nginx.service`.
- Ergo installation/config: `/srv/ergo`, `/srv/ergo/ircd.yaml`.
- Proxy config: `/etc/nginx/conf.d/monocle-irc.conf`.
- Logs: `journalctl -u ergo`, `/var/log/nginx/error.log`.
- Deployment files and the previous firewall config: `/root/monocle-irc-lab`.
- Local deployment copy: `/tmp/monocle-irc-lab` (temporary).

Minecraft/Grim services and their socket were stopped and disabled, not deleted.
All Minecraft files remain under /srv/minecraft, including the world and Grim
configuration. To revert this CT to the anticheat lab, stop/disable ergo and
nginx, restore /root/monocle-irc-lab/nftables-before-irc.conf to
/etc/nftables.conf, load it with nft -f, then enable/start minecraft.socket
and minecraft.service. This restores LAN port 25565 and closes 8080.

## Verification

`./gradlew ircCheck` runs offline input-validation and text-sanitization checks.
After `./gradlew testClasses`, this runs the actual transport through the LAN
proxy with two temporary clients:

```sh
java -ea -cp build/classes/java/main:build/classes/java/test \
  dev.monocle.client.utils.network.IrcConnectionTest ws://10.0.0.2:8080/irc
```

The integration check verifies registration, channel join, bidirectional
message delivery and disconnect. In-game UI/command interaction still needs
live testing. The server archive's SHA-256 was checked against the published
release digest: c275f2de8e8eb074c38707136392290172400b5b78616cb9f660351527924b47.
