# Network Mode

Network mode lets ResourcePackManager deliver one merged resource pack to both Java and Bedrock players across a multi-server network behind a Velocity, BungeeCord, or Waterfall proxy. Backends mix their plugin packs as usual and upload to a registry; the proxy plugin polls the registry, merges, and republishes the merged pack URL. Bedrock delivery goes through Geyser using that merged URL.

Java pack push is handled by **the backends**, not the proxy: every backend pushes the same network-merged URL via `Player.setResourcePack` at `PlayerJoinEvent`. Since 1.20.3+ Java clients dedupe repeat pushes of the same `(URL, sha1, UUID)` as a no-op, the client sees exactly one pack prompt at first backend connect — no re-prompts on `/server` switches. Bedrock delivery still happens on the proxy because Bedrock's pack handshake only fires once at proxy login.

## When to use it

| Setup | Use network mode? |
|---|---|
| Single Paper/Spigot server, no proxy | No — leave it off (default behavior unchanged). |
| Velocity/BungeeCord proxy + Floodgate on backends + Geyser on proxy | Yes. |
| Geyser-Spigot installed on backend alongside Floodgate | No — RPM uses its existing per-server flow. |

Detection is automatic: if Floodgate is loaded on the backend AND Geyser-Spigot is NOT, network mode activates. No config flag to flip.

## Architecture

```
                  Bedrock client
                       ▲
                       │ (Geyser pack handshake at proxy login)
                       │
   ┌───────────────────┴──────────────────────────────┐
   │  Proxy (Velocity / BungeeCord)                   │
   │                                                  │
   │  ResourcePackManager-Velocity.jar /              │
   │  ResourcePackManager-BungeeCord.jar              │
   │   • NetworkSync polls magmaguy.com               │
   │   • Downloads each backend's pack                │
   │   • Mixes via shared MixEngine                   │
   │   • Uploads merged → magmaguy.com URL            │
   │     (or self-hosts on failure)                   │
   │   • Bedrock-only: Geyser PackCodec.url(merged)   │
   │   • Java push: NONE (backends do it)             │
   └─────────────────────────┬────────────────────────┘
                             │ proxy ↔ backend (private net)
                             │
   Java client ◄──────┐      │
                      │      │
   ┌──────────────────┴──────┴────────┐
   │  Backend 1 / 2 / 3               │
   │                                  │
   │  ResourcePackManager.jar         │
   │   • Mixes plugin packs           │
   │   • Uploads with network-key     │
   │   • NetworkManifestPoll caches   │
   │     the network-merged URL+sha1  │
   │   • PlayerJoinEvent:             │
   │     pushes merged URL via        │
   │     Player.setResourcePack       │
   │   • Bedrock: NOT registered      │
   │     locally — proxy owns it      │
   └──────────────────────────────────┘
```

Every backend pushes the **same** `(URL, sha1, UUID)`. 1.20.3+ Java clients treat repeat pushes of an identical triple as a no-op, so the client sees one prompt at first backend connect, zero re-prompts on `/server` switches. The bundled Bedrock conversion still runs on every backend so the upload contains both Java and Bedrock assets in one pack; the proxy plugin's Geyser binder serves the merged URL to Bedrock clients and Geyser fetches it on the proxy JVM.

If the server-side `/rsp/network/<key>/manifest` endpoint isn't shipped yet, `NetworkManifestPoll` logs a single INFO line and leaves its cache empty; backends fall back to pushing their own per-backend pack URL (divergent across backends — client re-prompts on `/server`) until the endpoint goes live, then automatically switch to the merged URL on the next poll cycle.

## Setup

Drop the jars, restart, done. No config files to edit.

1. **Backend(s):** drop `ResourcePackManager.jar` into each backend's `plugins/`. On first boot it detects network mode (Floodgate present, no Geyser-Spigot) and extracts `ResourcePackManager-Velocity.jar` and `ResourcePackManager-BungeeCord.jar` to `plugins/ResourcePackManager/proxy-extension/`. Log line tells you the absolute path.

