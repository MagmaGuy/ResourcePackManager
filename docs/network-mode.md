# Network Mode

Network mode lets ResourcePackManager deliver a merged resource pack to Bedrock players across a multi-server network behind a Velocity, BungeeCord, or Waterfall proxy. Backends mix their plugin packs as usual; the proxy plugin polls each backend's HTTP server for the converted Bedrock pack zip and Geyser mappings JSON, merges them locally on disk, and hands the merged pack file path directly to Geyser via `PackCodec.path(file)`. There is no proxy-side HTTP server — Geyser reads the merged pack file in-process and ships the bytes to Bedrock clients over the Bedrock protocol.

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
   │  resourcepackmanager-velocity.jar /              │
   │  resourcepackmanager-bungee.jar                  │
   │   • NetworkSync polls each backend's             │
   │     http://<host>:<mcPort+100>/bedrock.zip and   │
   │     /mappings.json every 30s (If-Modified-Since) │
   │   • Waits for inbox to stabilize across 2 polls  │
   │   • Merges Bedrock zips + Geyser mappings on disk│
   │   • Hands merged pack file path to Geyser via    │
   │     PackCodec.path(file) — Geyser ships bytes    │
   │     over the Bedrock protocol                    │
   │   • No proxy-side HTTP server                    │
   │   • Java push: NONE (backends do it)             │
   └─────────────────────────┬────────────────────────┘
                             │ proxy → backend HTTP poll (/bedrock.zip, /mappings.json)
                             ▼
   Java client ◄──────┐
                      │
   ┌──────────────────┴────────┐
   │  Backend 1 / 2 / 3        │
   │                           │
   │  ResourcePackManager.jar  │
   │   • Mixes plugin packs    │
   │   • Uploads to magmaguy.com│
   │     OR self-hosts on      │
   │     mcPort + networkHttp- │
   │     Offset (default 100)  │
   │   • Exposes /bedrock.zip  │
   │     and /mappings.json    │
   │     on that same HTTP port│
   │   • PlayerJoinEvent:      │
   │     pushes its OWN URL    │
   │     via setResourcePack   │
   │   • Bedrock content       │
   │     pulled by proxy       │
   └───────────────────────────┘
```

The backend's `PackHttpServer` is always-on in network mode (not only on upload failure). The two routes the proxy actively polls are:

- `GET /bedrock.zip` — the converted Bedrock pack zip produced by the backend's BedrockConversion step. 404s until conversion has produced output; supports `If-Modified-Since` so unchanged files return 304.
- `GET /mappings.json` — the Geyser custom-items mappings JSON. Same 404/304 semantics as `/bedrock.zip`.

The proxy GETs these two paths on every backend on each poll cycle (30s default), saves any 200 response into an inbox directory, waits for the inbox hashes to stabilize across two consecutive polls, then merges all backends' contributions into a single Bedrock pack zip and a single mappings file.

## Trade-off: Java is per-backend; Bedrock is network-merged

This iteration deliberately does NOT push the proxy's merged pack to Java clients. Reasons:

- The backend doesn't have a clean way to learn the proxy's public hostname, so even if the proxy publishes a merged URL, the backend can't construct a stable URL to push.
- Avoiding backend→proxy out-of-band coordination keeps the design simple — proxy polls backends over HTTP and that's it.
- Bedrock's pack handshake fires once per session at proxy login, so the cross-backend merged pack actually matters there. Java's pack push happens per-backend-join anyway, so each backend pushing its own pack is the natural fit.

If your backends have **different plugin sets**, Java players will see the per-backend pack of whichever backend they're currently on, and get a re-prompt on `/server`. Bedrock players will see the merged pack regardless of which backend they're on. If you need cross-backend merged content on Java too, run identical plugin sets on all backends (each backend mixes the same content → all per-backend pushes carry identical bytes → 1.20.3+ clients dedupe).

## Setup

Drop the jars, restart, done. No config files to edit in the common case — with ONE crucial exception (step 3 below).

1. **Backend(s):** drop `ResourcePackManager.jar` into each backend's `plugins/`. On first boot it detects network mode (Floodgate present, no Geyser-Spigot) and extracts `resourcepackmanager-velocity.jar` and `resourcepackmanager-bungee.jar` to `plugins/ResourcePackManager/proxy-extension/`. Log line tells you the absolute path.

2. **Proxy:** copy the appropriate proxy jar from any backend's `proxy-extension/` folder to your proxy's `plugins/`. Restart the proxy.
   - **Velocity:** `resourcepackmanager-velocity.jar`
   - **BungeeCord / Waterfall:** `resourcepackmanager-bungee.jar`

3. **CRITICAL: enable `send-floodgate-data` on the proxy's Floodgate.** Edit `<proxy>/plugins/floodgate/config.yml` and set:

   ```yaml
   send-floodgate-data: true
   ```

   Floodgate's default is `false`. Without this flag, the proxy never forwards Bedrock-player identity to the backends, so the backend's `FloodgateApi.isFloodgatePlayer()` returns `false` even for real Bedrock players. RPM's `BedrockChecker` (and any other plugin that uses it, e.g. FreeMinecraftModels) then treats Bedrock players as Java players and never sends them the custom-bone-rendering packets — Bedrock clients see vanilla armor stands instead of FMM models, and other Bedrock-specific paths silently misbehave. **You must restart Floodgate / the proxy after toggling this.**

4. **Done.** RPM derives the network-key automatically from your Floodgate `key.pem` (the file you already shared between proxy and backends for Floodgate's own proxy-mode auth). Same key.pem → same network-key → all components on the same network. The proxy then polls each backend it knows about (from `velocity.toml` / `config.yml`) at `http://<host>:<mcPort+100>/bedrock.zip` and `/mappings.json` every 30 seconds, where `+100` is the default `networkHttpOffset`.

