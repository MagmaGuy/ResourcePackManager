# Network Mode

Network mode lets ResourcePackManager deliver a merged resource pack to Bedrock players across a multi-server network behind a Velocity, BungeeCord, or Waterfall proxy. Backends mix their plugin packs as usual; the proxy plugin polls each backend's metadata endpoint over HTTP, downloads the per-backend packs, merges them locally, and self-hosts the merged pack for Geyser to serve to Bedrock clients.

Java pack push is handled by **the backends individually**, not the proxy: each backend pushes its OWN pack URL via `Player.setResourcePack` at `PlayerJoinEvent`. Java clients on multi-backend networks therefore see per-backend packs (and re-prompt on `/server` switches). Cross-backend merging is a Bedrock-only feature in this design — the trade-off keeps backend↔proxy coordination simple (no out-of-band channels, no "who's the proxy's public host" discovery).

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
   │   • BackendMetadataPoller hits each backend's    │
   │     http://<host>:25567/.rspm-pack-info.json     │
   │     every 60s                                    │
   │   • NetworkSync downloads each backend's pack    │
   │   • Mixes via shared MixEngine                   │
   │   • Self-hosts merged pack on port 25567         │
   │   • Bedrock-only: Geyser PackCodec.url(merged)   │
   │   • Java push: NONE (backends do it)             │
   └─────────────────────────┬────────────────────────┘
                             │ proxy → backend HTTP poll (/.rspm-pack-info.json)
                             ▼
   Java client ◄──────┐
                      │
   ┌──────────────────┴──────┐
   │  Backend 1 / 2 / 3      │
   │                         │
   │  ResourcePackManager.jar│
   │   • Mixes plugin packs  │
   │   • Uploads to          │
   │     magmaguy.com OR     │
   │     self-hosts on 25567 │
   │   • Always exposes      │
   │     /.rspm-pack-info.json│
   │     on port 25567       │
   │   • PlayerJoinEvent:    │
   │     pushes its OWN URL  │
   │     via setResourcePack │
   │   • Bedrock: NOT served │
   │     locally — proxy owns│
   └─────────────────────────┘
```

The backend's pack HTTP server is always-on in network mode (not only on upload failure). It serves two routes:

- `GET /rspm.zip` — the actual pack zip (404s until AutoHost has a pack ready).
- `GET /.rspm-pack-info.json` — always returns 200 with this shape:
  ```json
  {
    "uuid": "<DataConfig.rspUUID or null>",
    "url": "<self-host URL OR magmaguy.com/rsp/<uuid> OR null>",
    "sha1": "<Mix.finalSHA1 hex or null>",
    "networkKey": "<NetworkMode.getNetworkKey()>"
  }
  ```
  Fields are `null` when not yet known. The proxy's poller skips backends whose `url` is null until the backend catches up — no fragile retry loop, no 404 spam.

## Trade-off: Java is per-backend; Bedrock is network-merged

This iteration deliberately does NOT push the proxy's merged pack to Java clients. Reasons:

- The backend doesn't have a clean way to learn the proxy's public hostname, so even if the proxy publishes a merged URL, the backend can't construct a stable URL to push.
- Avoiding backend→proxy out-of-band coordination keeps the design simple — proxy polls backends over HTTP and that's it.
- Bedrock's pack handshake fires once per session at proxy login, so the cross-backend merged pack actually matters there. Java's pack push happens per-backend-join anyway, so each backend pushing its own pack is the natural fit.

If your backends have **different plugin sets**, Java players will see the per-backend pack of whichever backend they're currently on, and get a re-prompt on `/server`. Bedrock players will see the merged pack regardless of which backend they're on. If you need cross-backend merged content on Java too, run identical plugin sets on all backends (each backend mixes the same content → all per-backend pushes carry identical bytes → 1.20.3+ clients dedupe).

## Setup

Drop the jars, restart, done. No config files to edit in the common case.

1. **Backend(s):** drop `ResourcePackManager.jar` into each backend's `plugins/`. On first boot it detects network mode (Floodgate present, no Geyser-Spigot) and extracts `ResourcePackManager-Velocity.jar` and `ResourcePackManager-BungeeCord.jar` to `plugins/ResourcePackManager/proxy-extension/`. Log line tells you the absolute path.

2. **Proxy:** copy the appropriate proxy jar from any backend's `proxy-extension/` folder to your proxy's `plugins/`. Restart the proxy.
   - **Velocity:** `ResourcePackManager-Velocity.jar`
   - **BungeeCord / Waterfall:** `ResourcePackManager-BungeeCord.jar`

3. **Done.** RPM derives the network-key automatically from your Floodgate `key.pem` (the file you already shared between proxy and backends for Floodgate's own proxy-mode auth). Same key.pem → same network-key → all components on the same network. The proxy then polls each backend it knows about (from `velocity.toml` / `config.yml`) at `http://<host>:25567/.rspm-pack-info.json` every 60 seconds.

