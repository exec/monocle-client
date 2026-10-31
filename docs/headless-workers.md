# Optional headless workers

Monocle 0.15.47 adds an opt-in compatibility path for running a full Minecraft client with [HeadlessMC](https://headlesshq.github.io/headlessmc/launch/), without a display. The usual worker connection, modules and jobs still run inside Minecraft. The standalone host already runs without a display and needs no headless flag.

HeadlessMC's backend is required. `-Djava.awt.headless=true` alone does not make Minecraft windowless, and `-Dmonocle.headless=true` only adjusts Monocle's graphics initialization. Leave that flag off for ordinary graphical play.

## Tested baseline

- Minecraft 26.2, Java 25, Fabric API 0.158.0+26.2, Baritone 26.2 and Monocle 0.15.47.
- Debian x64 with `DISPLAY`, `WAYLAND_DISPLAY` and `XAUTHORITY` unset.
- The [HeadlessMC 3.0.0-RC8 Java agent](https://github.com/headlesshq/headlessmc/releases/tag/3.0.0-RC8). Its release asset `headlessmc-lwjgl-3.0.0-RC8-all.jar` has SHA-256 `45c7a0041d580e7c80a190c49140e65c521e992af13229ca71795f3414146009`.
- A disposable single-player world reached player/world readiness, connected to a separate standalone host, and completed normal host-assigned Wait and walking Travel jobs.

Unmodified Monocle failed during font initialization, followed by an icon-atlas failure after the font fix. Compatibility mode reads font metadata through the JDK, retains placeholder icon geometry, uses vanilla font measurements and skips custom font textures and HUD work. Saved font/HUD preferences remain intact for the next graphical launch.

This is an initial compatibility test, not certification of every module or mod combination. Live-server login, lobby handling, stash/container transfers, elytra navigation, Windows/macOS headless launches and an overnight soak still need testing. Shader/graphics mods such as Sodium and Iris were not included. Start with the minimal mod set above.

## Use an existing launcher

HeadlessMC supports a [standalone Java-agent backend](https://github.com/headlesshq/headlessmc/blob/main/headlessmc-lwjgl/README.md), so an existing launcher can retain its account authentication and normal game installation. Download the pinned agent from its upstream release, keep it outside the profile's `mods` directory, and add these **JVM arguments** to the intended bot instance:

```text
-javaagent:/absolute/path/headlessmc-lwjgl-3.0.0-RC8-all.jar
-Dfabric.systemLibraries=/absolute/path/headlessmc-lwjgl-3.0.0-RC8-all.jar
-Djoml.nounsafe=true
-Djava.awt.headless=true
-Dmonocle.headless=true
```

Replace the example path with the actual absolute path. Quote an entire argument when the launcher requires it for a path containing spaces. If `fabric.systemLibraries` is already configured, preserve its existing entries and add the agent using the platform's path separator (`:` on Linux/macOS, `;` on Windows).

Use a dedicated bot profile/game directory. Keep its worker identity, account login and runtime databases separate from any simultaneously running graphical client. Do not launch two processes against the same game directory or copy an active worker's identity into a second process. Configure the worker's host connection and server auto-join before removing the display; the Java agent itself does not add a command console or configure Monocle's worker connection.

The Monocle launcher has an opt-in **instance Settings → Java → Headless (no display)** checkbox for Fabric 26.2. It downloads and verifies this pinned backend on first launch, then injects these arguments automatically for normal and worker-session launches. Use either the checkbox or the manual agent configuration, not both. The checkbox also writes the recommended instance options below. Older launcher builds can use the manual JVM arguments instead.

For a headless profile, set these entries in Minecraft's `options.txt` while the game is closed:

```text
pauseOnLostFocus:false
onboardAccessibility:false
narrator:0
```

HeadlessMC documents these options to avoid a paused game or startup onboarding. Start with a modest render/simulation distance and frame cap, then measure CPU and worker responsiveness. Headless mode still runs the game simulation and networking; it does not turn Minecraft into a lightweight protocol-only bot.

To return to graphical play, remove the agent and its associated headless JVM arguments. Monocle does not overwrite the saved GUI settings to enable this mode.

## Use the HeadlessMC launcher

HeadlessMC 3's documented launch command supports a Fabric version and extra JVM arguments:

```text
launch --headless --jvm "-Dmonocle.headless=true -Djava.awt.headless=true" fabric 26.2
```

Follow upstream's [installation and launch instructions](https://headlesshq.github.io/headlessmc/launch/) to install Fabric and authenticate the intended account. Its [`--server` option](https://headlesshq.github.io/headlessmc/commands/) can request a server join at startup. This complete launcher command has not been exercised here; the tested path used the Java agent with an existing installation. Do not mix the launcher-managed headless backend with a second manually supplied agent.

The optional [HeadlessMC-specifics mod](https://headlesshq.github.io/headlessmc/specifics/) adds in-game command-console features. Monocle's host-assigned worker jobs do not require it. Use only a compatible version if adding it.

## Reproduce the disposable smoke test

The test fixture is separate from the production client:

```sh
./gradlew monocleFontCheck headlessSmokeJar bundledLibrariesCheck
```

`headlessSmokeJar` writes its fixture to `build/headless-smoke/`, outside the release JAR directory. In a **fresh disposable profile**, install it alongside Monocle and the minimal mod set. Launch with the Java-agent arguments above and:

```text
-Dmonocle.headless.smoke=true
```

Use the synthetic offline single-player username `HeadlessSmoke`. The fixture refuses other usernames, creates a disposable creative/peaceful world and writes `headless-smoke-result.json` in that profile after 40 ready-world ticks. It requires both test and headless flags and must never be installed in a normal profile.

For host integration, initialize a separate temporary standalone host bound to `127.0.0.1`, using different ports and data storage from any production host. Add:

```text
-Dmonocle.headless.smoke.host=/absolute/path/to/disposable-host/host-config.json
```

The fixture reads the temporary host's Default crew key locally and connects through loopback. Submit a Wait job and a nearby walking Travel job using the host's normal controls; require both to complete. The fixture intentionally refuses a non-loopback host configuration. Keep the generated logs, databases, account data and host config outside the source repository, and stop the test client and host afterward.

The offline checks compare headless font metadata against the normal native path for every bundled font and verify that the fixture cannot ship inside the installable client JAR. They do not replace the actual no-display game test.

HeadlessMC is an external MIT-licensed project; its agent is not bundled in Monocle. Obtain it from the upstream release and retain its license if redistributing it.