### Port requirements

- **Backend HTTP port reachable from the proxy.** Each backend's `PackHttpServer` port is auto-derived per backend as `<minecraft port> + networkHttpOffset` (default offset 100). So a backend on MC `25671` exposes `/bedrock.zip` and `/mappings.json` on HTTP `25771`; a backend on MC `25672` exposes them on `25772`; etc. This means single-host networks (multiple backends on localhost — the common production setup) auto-stagger with zero config: each backend already has a unique MC port, so each gets a unique HTTP port and the port-collision bind race is gone. Open the derived ports on each backend's firewall to the proxy's IP. To change the offset (rarely needed) set `networkHttpOffset` on every backend AND `network-http-offset` on the proxy — they must match.
- **Proxy → Bedrock client pack delivery is in-protocol** (Geyser sends pack bytes over the Bedrock connection), so no extra public-facing HTTP port is needed on the proxy. The proxy does NOT run an HTTP server in network mode — the merged pack lives on the proxy's local disk and Geyser reads it directly via `PackCodec.path`.

## Advanced configuration

### Override the network-key explicitly

If you need to split one Floodgate network into multiple RPM networks (or merge multiple Floodgate networks into one), set an explicit network-key:

- **Backend:** `plugins/ResourcePackManager/config.yml` → `networkKey: "your-shared-string"`
- **Proxy plugin:** `plugins/ResourcePackManager/config.yml` → `network-key: "your-shared-string"`

Use the same value everywhere. Mismatched network-keys mean the proxy and backends won't coordinate — components silently ignore each other's content.

### Override the HTTP port

By default, each backend's HTTP port is derived as `<minecraft port> + 100`. So MC `25671` → HTTP `25771`, MC `25672` → HTTP `25772`, etc. Single-host networks auto-stagger because each backend's MC port is already unique, so each gets a unique HTTP port without any admin action. Admins **almost never need to change this**.

If the offset 100 collides with something already running on the backend host, bump it on both sides — they must match, because the proxy computes `mcPort + network-http-offset` per backend and the backend binds on `mcPort + networkHttpOffset`. A mismatch means the proxy hits the wrong port and the backend looks dead.

- **Backend:** `plugins/ResourcePackManager/config.yml` → `networkHttpOffset: <offset>`
- **Proxy plugin:** `plugins/ResourcePackManager/config.yml` → `network-http-offset: <offset>`

The proxy config has only three keys: `network-key`, `force-resource-pack`, `network-http-offset`. There is no `backend-metadata-port`, no proxy-side `self-host-port`, no `self-host-external-host` on the proxy — those are all gone with the move to direct `PackCodec.path` delivery.

