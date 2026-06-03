# ResourcePackManager

ResourcePackManager (RSPM) is a Bukkit/Paper plugin that merges the resource packs
of every plugin on your server into a single pack, hosts it, and pushes it to
players automatically. It also converts the merged pack to Bedrock format and
serves it to Bedrock players through GeyserMC, with optional proxy modules
(Velocity / BungeeCord) for delivering Bedrock packs across a network.

By default the merged pack is hosted on a local HTTP server embedded in the
plugin; if that is not reachable it falls back to uploading the pack to
magmaguy.com and pushing that URL to clients. Either way, players receive the
combined pack without you having to host or stitch anything together by hand.

## Key features

- **Automatic pack merging** — collects the resource packs supplied by other
  installed plugins and merges them into one pack, resolving file conflicts by a
  configurable `priorityOrder`.
- **Self-host first, remote fallback** — serves the pack from an embedded HTTP
  server when reachable, otherwise uploads to magmaguy.com and pushes that URL.
- **Bedrock conversion** — converts the merged Java pack to a Bedrock pack and
  hands it to Geyser (`GeyserPackProvider`), optionally auto-deploying it to the
  Geyser packs folder.
- **Network mode** — on a Velocity/BungeeCord network the proxy module pulls each
  backend's Bedrock pack over HTTP and serves it to Bedrock players network-wide.
- **Custom mixer input** — drop additional `.zip` packs into the `mixer/` folder
  to have them merged in alongside plugin packs.
- **Operator diagnostics** — `/rspm status` dumps pack state, hosting mode,
  resolved external host, and integration presence in one shot.
- **Data compliance** — `/rspm data_compliance_request` packages all data the
  autohost holds for your server (see the bundled `ReadMe.md` data policy).

## Modules

This is a multi-module Maven project (parent artifact `ResourcePackManager-parent`).

| Module | Purpose |
| --- | --- |
| `resourcepackmanager-bukkit` | The main Bukkit/Paper plugin: merging, hosting, commands, Geyser integration. Shades MagmaCore. |
| `resourcepackmanager-bedrock` | Java → Bedrock resource pack conversion pipeline. |
| `resourcepackmanager-mixer` | Pack merging / conflict-resolution logic. |
| `resourcepackmanager-http-common` | Shared HTTP server/client code used for self-hosting and proxy fetches. |
| `resourcepackmanager-proxy-common` | Shared proxy logic (network sync, status rendering) used by both proxy platforms. |
| `resourcepackmanager-velocity` | Velocity proxy plugin for network mode. |
| `resourcepackmanager-bungee` | BungeeCord proxy plugin for network mode. |

## Requirements

- Java 17 (compiler `source`/`target` 17).
- A Bukkit/Paper server. `plugin.yml` declares `api-version: 1.21.4`.
- MagmaCore (shaded into the Bukkit jar; no separate install).
- For Bedrock support: **GeyserMC** on the backend (`Geyser-Spigot`) and
  **Floodgate** for network mode — the network key is auto-derived from
  `plugins/floodgate/key.pem`, so the same `key.pem` must be shared across the
  whole network (Floodgate requires this anyway).

All other plugin integrations are soft dependencies — RSPM merges their packs if
present and does nothing if absent (e.g. EliteMobs, FreeMinecraftModels,
ModelEngine, Nova, Oraxen, ItemsAdder, Nexo, BetterHUD, ValhallaMMO,
RealisticSurvival, and others listed in `plugin.yml`).

## Installation

1. Put `ResourcePackManager.jar` in the `plugins/` folder of **each backend**
   (game) server and start it once to generate `plugins/ResourcePackManager/config.yml`.
2. For Bedrock delivery, install Geyser on the backend (and Floodgate if you run
   a proxy network).
3. **Network mode only** — also install the matching proxy jar on the proxy:
   - Velocity: `resourcepackmanager-velocity-*.jar`
   - BungeeCord: `resourcepackmanager-bungee-*.jar`
   The proxy generates its own `config.yml` on first start.

A single (non-networked) backend server needs only the Bukkit jar.

## Configuration

Backend config lives at `plugins/ResourcePackManager/config.yml`. Selected keys
(all generated with inline comments by the plugin):

| Key | Default | Description |
| --- | --- | --- |
| `priorityOrder` | plugin list | Merge order, highest priority first. Add a mixer `.zip` filename to position it. |
| `autoHost` | `true` | Upload the pack to magmaguy.com when self-hosting is not used. |
| `forceResourcePack` | `false` | Force clients to accept the pack. |
| `resourcePackPrompt` | text | Prompt shown to clients. |
| `resourcePackRerouting` | `""` | Optional: copy the merged pack to a custom directory (e.g. to host with another plugin). |
| `bedrockConversionEnabled` | `true` | Convert the merged Java pack to Bedrock for Geyser. |
| `bedrockAutoDeployToGeyser` | `true` | Auto-deploy the Bedrock pack to the Geyser packs folder. |
| `bedrockGeyserFolder` | `""` | Path to the Geyser packs folder; empty = auto-detect. |
| `bedrockConverterDebug` | `false` | Verbose per-item conversion logging. |
| `selfHostEnabled` | `true` | Start a local HTTP server to serve the pack instead of uploading. |
| `selfHostPort` | `-1` | HTTP port; `-1` = Minecraft port + `networkHttpOffset-v2`. |
| `networkHttpOffset-v2` | `1` | Offset added to the MC port to derive the HTTP port (fits narrow hosting port ranges). |
| `selfHostExternalHost` | `""` | Public host/IP clients use to reach the self-host server; empty = auto-detect. |
| `selfHostForce` | `false` | Force self-hosting, bypassing all other delivery paths (testing). |
| `preferSelfHost` | `true` | Try self-host first and fall back to remote upload only if reachability checks fail. |

Proxy config (`config.yml` in the proxy plugin's data folder):

| Key | Default | Description |
| --- | --- | --- |
| `force-resource-pack` | `false` | Force clients to accept the pack (kick on decline). |
| `network-http-offset-v2` | `1` | Offset to each backend's MC port to derive the HTTP port the proxy fetches from. Must match each backend's `networkHttpOffset-v2`. |

The proxy `network-key` is auto-derived from `plugins/floodgate/key.pem`; there is
no manual key to paste.

## Commands and permissions

Base command: `/resourcepackmanager` (alias `/rspm`). All subcommands require the
`resourcepackmanager.*` permission.

| Command | Description |
| --- | --- |
| `/rspm reload` | Reload the plugin. |
| `/rspm status` | Show current pack state, hosting mode, config, and integrations. |
| `/rspm data_compliance_request` | Download a copy of all data the autohoster holds for this server. |
| `/rspm itemsadder <configure\|dismiss>` | Configure the ItemsAdder integration. |

## Building from source

Requires JDK 17 and Maven.

```sh
mvn clean package
```

This builds every module. The main backend jar is produced at:

```
resourcepackmanager-bukkit/target/ResourcePackManager.jar
```

The proxy jars are at `resourcepackmanager-velocity/target/resourcepackmanager-velocity-*.jar`
and `resourcepackmanager-bungee/target/resourcepackmanager-bungee-*.jar`.

## Links

- Spigot: https://www.spigotmc.org/resources/resource-pack-manager.118574/

## License

No license file is present in this repository. ResourcePackManager is developed by
MagmaGuy for the Nightbreak game studio; see
`resourcepackmanager-bukkit/src/main/resources/ReadMe.md` for the autohost data
policy and terms of service.