### Port requirements

- **Backend 25567 reachable from the proxy.** This is the always-on `PackHttpServer` port. Both the metadata endpoint and the pack zip are served here. Open it on the backend's firewall to the proxy's IP. If you change the backend's `selfHostPort` config, mirror it in the proxy's `backend-metadata-port`.
- **Proxy 25567 reachable from Bedrock clients.** The proxy self-hosts the merged pack on this port for Geyser to fetch. The proxy plugin's `self-host-external-host` config sets the hostname clients use.

## Advanced configuration

### Override the network-key explicitly

If you need to split one Floodgate network into multiple RPM networks (or merge multiple Floodgate networks into one), set an explicit network-key:

- **Backend:** `plugins/ResourcePackManager/config.yml` → `networkKey: "your-shared-string"`
- **Proxy plugin:** `plugins/ResourcePackManager/config.yml` → `network-key: "your-shared-string"`

Use the same value everywhere. The proxy filters out backends whose `/.rspm-pack-info.json` reports a mismatching `networkKey` — so two unrelated RPM installs sharing one proxy will not cross-contaminate.

### Override the backend metadata port

Default is 25567 (matches the backend's `selfHostPort` default). If you run RPM on a non-default port on the backends, set the same number on the proxy:

- **Backend:** `plugins/ResourcePackManager/config.yml` → `selfHostPort: <port>`
- **Proxy plugin:** `plugins/ResourcePackManager/config.yml` → `backend-metadata-port: <port>`

## Verify

After dropping the jars and restarting:
- Proxy console should log `RSPM proxy plugin started (network-key=...)`. Within ~2 seconds: `BackendMetadataPoller starting (metadata port 25567, poll interval 60000 ms)`. Within ~60 seconds after any backend is up with a pack ready: `Backend metadata refreshed: N backend(s) with packs ready`, then `Merged pack published at http://<proxy-host>:25567/network.zip`.
- A Java player connecting to a backend gets a pack prompt at first backend join. Switching to a backend with different plugin content re-prompts.
- A Bedrock player connecting via Geyser sees the merged pack at proxy login (the only handshake Bedrock has).

## Multi-backend gotchas

**Bedrock pack handshake happens once per session, at proxy login.** This is a Bedrock protocol limitation — Geyser cannot swap packs when a Bedrock player switches servers. So the pack served at proxy login is the only pack a Bedrock player sees during their session, regardless of which backend they're on.

If your backends have **different plugin sets**, the merged pack contains everything. That's the point of the merge — Bedrock players on any backend can see models from every backend. Java players see per-backend packs only.

If you want **strictly per-server packs on Bedrock** (different content per server), RPM can't deliver that. [GeyserPackSync](https://github.com/onebeastchris/GeyserPackSync) uses Bedrock's transfer-packet trick to force-reconnect Bedrock clients on backend switch — at the cost of a visible disconnect+reconnect to the player. Use it alongside RPM if that UX trade-off is acceptable.

## Reverting to single-server mode

To go back to non-network behavior, remove Floodgate from the backend OR add Geyser-Spigot. Detection will flip off network mode. The backend resumes pushing packs directly to Java players via `setResourcePack` (no change in behavior — backends were already doing this even in network mode) and the always-on metadata server stops.

Re-activating later derives the same network-key again from the same Floodgate `key.pem`, so existing network state lines up automatically.

## Caveats and limitations

- **Each backend must run RPM:** the proxy can only see backends that expose `/.rspm-pack-info.json`. A backend without RPM (or with RPM in standalone mode, where the metadata server isn't started) is invisible to the proxy's poller.
- **Bootstrap is clean:** the proxy polls on a fixed schedule from startup, so the first Bedrock player gets the merged pack as soon as the first poll cycle completes (≤60s after proxy enable, assuming a backend is up). No "first player primes the cache" workaround is needed.
- **Network-key collision:** If two unrelated RPM installations accidentally use the same network-key, their packs get merged together. With the default Floodgate-derived key this is essentially impossible — separate Floodgate networks already have distinct `key.pem` files. If you override the key manually, treat it like a shared secret and pick something unique.
- **Backend re-mixes:** when a backend re-uploads a different pack (different sha1), the proxy detects on its next poll (≤60s) and re-merges. New Bedrock sessions see the new merged pack; existing Bedrock sessions keep what they were given at proxy login.
- **Java pack push is per-backend by design:** see "Trade-off" above. If your backends carry the same plugins and you want Java players to skip re-prompts on `/server`, the backends will push identical packs and 1.20.3+ Java clients will dedupe.
- **Self-host fallback for the backend upload:** see [`self-host.md`](self-host.md). When the magmaguy.com upload fails, the backend serves its own pack from the same port the proxy polls for metadata.
