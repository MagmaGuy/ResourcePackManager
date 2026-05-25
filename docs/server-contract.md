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

## Network-mode endpoints

**None required.** Network mode no longer talks to `magmaguy.com/rsp/` for manifest or merged-pack distribution. The proxy plugin polls each backend's `PackHttpServer` directly at `/bedrock.zip` and `/mappings.json` on `mcPort + networkHttpOffset` (default `+100`), saves the responses to a local inbox, waits for the inbox to stabilize across two consecutive polls, and merges the per-backend Bedrock packs + Geyser mappings on the proxy's local disk. It then hands the merged pack file path to Geyser via `PackCodec.path(file)` — there is no proxy-side HTTP server; Geyser reads the file in-process and delivers bytes to Bedrock clients over the Bedrock protocol. See [`network-mode.md`](network-mode.md) for the architecture.

Backends in network mode still use the regular `/rsp/upload` / `/rsp/sha1` / `/rsp/initialize` / `/rsp/still_alive` endpoints to host their individual packs — nothing about that flow changes. The network-merged pack lives only on the proxy and is never uploaded.

---

## Compatibility constraints

- **Existing endpoints' behavior must NOT change.** Backends running RPM 1.7.x and older expect the current contract.
- **Existing error JSON shape must NOT change.** Clients parse `error.code` for branching logic.
- **`Content-Type: application/zip` and accurate `Content-Length`** are required on all zip GETs — Geyser's pack fetcher rejects responses missing these headers ([reference: `GeyserUrlPackCodec`](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/pack/url/GeyserUrlPackCodec.java)).
- **HTTP works fine** for both Java and Bedrock pack URLs. Bedrock clients never see the URL directly (Geyser fetches it on the proxy JVM and serves bytes via the Bedrock protocol), so no client-side TLS requirement.
