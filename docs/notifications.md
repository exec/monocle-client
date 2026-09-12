# Shared notification feed

## Current scope (0.3.8)

All built-in modules now route their ordinary local feedback and toggles through
the common feed destination. The historical **Overhauled Module Output** setting
name is retained to preserve saved preferences, but now covers the entire built-in
catalog rather than only the two batches listed below. Feed remains the default.
External add-ons are not automatically opted in. Notifier categories keep their
own selectors. Swarm's helper-thread messages now use its module's shared path;
network/command execution behavior is unchanged.

Intentional exceptions: IRC conversation/status output remains in chat; actual
server messages and command replies retain their routes. Better Chat's coordinate
protection posts a feed warning (unless Chat-only), and keeps its clickable
confirmation in chat. The feed cannot replace a clickable approval control.
Stash Finder's optional native toast remains separate. No global chat interception
is installed, and no new gameplay notifications are generated.

The verification task now scans every compiled class under the built-in module
package for direct ChatUtils feedback calls; only the common module router,
Community Chat and Better Chat's actionable confirmation are allowed.

Enable Misc → Notifier. Each of its four event categories has an Output setting:
Chat, Feed (default), or Both. The category's existing enable/disable settings
still apply: totems and pearls are on, visual range and joins/leaves are off.
Notifier itself is not automatically enabled.

Configure the shared renderer in Config → Notification Feed:

- Width 240 GUI pixels, right/top offsets 12 GUI pixels.
- Maximum height 40% of the screen, adjustable from 10–100%, with an eight-pixel
  bottom margin. Small windows clamp the width and line count; if a readable card
  cannot fit, rendering is suppressed.
- Six-second lifetime, three body lines, grouping on, feed sound off by default.
- Long bodies wrap and truncate with an ellipsis. Full bounded text remains in
  Recent Notifications (512 code points per body).
- New cards enter beneath older cards. At capacity the oldest cards scroll up
  and disappear permanently from the visible feed. Expiry fades cards and the
  remaining stack closes the gap. Grouping renews lifetime without reordering.
- Severity changes the marker/color, not chronological ordering. There is no
  delayed priority queue.
- F1/hidden HUD hides the feed without stopping expiry. Nothing captures clicks
  or keyboard focus. Disabling the feed suppresses feed-only output, not chat.
- Feed sound is optional and capped at once per second. Notifier's existing
  visual-range sound applies to Chat-only routing; Feed/Both use the shared
  sound control to avoid duplicate sounds.

In Notifier's panel, Preview Notification Feed posts three explicitly labelled
examples. Close the GUI promptly to see them. Recent Notifications opens a
scrollable newest-first snapshot of up to 100 feed updates, including grouped
updates. Reopen to refresh; Clear History and Feed removes both. History is
memory-only and cleared on world leave, not written to disk.

## Module integration

### Existing overhauled modules (0.3.6)

Config → Notification Feed → Overhauled Module Output defaults to Feed.
Chat restores legacy delivery; Both posts to both. This applies to common
module `info`, `warning`, `error` and toggle feedback for this explicit list:

Highway Builder, Printer Helper, Schematic Selector, Stash Finder, Inventory
Manager, Auto Log, Auto Eat, Auto Gap, Auto Mend, Auto Armor, Auto Tool, Kill Aura,
Surround, Scaffold, ElytraFly, Chest Swap, Auto Totem, Auto Replenish, Auto Reconnect,
and Notifier. No notification is invented for modules which have nothing to report.
Notifier's four event categories keep their independent Output selectors.

Toggle feedback obeys the existing global and per-module chat-feedback switches,
even when its destination is Feed. Repeated toggles update one card per module;
ordinary messages remain distinct so unrelated warnings cannot replace each other.
The shared Minecraft formatting parser removes `(highlight)`/`(default)` markup
before feed delivery. Original chat Components/click actions survive in Chat/Both.

Stash Finder's existing notification-mode “Chat” now follows the module output
setting. Its native “Toast” is unchanged, so its default “Both” can display a feed
card and a native toast; select “Chat” there for the feed alone. Full coordinates
remain accessible in the stash notebook and feed history.

Untouched Meteor modules and add-on modules retain their own output. IRC and
command-class responses are not intercepted. A command that calls a migrated
module action can still cause that module's status notification in the feed
(for example a completed schematic export). HUD status, log files, Auto Log's
disconnect reason and Auto Reconnect's disconnect screen are unchanged.

### Second migration batch (0.3.7)

Another 20 modules now use the same Overhauled Module Output setting, bringing
the total to 40: Auto Anvil, Auto City, Burrow, Quiver, Bed Aura, Offhand, Anchor
Aura, Excavator, EChest Farmer, Auto Smelter, Auto Brewer, Spawn Proofer, Auto
Nametag, Infinity Miner, Nuker, Auto Walk, Long Jump, Blink, Auto Wasp and Anti AFK.

Every added module already has local info/warning/error output. Their inherited
message and toggle paths are routed without changing gameplay code. Existing
chat-info switches still suppress messages when disabled. Anti AFK's deliberate
outbound messages remain server messages; only local feedback moves to the feed.
The setting name is retained so saved Chat/Feed/Both preferences survive.

### New consumers

Use explicit notifications for meaningful events; command replies and debugging
output stay in chat. No global chat interception is installed.

```java
// On the client thread; key is scoped to source and updates a matching card.
Notifications.send(output.get(), "Highway Builder", "supplies",
    NotificationFeed.Severity.Warning,
    Component.literal("Supplies running low."));

// Feed-only API accepts plain data and can be called from a worker thread.
Notifications.post("Example module", "job-status",
    NotificationFeed.Severity.Success, "Job complete.");
```

Use an empty key for distinct events. Grouping can be disabled globally. Feed
state is synchronized, capped at 64 active cards and 100 history entries, with
no executor queue. Sources, keys and bodies are length-bounded; formatting and
control codes are removed from feed text. Chat routing preserves Components.
Worker producers must cancel their own stale jobs on disconnect; the feed does
not know which world an arbitrary future module's background task belongs to.

Notifier separately bounds its packet queue to 128 and its delayed join/leave
messages to 100. Packet processing rejects stale module activations and other
connections. Existing join/leave delay applies before routing. Its tracked
player names still depend on the server's player-list packets; this is not a
guarantee of real logins/logouts.

## Checks

`./gradlew notificationFeedCheck` covers layout bounds, grouping/source isolation,
expiration, permanent dismissal, history limits, 10,000-message bursts, concurrent
posting, Unicode/formatting safety, totem ignore filters and the packet callback
boundary. Full rendering, animation, F1, GUI-scale changes, clipping, sound,
preview/history controls and all four actual event categories need live testing.
