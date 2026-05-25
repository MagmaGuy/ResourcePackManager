# `magmaguy.com/rsp/` Server-Side API Contract

This document describes the HTTP API that `magmaguy.com/rsp/` must implement to support ResourcePackManager's autohost and network-mode features. The plugin client lives in [`MagmaguyRspClient`](../resourcepackmanager-http-common/src/main/java/com/magmaguy/resourcepackmanager/http/MagmaguyRspClient.java); this is the server-side counterpart.

Note: the server is a separate codebase from RPM. This document is the contract — the plugin client targets these endpoints; server-side implementation is updated independently. Phases 0–3 of the network-mode rollout work entirely against the existing endpoints (no new server endpoints needed). Phases 4+ require the new endpoints listed under "Network mode" below.

---

## Conventions

- **Base URL:** `https://magmaguy.com/rsp/`
- **Body encoding:** all POSTs use `multipart/form-data` with text fields and (where applicable) one binary `file` part
- **Error responses:** JSON body `{ "error": { "code": "<CODE>", "type": "<TYPE>", "message": "<human-readable>" } }` with appropriate HTTP status (4xx/5xx)
- **Success responses (except file downloads):** JSON body, shape varies per endpoint
- **Auth:** uuid-based; clients supply their issued uuid as a form field on every state-mutating call

### Defined error codes

| Code | Triggers |
|---|---|
| `MISSING_REQUIRED_FILES` | Uploaded zip lacks pack.png / pack.mcmeta at root |
| `FILE_TOO_LARGE` | Uploaded zip exceeds the server's size cap |
| `INVALID_FILE_FORMAT` | Uploaded blob is not a valid zip |
| `SESSION_NOT_FOUND` | The uuid the client supplied is unknown/expired |
| `SERVER_UNAVAILABLE` | Temporary server-side failure; client may retry |

Clients react specifically to `SESSION_NOT_FOUND` by clearing their cached uuid and re-initializing.

---

## Existing endpoints (Phases 0–3 use these as-is)

### `POST /rsp/initialize`

Create or resume a session.

**Form fields:**
- `uuid` (optional) — supply to resume an existing session

**Response (success, 2xx):**
```json
{ "success": true, "uuid": "a1b2c3...", "message": "..." }
```
Or (legacy plain-text fallback): the uuid string directly.

**Response (failure):** standard error JSON.

### `POST /rsp/sha1`

Check whether the server already has a pack with this sha1 under this uuid (used for upload deduplication).

**Form fields:**
- `uuid` — required
- `sha1` — required, hex string uppercase

**Response (success, 2xx):**
```json
{ "success": true, "uploadNeeded": false }
```
or
```json
{ "success": true, "uploadNeeded": true }
```
Legacy plain-text fallback: `"true"` or `"false"`.

**Response (failure):** standard error JSON. Notably, `SESSION_NOT_FOUND` indicates the client's uuid is unknown — the client should re-initialize.

### `POST /rsp/upload`

Upload a resource pack zip. The pack is served at `https://magmaguy.com/rsp/<uuid>` (GET) after a successful upload.

**Form fields:**
- `uuid` — required
- `file` — required, binary multipart, the .zip file

**Response (success, 2xx):** any 2xx status with no specific body required. Plugin treats 2xx as success and assumes `BASE_URL + uuid` is now serving the uploaded pack.

**Response (failure):** standard error JSON.

### `POST /rsp/still_alive`

Keep-alive heartbeat. Plugin pings every 6 hours.

**Form fields:**
- `uuid` — required

**Response (success):** any 2xx.
**Response (failure):** standard error JSON; `SESSION_NOT_FOUND` triggers client re-initialization.

### `POST /rsp/data_compliance`

Returns the data compliance archive (zip file) for the given uuid. Used for GDPR-style data export.

**Form fields:**
- `uuid` — required

**Response (success):** raw zip bytes as response body.

### `GET /rsp/<uuid>`

Serves the currently-uploaded resource pack for the given uuid. Used by clients downloading the pack (via `Player#setResourcePack` URL).

**Response (success):** raw zip bytes; `Content-Type: application/zip`; `Content-Length` accurate.

---

## Network-mode endpoints (Phases 4+ require these)

These endpoints support the network-mode workflow where multiple backends pool packs under a shared `network-key` and the proxy plugin merges them into a single network-wide pack.

### `POST /rsp/upload` extension — `network-key` and `variant` form fields

Existing `/rsp/upload` accepts two new optional fields:

- `network-key` (optional) — tags this upload as belonging to a network. Server stores `(network-key, uuid, sha1, last-upload-time)` in a registry queryable via `/rsp/network/<key>/manifest`.
- `variant` (optional, default `"java"`) — `"java"` or `"bedrock"`. Two parallel slots per uuid. Bedrock variant served at `https://magmaguy.com/rsp/<uuid>/bedrock` (GET).

When both are set: the upload is tagged into the network registry AND stored under the appropriate variant slot.

