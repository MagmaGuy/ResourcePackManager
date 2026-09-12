# ResourcePackManager

[Download](https://nightbreak.io/plugin/resourcepackmanager/) · [Modrinth](https://modrinth.com/plugin/resourcepackmanager) · [Documentation](https://wiki.nightbreak.io/) · [Support](https://discord.gg/nightbreak)

ResourcePackManager (RSPM) is a universal Bukkit/Paper, Velocity, and
BungeeCord plugin. On backend servers it merges the resource packs of
every plugin into a single pack, hosts it, and pushes it to players
automatically. On proxies it runs the network-side pack delivery bridge for
Bedrock players through GeyserMC. The same release jar also contains RSPM's
Geyser extension entrypoint; there is no separately versioned bridge jar.

By default the merged pack is hosted on a local HTTP server embedded in the
plugin; if that is not reachable it falls back to uploading the pack to
magmaguy.com and pushing that URL to clients. Either way, players receive the
combined pack without you having to host or stitch anything together by hand.

## Key features

- **Automatic pack merging**: collects the resource packs supplied by other
  installed plugins and merges them into one pack, resolving file conflicts by a
  configurable `priorityOrder`.
- **Self-host first, remote fallback**: serves the pack from an embedded HTTP
  server when reachable, otherwise uploads to magmaguy.com and pushes that URL.
- **Bedrock conversion**: converts the merged Java pack to a Bedrock pack and
  hands it to Geyser (`GeyserPackProvider`), optionally auto-deploying it to the
  Geyser packs folder.
- **Network mode**: on a Velocity/BungeeCord network the proxy module pulls each
  backend's Bedrock pack over HTTP and serves it to Bedrock players network-wide.
- **Custom mixer input**: drop additional `.zip` packs into the `mixer/` folder
  to have them merged in alongside plugin packs.
- **Per-integration exclusion**: disable an automatically discovered plugin
  pack without affecting manually managed mixer inputs.
- **Verified universal updates**: update candidates are checked as complete
  universal jars before being staged for a backend, proxy, or Geyser restart.
- **Operator diagnostics**: `/rspm status` dumps pack state, hosting mode,
  resolved external host, and integration presence in one shot.
- **Data compliance**: `/rspm data_compliance_request` packages all data the
  autohost holds for your server (see the bundled `ReadMe.txt` data policy).

## Modules

This is a multi-module Maven project (parent artifact `ResourcePackManager-parent`).

| Module | Purpose |
| --- | --- |
| `resourcepackmanager-bukkit` | Bukkit/Paper entrypoint and final assembly module. Produces the one universal deployable jar and shades MagmaCore plus the internal modules below. |
| `resourcepackmanager-bridge-common` | Platform-neutral inspection, version validation, and durable publication of the universal jar. |
| `resourcepackmanager-bedrock` | Java → Bedrock resource pack conversion pipeline. |
| `resourcepackmanager-mixer` | Platform-neutral Java/Bedrock pack merging, conflict resolution, deterministic proxy merging, and exact-texture optimization. |
| `resourcepackmanager-geyser-bridge` | Internal Geyser extension entrypoint included in the universal jar. |
| `resourcepackmanager-http-common` | Shared HTTP server/client code used for self-hosting and proxy fetches. |
| `resourcepackmanager-proxy-common` | Shared proxy logic for network sync, Geyser deployment, and verified proxy updates. |
| `resourcepackmanager-velocity` | Internal Velocity adapter included in the universal jar. |
| `resourcepackmanager-bungee` | Internal BungeeCord adapter included in the universal jar. |
| `resourcepackmanager-system-tests` | Real-proxy system-test procedures and fixtures; not a Maven module. |

### One universal artifact

`ResourcePackManager.jar` contains all four platform descriptors and
entrypoints:

- `plugin.yml` for Bukkit/Paper;
- `velocity-plugin.json` for Velocity;
- `bungee.yml` for BungeeCord; and
- `extension.yml` for Geyser.

The internal Maven modules remain separate source and test boundaries, but they
are not separate public downloads. The final jar contains no nested adapter
jars. Installation and update code validates the descriptors, entrypoints,
version, size, and SHA-256 before publishing executable bytes. Proxy updates
stage the exact same candidate for the proxy root and Geyser instead of
building or downloading a secondary bridge artifact.

### Merge guarantees

- The configured priority order remains authoritative. When complete input
  archives are byte-identical, RSPM keeps the first/highest-priority copy and
  skips only the redundant extraction and collision work.
- Bedrock converter interchange files stay in the merged staging tree for
  conversion but are omitted from the Java-client download.
- Exact duplicate Bedrock textures may share one file only after structural
  JSON references are rewritten. Ambiguous, embedded, malformed, or opaque
  references keep their original alias.
- Resource-pack rerouting copies the already completed Java archive rather than
  recompressing a second, potentially different archive.

## Requirements

- Build with JDK 21. Run the Java version required by your backend or proxy. The Maven build emits Java 17-compatible plugin bytecode; that does not lower the Minecraft server's own Java requirement.
- A Bukkit/Paper server. `plugin.yml` declares `api-version: 1.21.4`.
- MagmaCore (shaded into the Bukkit jar; no separate install).
- For Bedrock support: **GeyserMC** on the backend or proxy where Bedrock players connect, and
  **Floodgate** for network mode: the network key is auto-derived from
  `plugins/floodgate/key.pem`, so the same `key.pem` must be shared across the
  whole network (Floodgate requires this anyway).

All other plugin integrations are soft dependencies: RSPM merges their packs if
present and does nothing if absent (e.g. EliteMobs, FreeMinecraftModels,
ModelEngine, Nova, Oraxen, ItemsAdder, Nexo, BetterHUD, ValhallaMMO,
RealisticSurvival, and others listed in `plugin.yml`).

## Installation

1. Put `ResourcePackManager.jar` in the `plugins/` folder of **each backend**
   (game) server and start it once to generate `plugins/ResourcePackManager/config.yml`.
2. For Bedrock delivery on a single server, install Geyser on that backend. On a proxy network, install Geyser on the proxy and configure Floodgate identity/key forwarding for the network.
3. **Network mode only**: also put the same `ResourcePackManager.jar` in the
   proxy's `plugins/` folder. It detects Velocity vs BungeeCord from
   the platform loader. The proxy generates its own `config.yml` on first start.

A single (non-networked) backend server needs only that same jar. Do not install
or distribute a separate RSPM Geyser bridge; RSPM installs/stages its universal
artifact for Geyser when required.

On network upgrades, an authenticated proxy can fetch the public universal
artifact offered by an updated backend, verify its version, size, and SHA-256
against Nightbreak, and apply that exact jar to both the proxy plugin and
Geyser's extension update queue during shutdown. Geyser removes any legacy
`ResourcePackManager-GeyserBridge.jar` with the same extension ID before loading
the universal replacement on the next start.

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
| `geyserExtensionAutoInstall` | `true` | Install/update the universal RSPM jar as a Geyser extension for custom Bedrock entity models. Set `false` to prevent future staging; remove existing extension jars while Geyser is stopped. |
| `bedrockAutoDeployToGeyser` | `true` | Auto-deploy the Bedrock pack to the Geyser packs folder. |
| `bedrockGeyserFolder` | `""` | Path to the Geyser packs folder; empty = auto-detect. |
| `bedrockConverterDebug` | `false` | Verbose per-item conversion logging. |
| `selfHostEnabled` | `true` | Start a local HTTP server to serve the pack instead of uploading. |
| `selfHostPort` | `-1` | HTTP port; `-1` = Minecraft port + `networkHttpOffset-v2`. |
| `networkHttpOffset-v2` | `1` | Fallback offset used when `selfHostPort` is auto-derived; the backend announces its actual HTTP port to proxies automatically. |
| `selfHostExternalHost` | `""` | Public host/IP clients use to reach the self-host server; empty = auto-detect. |
| `selfHostExternalUrl` | `""` | Complete public HTTP(S) pack URL for self-hosting behind a reverse proxy; include the path clients must request. |
| `selfHostForce` | `false` | Force self-hosting, bypassing all other delivery paths (testing). |
| `preferSelfHost` | `true` | Try self-host first and fall back to remote upload only if reachability checks fail. |

### Adding and troubleshooting a manual pack

1. Confirm the pack works by itself on the same Minecraft client version. If it
   relies on client features such as OptiFine CEM/CIT or equivalent client mods,
   every player still needs that support; RSPM merges and distributes pack files
   but does not add client-side rendering features.
2. Put the original `.zip` in `plugins/ResourcePackManager/mixer/`. The archive
   must be a normal resource-pack ZIP: `pack.mcmeta`, `pack.png` (if present),
   and `assets/` belong at the archive root, not inside an extra wrapper folder.
3. Add the exact ZIP filename, including `.zip`, to `priorityOrder` in
   `plugins/ResourcePackManager/config.yml`. The first entry has the highest
   priority. Manual ZIPs omitted from the list are still merged, at the lowest
   priority (ties use a stable filename order).
4. Run `/rspm reload`, then inspect
   `plugins/ResourcePackManager/output/ResourcePackManager_RSP.zip` and
   `plugins/ResourcePackManager/collision_log.txt` (created when collisions occur).

For ordinary files and non-mergeable JSON (models, blockstates, equipment, and
similar fixed structures), the higher-priority pack's complete file wins.
RSPM only combines JSON formats that can be merged safely, such as language,
sound, font, atlas, and supported item-definition files. Two packs that replace
the same entity model or texture therefore need the intended winner above the
other pack, or a compatibility pack authored for those two packs.

For diagnosis, set `verboseLogging: true`, run `/rspm reload`, and include the
two input ZIPs, `config.yml`, `collision_log.txt`, and the generated
`ResourcePackManager_RSP.zip` with a report. A server log can establish the RSPM
version and whether the mix completed, but it cannot by itself identify a
file-level content conflict.

### Excluding a plugin's resource pack

Every automatically discovered compatible plugin has its own file under
`plugins/ResourcePackManager/compatible_plugins/`. To exclude that plugin's
entire resource pack, set `isEnabled: false` in its file and then run
`/rspm reload` or restart the server. For example:

```yaml
# plugins/ResourcePackManager/compatible_plugins/elitemobs.yml
isEnabled: false
```

Disabling an integration prevents its local, remote, shared, and API-registered
pack sources from being watched, downloaded, staged, or merged. RSPM also
removes generated mixer artifacts left by an earlier enabled run. Removing a
plugin from `priorityOrder` does **not** exclude it; that setting only decides
which pack wins file conflicts. For a pack you manually placed in the `mixer/`
folder, remove or move that ZIP instead.

RSPM does not currently support keeping one source pack in the Java merge while
excluding only that source from Bedrock conversion. The converter consumes the
complete merged Java pack, after source packs have already been combined. This
is the same on a single server and in network mode; proxy configuration does not
change source-pack exclusion. To maintain different Java and Bedrock contents,
disable RSPM's Bedrock conversion and install a separately maintained Bedrock
pack through Geyser.

Proxy config (`config.yml` in the proxy plugin's data folder):

| Key | Default | Description |
| --- | --- | --- |
| `network-http-offset-v2` | `1` | Fallback offset used only before a backend endpoint announcement is available. In normal operation the backend announces the exact HTTP port it bound. |
| `geyser-extension-auto-install` | `true` | Install/update the universal RSPM jar as a Geyser extension. Set `false` to prevent both startup installation and update staging. |

The proxy `network-key` is auto-derived from `plugins/floodgate/key.pem`; there is
no manual key to paste. RSPM's proxy plugin does not control pack acceptance:
the backend `forceResourcePack` option applies only to Java pack offers sent by
Bukkit, while Geyser owns Bedrock acceptance through its
`force-resource-packs` setting.

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

Requires JDK 21 and Maven. Build from the repository root so the Velocity and
Bungee adapters are rebuilt before the final jar is shaded:

```powershell
$env:MC_DIST_DIR = 'C:/path/to/MineCraftProjects/dist'
mvn -DskipTests package
```

This builds every module. The main backend jar is produced at:

```
resourcepackmanager-bukkit/target/ResourcePackManager.jar
```

When `MC_DIST_DIR` is set, the same artifact is mirrored as
`dist/ResourcePackManager.jar`. This is the only deployable ResourcePackManager
jar. Adapter modules still produce internal `target/` jars for reactor and
test wiring, but public distribution uses the universal artifact for
Bukkit/Paper, Velocity, BungeeCord, and Geyser.

Before publishing, verify that `plugin.yml`, `velocity-plugin.json`,
`bungee.yml`, and `extension.yml` all contain the parent POM version.

## Developer API

Resource-pack registration on the Bukkit backend: [ResourcePackManager developer reference](https://wiki.nightbreak.io/ResourcePackManager/api). See the [Java API index](https://wiki.nightbreak.io/developers) for dependency setup and lifecycle guidance.

Maven: `com.magmaguy:ResourcePackManager:2.4.0` from [MagmaGuy's repository](https://repo.magmaguy.com/releases). Use `provided` or `compileOnly` scope for the installed plugin.

## Troubleshooting

Start with `/rspm status` and the complete merge/hosting log. Verify that a player's machine can reach the actual pack URL. A locally reachable HTTP port does not establish that it is reachable through a firewall, proxy, or hosting provider.

For incorrect textures, collect the source packs, generated merged pack, collision log, and priority configuration. For Bedrock network problems, include backend and proxy versions, Geyser/Floodgate placement, and both sides' logs. Keep Floodgate keys, Nightbreak tokens, and other credentials private.

## Data policy and licensing

The bundled [autohost data policy](resourcepackmanager-bukkit/src/main/resources/ReadMe.txt) explains hosting and data retrieval. `/rspm data_compliance_request` includes a copy in its exported data. Resource packs retain the licenses of their constituent content; merging does not grant new distribution rights. No repository-wide license file is currently included.
