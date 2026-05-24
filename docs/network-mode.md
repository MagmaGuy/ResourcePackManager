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

### 1. Install RPM on every backend that has pack-providing plugins

Drop `ResourcePackManager.jar` into each backend's `plugins/` folder and start the server. RPM detects network mode automatically and prints a banner on startup:

```
[ResourcePackManager] ===== NETWORK MODE =====
[ResourcePackManager] Network key: 1f2a3b4c-5d6e-7f8a-9b0c-d1e2f3a4b5c6
[ResourcePackManager] Use this same key on your proxy plugin and any other backends in this network.
[ResourcePackManager] ========================
```

Copy the network key. You'll paste it into the proxy plugin in step 3 and into any other backends in step 2.

### 2. Set the same network key on every other RPM backend

In each additional backend's `plugins/ResourcePackManager/config.yml`, set:

```yaml
networkKey: 1f2a3b4c-5d6e-7f8a-9b0c-d1e2f3a4b5c6
```

Restart the backend. It now uploads its pack tagged with the same network key so the proxy plugin sees both packs in the manifest.

### 3. Copy the proxy plugin to your proxy

On startup, every RPM backend (when in network mode) extracts the bundled proxy plugin jars to `plugins/ResourcePackManager/proxy-extension/` and logs the absolute paths:

```
[ResourcePackManager] ===== PROXY EXTENSION =====
[ResourcePackManager] Proxy plugin jars extracted to:
[ResourcePackManager]   Velocity: /srv/mc/backend1/plugins/ResourcePackManager/proxy-extension/rpm-velocity.jar
[ResourcePackManager]   Bungee:   /srv/mc/backend1/plugins/ResourcePackManager/proxy-extension/rpm-bungee.jar
```

Copy the appropriate file to your proxy:
- **Velocity:** `rpm-velocity.jar` → proxy's `plugins/`
- **BungeeCord / Waterfall:** `rpm-bungee.jar` → proxy's `plugins/`. **Also install [Protocolize](https://www.spigotmc.org/resources/protocolize.63778/)** — Bungee has no native pack-push API, so Protocolize is required.

### 4. Configure the proxy plugin

Start the proxy. The plugin generates a default config at `plugins/ResourcePackManager/config.yml`:

```yaml
network-key: ""
force-resource-pack: false
self-host-port: 25567
self-host-external-host: ""
```

Paste the network key from step 1 into `network-key`, then restart the proxy.

```yaml
network-key: 1f2a3b4c-5d6e-7f8a-9b0c-d1e2f3a4b5c6
```

The plugin starts polling the network manifest every 30 seconds, downloads each backend's pack, mixes them, and pushes the result to clients on connect.

### 5. Verify

After all four steps:
- Proxy console should log `RSPM proxy plugin started (network-key=...)`. and within ~30s: `Merged pack ready at https://magmaguy.com/rsp/network/.../merged (sha1 ...)`.
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

The network key persists in `plugins/ResourcePackManager/data.yml` even if network mode flips off, so re-activating later picks up the same key.

## Caveats and limitations

- **Server-side endpoints required:** Network mode depends on `/rsp/network/<key>/manifest`, `POST /rsp/network/<key>/merged`, and `GET /rsp/network/<key>/merged` endpoints on `magmaguy.com/rsp/`. See [`server-contract.md`](server-contract.md) for the full contract. Until the server side ships these, the proxy plugin's poll silently no-ops and clients receive no pack.
- **Network-key collision:** If two unrelated RPM installations accidentally use the same network-key, their packs get merged together. Treat the key like a shared secret — let the auto-generated UUID stand or set an explicit value you control.
- **Backend re-mixes:** when a backend re-uploads a different pack (different sha1), the proxy detects on its next poll and re-merges within ~30 seconds. New clients see the new pack; existing clients keep the pack they already downloaded until they reconnect.
- **Self-host fallback:** see [`self-host.md`](self-host.md). The proxy automatically falls back to a local HTTP server when uploads to magmaguy.com fail.
- **Protocolize on Bungee:** required, not bundled. The Bungee plugin will warn on startup and continue running with Bedrock-only delivery if Protocolize is missing.