### `GET /rsp/<uuid>/bedrock`

Serves the Bedrock-variant pack for the given uuid (uploaded via `/rsp/upload?variant=bedrock`). Same response semantics as `/rsp/<uuid>`.

### `GET /rsp/network/<network-key>/manifest`

Returns the current set of backends registered to a network, **plus** the network-merged pack entry. Polled by both:

- the proxy plugin (`NetworkSync`) — uses backend entries as the merge inputs
- every backend (`NetworkManifestPoll`) — uses the merged-pack entry to discover the network-merged URL + sha1 to push to its own Java clients via `Player.setResourcePack`

The merged-pack entry is identified by either:
- `uuid == "merged"` (preferred — explicit marker), or
- `url` ending in `/merged` (fallback — derived from `POST /rsp/network/<key>/merged` canonical URL).

If a backend can't identify the merged entry, it falls back to pushing its own per-backend pack URL (divergent across backends, client re-prompts on `/server` switches). This keeps Java pack push functional even if the manifest endpoint is shipped before backends understand the merged-entry shape.

**Response (success):**
```json
{
  "entries": [
    {
      "uuid": "a1b2c3...",
      "url": "https://magmaguy.com/rsp/a1b2c3...",
      "sha1": "DEAD...",
      "priority": 100,
      "lastSeenMillis": 1715000000000
    },
    {
      "uuid": "d4e5f6...",
      "url": "http://backend2.example.com:25567/rspm.zip",
      "sha1": "BEEF...",
      "priority": 50,
      "lastSeenMillis": 1715000060000
    }
  ]
}
```

Notes:
- `url` may point at `magmaguy.com/rsp/<uuid>` OR at a backend's self-hosted URL when the backend chose self-host (e.g., upload failed). The server records whatever URL the backend reports.
- `priority` is set by the backend (defaults to 100); higher wins authority disputes (TBD — for v1, lowest-conflict approach: highest priority's pack is preferred).
- Entries with `lastSeenMillis` older than the still-alive window (~7 hours) MAY be omitted by the server, or returned and excluded by the client.

### `POST /rsp/network/<network-key>/merged`

Uploads the proxy-merged network pack. Called by the ResourcePackManager-Velocity / ResourcePackManager-BungeeCord proxy plugin after it downloads each backend's pack from the manifest and runs the shared `MixEngine` over them.

**Form fields:**
- `file` — required, binary multipart, the merged .zip
- Optional metadata fields TBD

**Response (success, 2xx):** the URL the merged pack is now served at (typically `https://magmaguy.com/rsp/network/<key>/merged`).

### `GET /rsp/network/<network-key>/merged`

Serves the latest merged pack uploaded via `POST /rsp/network/<network-key>/merged`. Java backends push this URL to clients via `setResourcePack`; the proxy plugin registers it via `PackCodec.url(...)` for Bedrock (Geyser fetches it on the proxy JVM and serves to Bedrock clients via the Bedrock protocol).

`Content-Type: application/zip`; `Content-Length` accurate.

---

## Phasing summary

- **Phase 0–2** (multi-module restructure, mixer extraction, HTTP infra): no server-side changes.
- **Phase 3** (backend network mode + self-host fallback): no new endpoints required — backends in network mode upload to existing `/rsp/upload` with `network-key` form field added. If the server rejects the extra field cleanly, behavior is unchanged from today (uploads succeed without network tagging). If the server stores it but doesn't expose `/rsp/network/<key>/manifest` yet, the proxy plugin (Phase 4) can't function but the backend works fine.
- **Phase 4** (proxy plugin manifest poll + merge + republish): requires `/rsp/network/<key>/manifest`, `POST /rsp/network/<key>/merged`, `GET /rsp/network/<key>/merged`.
- **Phase 5+** (Velocity/Bungee entry points, extension extraction): no new endpoints.

The client-side ([`MagmaguyRspClient`](../resourcepackmanager-http-common/src/main/java/com/magmaguy/resourcepackmanager/http/MagmaguyRspClient.java)) ships stub methods (`fetchNetworkManifest`, `uploadNetworkMerged`, `uploadBedrockVariant`, `uploadNetworkTagged`) that throw `UnsupportedOperationException` until the matching server-side endpoints land. Replace the stub bodies with real HTTP calls once the server is ready.

---

## Compatibility constraints

- **Existing endpoints' behavior must NOT change.** Backends running RPM 1.7.x and older expect the current contract.
- **Existing error JSON shape must NOT change.** Clients parse `error.code` for branching logic.
- **`Content-Type: application/zip` and accurate `Content-Length`** are required on all zip GETs — Geyser's pack fetcher rejects responses missing these headers ([reference: `GeyserUrlPackCodec`](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/pack/url/GeyserUrlPackCodec.java)).
- **HTTP works fine** for both Java and Bedrock pack URLs. Bedrock clients never see the URL directly (Geyser fetches it on the proxy JVM and serves bytes via the Bedrock protocol), so no client-side TLS requirement.