If you need a hardcoded HTTP port on a specific backend (e.g. firewall rule pins it), set `selfHostPort: <port>` on that backend to any positive integer. That backend will then bind on the explicit port instead of auto-deriving — but the proxy can no longer find it from MC port alone, so this only makes sense for one-off scenarios.

## Verify

After dropping the jars and restarting:
- Proxy console should log `RSPM proxy plugin started (network-key=...)`. Within ~2 seconds: `NetworkSync starting for network-key ... (poll interval 30000 ms, network-http-offset 100 — per-backend HTTP port = mcPort + offset)`. Within ~60 seconds after any backend is up with content: `NetworkSync: inbox stabilized — merging N Bedrock zip(s) ...` then `Merged Bedrock pack published at ...`.
- A Java player connecting to a backend gets a pack prompt at first backend join. Switching to a backend with different plugin content re-prompts.
- A Bedrock player connecting via Geyser sees the merged pack at proxy login (the only handshake Bedrock has).

### Troubleshooting

- **Bedrock players see no FMM models / custom items don't render** — check that `send-floodgate-data: true` is set in the proxy's `plugins/floodgate/config.yml`. Floodgate's default is `false`; with it off, the backend's `FloodgateApi.isFloodgatePlayer()` returns `false` for real Bedrock players and any plugin that branches on `BedrockChecker` (FMM, RPM, others) silently treats them as Java clients and skips Bedrock-specific code paths. Restart the proxy/Floodgate after changing it.

## Multi-backend gotchas

**Bedrock pack handshake happens once per session, at proxy login.** This is a Bedrock protocol limitation — Geyser cannot swap packs when a Bedrock player switches servers. So the pack served at proxy login is the only pack a Bedrock player sees during their session, regardless of which backend they're on.

If your backends have **different plugin sets**, the merged pack contains everything. That's the point of the merge — Bedrock players on any backend can see models from every backend. Java players see per-backend packs only.

If you want **strictly per-server packs on Bedrock** (different content per server), RPM can't deliver that. [GeyserPackSync](https://github.com/onebeastchris/GeyserPackSync) uses Bedrock's transfer-packet trick to force-reconnect Bedrock clients on backend switch — at the cost of a visible disconnect+reconnect to the player. Use it alongside RPM if that UX trade-off is acceptable.

## Reverting to single-server mode

To go back to non-network behavior, remove Floodgate from the backend OR add Geyser-Spigot. Detection will flip off network mode. The backend resumes pushing packs directly to Java players via `setResourcePack` (no change in behavior — backends were already doing this even in network mode) and the always-on backend HTTP server stops.

Re-activating later derives the same network-key again from the same Floodgate `key.pem`, so existing network state lines up automatically.

## Caveats and limitations

- **Each backend must run RPM:** the proxy can only see backends that expose `/bedrock.zip` and `/mappings.json`. A backend without RPM (or with RPM in standalone mode, where the backend HTTP server isn't started) is invisible to the proxy's poller.
- **Bootstrap is clean:** the proxy polls on a fixed schedule from startup, so the first Bedrock player gets the merged pack as soon as two consecutive stable poll cycles complete (~60s after proxy enable at the default 30s interval, assuming a backend is up). No "first player primes the cache" workaround is needed.
- **Network-key collision:** If two unrelated RPM installations accidentally use the same network-key, their packs get merged together. With the default Floodgate-derived key this is essentially impossible — separate Floodgate networks already have distinct `key.pem` files. If you override the key manually, treat it like a shared secret and pick something unique.
- **Backend re-mixes:** when a backend rewrites its `/bedrock.zip` (different sha1), the proxy detects it on the next poll (≤30s), waits for the inbox to stabilize again, and re-merges. New Bedrock sessions see the new merged pack; existing Bedrock sessions keep what they were given at proxy login.
- **Java pack push is per-backend by design:** see "Trade-off" above. If your backends carry the same plugins and you want Java players to skip re-prompts on `/server`, the backends will push identical packs and 1.20.3+ Java clients will dedupe.
- **Self-host fallback for the backend upload:** see [`self-host.md`](self-host.md). When the magmaguy.com upload fails, the backend serves its own pack from the same HTTP port the proxy polls for the Bedrock outputs.
