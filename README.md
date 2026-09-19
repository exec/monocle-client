<p align="center">
  <img src="src/main/resources/assets/monocle-client/icon.png" alt="Monocle Client" width="128" height="128">
</p>

<p align="center"><strong>Monocle: A Clear Advantage.</strong></p>

Monocle is a Minecraft Fabric utility client for anarchy servers where client mods are permitted. It is a fork of [Meteor Client](https://github.com/MeteorDevelopment/meteor-client), with its upstream history, attribution, and GPL-3.0 license retained.

## What Monocle adds

- A server-verified Highway Builder with speculative excavation and paving, supply recovery, managed inventory, and resilient multi-worker operation.
- **Workers**, an in-game control room for crews, jobs, programmable workflows, priorities, telemetry, recovery, and shared resources.
- An optional [standalone host](host-service/README.md) with the same coordinator, an authenticated API, and a bundled operator WebUI.
- Stash cataloging and resupply workflows, schematic selection/export, Printer Helper, shared notifications, and substantial module/UI refinements.

The client remains usable on its own. The standalone host and launcher are optional; workers still perform every Minecraft action in their own game clients.

## Build

Monocle currently targets **Minecraft 26.2**, **Fabric**, and **Java 25**.

```sh
./gradlew build
```

The installable client is written to `build/libs/monocle-v<version>-26.2.jar`. Install exactly one Monocle JAR in the Fabric profile's `mods` folder. The standalone host distribution is written to `host-service/build/distributions/monocle-host-<version>.zip`.

Root `build` compiles the client, coordinator core, and host; runs their automated checks; validates documentation links; and verifies that release JARs contain required bundled libraries and license notices. Minecraft movement, packet timing, inventory behavior, and multi-account recovery still require live testing.

## Documentation

- [Workers control room and crew operation](docs/bots.md)
- [Workflow API](docs/bot-workflows.md)
- [Standalone host](host-service/README.md)
- [Operator WebUI](docs/bot-webui.md)
- [Worker transport and API](docs/bot-web-transport.md)
- [Telemetry and freeze diagnosis](docs/bot-telemetry.md)
- [Stash Manager](docs/stash-manager.md)
- [Incident index](docs/incidents/README.md)
- [Release checklist](docs/RELEASING.md)
- [Change history](CHANGELOG.md)

## Security

Worker and administrative interfaces are trusted-control surfaces, not public services. Keep raw TCP on a trusted LAN or VPN, use TLS for remote WebSocket access, keep crew keys and API tokens private, and read [SECURITY.md](SECURITY.md) before exposing a host.

## Credits and license

Meteor Client and Monocle are licensed under the [GNU General Public License v3.0](LICENSE). Monocle's complete corresponding source must remain available when builds are distributed.

WebSocket transport bundles [Java-WebSocket 1.6.0](https://github.com/TooTallNate/Java-WebSocket), separately licensed under [MIT](licenses/Java-WebSocket-MIT.txt). Air Mine is derived from AirMiner in [Quiettee Utils](https://github.com/FragmentZero6b6t/quiettee-utils), copyright FragmentZero6b6t, under GPL-3.0; its [attribution notice](licenses/Quiettee-Utils-NOTICE.txt) ships with the client.

The bundled Glacial Indifference fonts remain under the SIL Open Font License 1.1. Their [copyright notice](src/main/resources/assets/monocle-client/fonts/GlacialIndifference-NOTICE.txt) and [full license](src/main/resources/assets/monocle-client/fonts/GlacialIndifference-OFL.txt) accompany the font files.
