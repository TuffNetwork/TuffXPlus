
# TuffX+

> [!WARNING]
> This is not a "crack" for Minecraft, it simply allows for better TuffClient integration on servers.

![preview](./img/showcase.png)

TuffX+ is a single, unified plugin that combines:
- Below Y0 *[previousely TuffX]* (Below Y0 support for modern world depth)
- ViaBlocks (custom block palette + chunk updates for modern blocks)
- ViaEntities (modern entity sync without nametags)
- TuffActions (swimming sync + creative item handling + module restrictions)

---

## Installation

### Requirements
- Java 17+
- Spigot/Paper 1.18+
- ViaVersion and ViaBackwards (on the *backend* server *only*; not proxy)

> PacketEvents, Jackson, and WebSocket libraries are shaded into the jar.

### Steps

1. Download the latest release from the [Releases page](https://github.com/TuffNetwork/TuffXPlus/releases) (or the latest beta from [the builds directory](builds)).
2. Drop `TuffXPlus-x.x.x.jar` into your server's `plugins` folder.
3. Start the server to generate `plugins/TuffXPlus/config.yml`.
4. Configure features in `config.yml` (`y0`, `registry`, `viablocks`, `viaentities`, `swimming`, `creative-items`, `restrictions`).
5. Restart the server or run `/tuffx reload`.

---

## Features
- Below Y0: sends extra chunk data for Y < 0 so TuffX client can see and interact with modern world depth.
- ViaBlocks: synchronizes modern block states to TuffX client with a custom palette.
- ViaEntities: syncs modern entities to TuffX client without nametags.
- TuffActions: swimming state sync and creative item handling.
- Restrictions: disallow TuffClient modules: [module list](/docs/restrictions.md)
- Optional server registry over WebSocket (for discovery).

## Commands
- `/tuffx reload` - reload the config
- `/viablocks get` - give a set of custom blocks (creative)
- `/viablocks refresh` - resend ViaBlocks data in view distance
- `/restrictions disallow` - add a module to the disallow list and send an update to all TuffClient clients
- `/restrictions allow` - remove a module from the disallow list and send an update

## Permissions
- `tuffx.reload`
- `tuffx.viablocks.command.get`
- `tuffx.viablocks.command.refresh`
- `tuffx.restrictions.command.disallow`
- `tuffx.restrictions.command.allow`

## Compiling
```sh
git clone https://github.com/TuffNetwork/TuffXPlus.git
cd TuffXPlus
./gradlew build
```

Output jar: `build/libs/TuffXPlus-x.x.x.jar`.

## Support

For general issues, [make an issue here](https://github.com/TuffNetwork/TuffXPlus/issues)

For immediate issues, [join our Discord](https://discord.gg/keWXRC9Jd8) and make a ticket in `#🎫tuff-tickets` with 'Client' type.
