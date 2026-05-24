# Network Mode

Network mode lets ResourcePackManager deliver one merged resource pack to both Java and Bedrock players across a multi-server network behind a Velocity, BungeeCord, or Waterfall proxy. Backends mix their plugin packs as usual, but instead of pushing packs to clients themselves, they upload to a registry; a proxy-side companion plugin polls the registry, merges, and pushes the merged pack at proxy login. Bedrock delivery goes through Geyser using the same merged URL.

When network mode is active, Java pack push from backends is silenced — the proxy plugin is the single source of truth for what pack clients see.

## When to use it

| Setup | Use network mode? |
|---|---|
| Single Paper/Spigot server, no proxy | No — leave it off (default behavior unchanged). |
| Velocity/BungeeCord proxy + Floodgate on backends + Geyser on proxy | Yes. |
| Geyser-Spigot installed on backend alongside Floodgate | No — RPM uses its existing per-server flow. |

Detection is automatic: if Floodgate is loaded on the backend AND Geyser-Spigot is NOT, network mode activates. No config flag to flip.

## Architecture

```
Player joins proxy
  │
  ├─ Java client ──┐                      ┌── Bedrock client
  │                ▼                      ▼
  │   ┌──────────────────────────────────────────┐
  │   │  Proxy (Velocity / BungeeCord)           │
  │   │                                          │
  │   │  rpm-velocity.jar / rpm-bungee.jar       │
  │   │   • NetworkSync polls magmaguy.com       │
  │   │   • Downloads each backend's pack        │
  │   │   • Mixes via shared MixEngine           │
  │   │   • Uploads merged → magmaguy.com URL    │
  │   │     (or self-hosts on failure)           │
  │   │                                          │
  │   │  Java push  ───►  PostLoginEvent         │
  │   │                   sendResourcePackOffer  │
  │   │                                          │
  │   │  Bedrock    ───►  Geyser API             │
  │   │                   PackCodec.url(merged)  │
  │   └──────────────────────────────────────────┘
  │                ▲
  │                │ proxy ↔ backend (private net)
  │                │
  ▼                │
┌──────────────────┴───────────┐
│  Backend 1 / 2 / 3           │
│                              │
│  ResourcePackManager.jar     │
│   • Mixes plugin packs       │
│   • Uploads with network-key │
│   • Backend Java push: OFF   │
│   • Bedrock: NOT registered  │
│     locally — proxy owns it  │
└──────────────────────────────┘
```

The bundled Bedrock conversion still runs on every backend so the upload contains both Java and Bedrock assets in one pack; the proxy plugin's Geyser binder serves the merged URL to Bedrock clients and Geyser fetches it on the proxy JVM.

## Setup

Drop the jars, restart, done. No config files to edit.

1. **Backend(s):** drop `ResourcePackManager.jar` into each backend's `plugins/`. On first boot it detects network mode (Floodgate present, no Geyser-Spigot) and extracts `rpm-velocity.jar` and `rpm-bungee.jar` to `plugins/ResourcePackManager/proxy-extension/`. Log line tells you the absolute path.

2. **Proxy:** copy the appropriate proxy jar from any backend's `proxy-extension/` folder to your proxy's `plugins/`. Restart the proxy.
   - **Velocity:** `rpm-velocity.jar`
   - **BungeeCord / Waterfall:** `rpm-bungee.jar`, plus [Protocolize](https://www.spigotmc.org/resources/protocolize.63778/) (Bungee has no native pack-push API).

3. **Done.** RPM derives the network-key automatically from your Floodgate `key.pem` (the file you already shared between proxy and backends for Floodgate's own proxy-mode auth). Same key.pem → same network-key → all components on the same network. No config to edit.

## Advanced: override the network-key explicitly

If you need to split one Floodgate network into multiple RPM networks (or merge multiple Floodgate networks into one), set an explicit network-key:

- **Backend:** `plugins/ResourcePackManager/config.yml` → `networkKey: "your-shared-string"`
- **Proxy plugin:** `plugins/ResourcePackManager/config.yml` → `network-key: "your-shared-string"`

Use the same value everywhere. This overrides the auto-derive.

## Verify

After dropping the jars and restarting:
- Proxy console should log `RSPM proxy plugin started (network-key=...)` and within ~30s: `Merged pack ready at https://magmaguy.com/rsp/network/.../merged (sha1 ...)`.
- A Java player connecting via the proxy gets a pack prompt at proxy login.
- A Bedrock player connecting via Geyser sees the merged pack on join.
- Switching backend servers on either client does NOT re-prompt (proxy pushes once; subsequent server connects are no-ops for the same pack).

## Multi-backend gotchas

**Bedrock pack handshake happens once per session, at proxy login.** This is a Bedrock protocol limitation — Geyser cannot swap packs when a Bedrock player switches servers. So the pack served at proxy login is the only pack a Bedrock player sees during their session, regardless of which backend they're on.

If your backends have **different plugin sets**, the merged pack contains everything. That's the point of the merge — Bedrock players on any backend can see models from every backend.

If you want **strictly per-server packs on Bedrock** (different content per server), RPM can't deliver that. [GeyserPackSync](https://github.com/onebeastchris/GeyserPackSync) uses Bedrock's transfer-packet trick to force-reconnect Bedrock clients on backend switch — at the cost of a visible disconnect+reconnect to the player. Use it alongside RPM if that UX trade-off is acceptable.

For Java, the proxy pushes one pack per network. Backends are silent in network mode. If you need different packs per backend on Java, you'd disable network mode and use legacy single-server behavior on each backend — but you'd lose the unified proxy-side delivery.

## Reverting to single-server mode

To go back to non-network behavior, remove Floodgate from the backend OR add Geyser-Spigot. Detection will flip off network mode. Backend RPM resumes pushing packs directly to Java players via `setResourcePack`, and the proxy plugin's polls return harmlessly empty.

Re-activating later derives the same network-key again from the same Floodgate `key.pem`, so existing network state lines up automatically.

## Caveats and limitations

- **Server-side endpoints required:** Network mode depends on `/rsp/network/<key>/manifest`, `POST /rsp/network/<key>/merged`, and `GET /rsp/network/<key>/merged` endpoints on `magmaguy.com/rsp/`. See [`server-contract.md`](server-contract.md) for the full contract. Until the server side ships these, the proxy plugin's poll silently no-ops and clients receive no pack.
- **Network-key collision:** If two unrelated RPM installations accidentally use the same network-key, their packs get merged together. With the default Floodgate-derived key this is essentially impossible — separate Floodgate networks already have distinct `key.pem` files. If you override the key manually, treat it like a shared secret and pick something unique.
- **Backend re-mixes:** when a backend re-uploads a different pack (different sha1), the proxy detects on its next poll and re-merges within ~30 seconds. New clients see the new pack; existing clients keep the pack they already downloaded until they reconnect.
- **Self-host fallback:** see [`self-host.md`](self-host.md). The proxy automatically falls back to a local HTTP server when uploads to magmaguy.com fail.
- **Protocolize on Bungee:** required, not bundled. The Bungee plugin will warn on startup and continue running with Bedrock-only delivery if Protocolize is missing.
- **Bungee minimum Minecraft version:** the Bungee proxy plugin's Protocolize-driven Java pack push has packet-ID mappings for **Minecraft 1.8 through 1.21.4**. Clients on newer Minecraft versions (1.21.5+) will not receive the Java pack push from a Bungee proxy until the Protocolize dependency bundled with this plugin is updated to cover the new protocol IDs. Velocity has no such cap — Velocity uses its native `sendResourcePackOffer` API which is version-agnostic.
