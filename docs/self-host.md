# Self-Host Fallback

When uploading the resource pack to `magmaguy.com/rsp/` fails — pack too large, network issue, server down, autohost disabled — RPM falls back to serving the pack from an embedded HTTP server on the same machine. This document covers how that works, how to configure it, and how to debug it when clients can't reach the pack.

This applies to both the backend RPM plugin and the proxy plugin (rpm-velocity / rpm-bungee). Both have identical config keys and behavior; the only difference is what they serve (backends serve their own plugin pack, proxy serves the network-merged pack).

## How it works

1. RPM tries to upload the pack to `magmaguy.com/rsp/` via the autohost client.
2. If the upload succeeds, it uses the returned `magmaguy.com/rsp/<uuid>` URL to push to clients. End.
3. If the upload fails — any reason: HTTP error, IOException, size rejection, `UnsupportedOperationException` (server doesn't have the endpoint yet) — RPM starts a local HTTP server on the configured port serving the pack zip.
4. The local URL is `http://<self-host-external-host>:<self-host-port>/...zip` and gets used in place of the magmaguy.com URL when pushing to clients.

The embedded server has no fancy features — it serves one file at one path, with the headers Geyser requires (`Content-Type: application/zip`, accurate `Content-Length`). One request per client per pack download.

## Config

In `plugins/ResourcePackManager/config.yml` (backend) or `plugins/ResourcePackManager/config.yml` on the proxy:

```yaml
# Backend (rpm-bukkit) keys are camelCase per the Bukkit convention:
selfHostEnabled: true
selfHostPort: 25567
selfHostExternalHost: ""
selfHostForce: false

# Proxy plugin (Velocity/Bungee) keys are kebab-case:
self-host-port: 25567
self-host-external-host: ""
```

| Key | Default | What it does |
|---|---|---|
| `selfHostEnabled` (backend only) | `true` | Allow falling back to local HTTP if upload fails. Setting to `false` disables fallback — failed uploads simply log a warning. |
| `selfHostPort` | `25567` | TCP port for the embedded HTTP server. Picked to be adjacent to vanilla Minecraft's 25565 so it's often open when the Minecraft port is open. |
| `selfHostExternalHost` | `""` | Public hostname or IP clients should use to reach the self-host server. When empty, RPM auto-detects (best-effort). Set explicitly if auto-detect picks the wrong interface. |
| `selfHostForce` (backend only) | `false` | Skip the magmaguy.com upload attempt entirely; always self-host. Mainly for testing. The proxy plugin doesn't have this — it always tries upload first. |

## Setup checklist

1. **Open the port in your firewall.** RPM does NOT probe reachability — it just binds the socket. If the port isn't reachable from your players, the pack download will fail on their end with a generic "couldn't download pack" error.

   - Linux: `sudo ufw allow 25567/tcp` (Ubuntu) or `firewall-cmd --add-port=25567/tcp --permanent` (RHEL/CentOS)
   - Windows: open `wf.msc`, add an inbound rule for TCP 25567
   - Cloud hosts: also open it in your security group / network ACL (AWS, GCP, OVH all have this)
   - Pterodactyl/managed panels: usually requires a support ticket to open a non-default port

2. **Set `selfHostExternalHost` if auto-detect picks the wrong address.**

   Auto-detect resolution order:
   1. `Bukkit.getIp()` (backend only) — the bind address from `server.properties`. Often blank or `0.0.0.0`.
   2. `InetAddress.getLocalHost()` — the local hostname's first resolved IP. Often a private LAN IP like `192.168.1.x` or `10.x.x.x` on cloud hosts, which won't work for external clients.
   3. Last resort: `"localhost"` (only useful for testing on the same machine).

   On most cloud hosts, you should explicitly set `selfHostExternalHost` to your public hostname or IPv4 address. Example:

   ```yaml
   selfHostExternalHost: "play.example.com"
   # or
   selfHostExternalHost: "203.0.113.42"
   ```

3. **(Optional) Test the URL.** Once the plugin reports `Self-hosting pack at http://...`, try fetching it from a machine on the public internet:

   ```
   curl -I http://your-host:25567/rspm.zip
   ```

   You should get `HTTP/1.1 200 OK` with `Content-Type: application/zip` and `Content-Length: <size>`. If `curl` hangs or returns `Connection refused`, the port isn't reachable from outside.

## Forcing self-host (testing only)

To exercise the self-host path without breaking your autohost setup, set on a backend:

```yaml
selfHostForce: true
```

Restart the backend. RPM skips the magmaguy.com upload entirely and starts the local HTTP server. Verify the pack reaches clients via the local URL, then turn it back off.

The proxy plugin has no equivalent `force` flag — to test its self-host path, temporarily make the magmaguy.com endpoint unreachable (e.g., add a hostfile entry pointing it at 127.0.0.1) and confirm the proxy plugin falls through to its own self-host.

## Troubleshooting

### "Self-host fallback failed (port 25567 probably in use)"

Something else is bound to the port. Check with:

- Linux: `ss -tlnp | grep 25567`
- Windows: `netstat -ano | findstr 25567`

Common culprits: another Minecraft server, a previous RPM instance that didn't shut down cleanly, an unrelated HTTP service. Change `selfHostPort` to a different port (and update your firewall rule).

### Clients see "Couldn't download resource pack" / Bedrock log shows `Content-Type` complaint

Geyser requires `Content-Type: application/zip` and an accurate `Content-Length`. RPM's embedded server sets both correctly. If the error mentions `Content-Type`, something between RPM and the Bedrock client (a reverse proxy, a CDN) is stripping or overriding the header. Either bypass it or fix the intermediary's config.

### Java client gets "Failed to download" with a vague message

The URL isn't reachable. Test with `curl -v http://your-host:25567/rspm.zip` from the public internet. If `curl` hangs or refuses, your firewall/NAT/cloud security group is blocking the port. If `curl` works but the game still fails, double-check that `selfHostExternalHost` matches what `curl` resolves to.

### "Self-hosting pack at http://0.0.0.0:25567/..." in the log

The auto-detected external host is `0.0.0.0`, which clients can't use. Set `selfHostExternalHost` explicitly to your public IP or hostname.

### The pack URL works but clients re-download every reconnect

Java clients cache by hash. If the hash changes every restart (e.g., timestamp differences in the zip), they re-download. RPM uses content-stable hashes, so this shouldn't happen — but if it does, check that nothing else is touching the zip file between mix completion and pack push (some monitoring tools rewrite file timestamps).

## Security notes

- The embedded HTTP server is internet-facing if you opened the port — that's how clients reach it. It serves exactly one file (the resource pack zip) at one path; there are no other endpoints and no way to traverse paths.
- Anyone with the URL can download the pack. If your resource pack contains assets you don't want to distribute publicly (e.g., paid model packs you don't have redistribution rights for), self-host is NOT appropriate. Use a private CDN with authentication, or rely on magmaguy.com's URL which is also a public URL but at least scoped to your install.
- There's no rate limit on the embedded server. A single misbehaving client could request the pack repeatedly. Not a security issue per se, just bandwidth — most clients download once and cache.

## Architecture cross-reference

- Backend self-host code: [`AutoHost.fallbackToSelfHost`](../rpm-bukkit/src/main/java/com/magmaguy/resourcepackmanager/autohost/AutoHost.java)
- Proxy self-host code: [`NetworkSync.startOrRefreshSelfHost`](../rpm-proxy-common/src/main/java/com/magmaguy/rspm/proxy/NetworkSync.java)
- HTTP server: [`PackHttpServer`](../rpm-http-common/src/main/java/com/magmaguy/rspm/http/PackHttpServer.java)