2. **Proxy:** copy the appropriate proxy jar from any backend's `proxy-extension/` folder to your proxy's `plugins/`. Restart the proxy.
   - **Velocity:** `ResourcePackManager-Velocity.jar`
   - **BungeeCord / Waterfall:** `ResourcePackManager-BungeeCord.jar` (no extra plugins required — the proxy plugin is Bedrock-only; Java pack push happens on backends).

3. **Done.** RPM derives the network-key automatically from your Floodgate `key.pem` (the file you already shared between proxy and backends for Floodgate's own proxy-mode auth). Same key.pem → same network-key → all components on the same network. No config to edit.

## Advanced: override the network-key explicitly

If you need to split one Floodgate network into multiple RPM networks (or merge multiple Floodgate networks into one), set an explicit network-key:

- **Backend:** `plugins/ResourcePackManager/config.yml` → `networkKey: "your-shared-string"`
- **Proxy plugin:** `plugins/ResourcePackManager/config.yml` → `network-key: "your-shared-string"`

Use the same value everywhere. This overrides the auto-derive.

## Verify

After dropping the jars and restarting:
- Proxy console should log `RSPM proxy plugin started (network-key=...)` and within ~30s: `Merged pack ready at https://magmaguy.com/rsp/network/.../merged (sha1 ...)`.
- A Java player connecting to a backend gets a pack prompt at first backend join.
- A Bedrock player connecting via Geyser sees the merged pack on join.
- Switching backend servers on either client does NOT re-prompt — every backend pushes the same merged `(URL, sha1, UUID)` and 1.20.3+ clients dedupe identical pushes as a no-op.

## Multi-backend gotchas

**Bedrock pack handshake happens once per session, at proxy login.** This is a Bedrock protocol limitation — Geyser cannot swap packs when a Bedrock player switches servers. So the pack served at proxy login is the only pack a Bedrock player sees during their session, regardless of which backend they're on.

If your backends have **different plugin sets**, the merged pack contains everything. That's the point of the merge — Bedrock players on any backend can see models from every backend.

If you want **strictly per-server packs on Bedrock** (different content per server), RPM can't deliver that. [GeyserPackSync](https://github.com/onebeastchris/GeyserPackSync) uses Bedrock's transfer-packet trick to force-reconnect Bedrock clients on backend switch — at the cost of a visible disconnect+reconnect to the player. Use it alongside RPM if that UX trade-off is acceptable.

For Java, every backend pushes the same network-merged pack. If you need different packs per backend on Java, you'd disable network mode and use legacy single-server behavior on each backend — but you'd lose the unified network delivery.

## Reverting to single-server mode

To go back to non-network behavior, remove Floodgate from the backend OR add Geyser-Spigot. Detection will flip off network mode. Backend RPM resumes pushing packs directly to Java players via `setResourcePack`, and the proxy plugin's polls return harmlessly empty.

Re-activating later derives the same network-key again from the same Floodgate `key.pem`, so existing network state lines up automatically.

## Caveats and limitations

- **Server-side endpoints required:** Network mode depends on `/rsp/network/<key>/manifest`, `POST /rsp/network/<key>/merged`, and `GET /rsp/network/<key>/merged` endpoints on `magmaguy.com/rsp/`. See [`server-contract.md`](server-contract.md) for the full contract. Until the server side ships these, the proxy plugin's poll silently no-ops and clients receive no pack.
- **Network-key collision:** If two unrelated RPM installations accidentally use the same network-key, their packs get merged together. With the default Floodgate-derived key this is essentially impossible — separate Floodgate networks already have distinct `key.pem` files. If you override the key manually, treat it like a shared secret and pick something unique.
- **Backend re-mixes:** when a backend re-uploads a different pack (different sha1), the proxy detects on its next poll and re-merges within ~30 seconds. New clients see the new pack; existing clients keep the pack they already downloaded until they reconnect.
- **Self-host fallback:** see [`self-host.md`](self-host.md). The proxy automatically falls back to a local HTTP server when uploads to magmaguy.com fail.
- **No proxy-side Minecraft version caps:** Java pack push is done by backends via the standard `Player.setResourcePack` API, so any Minecraft version Paper/Spigot supports works. The proxy plugin no longer touches the Java pack-push packet on either Velocity or Bungee.
