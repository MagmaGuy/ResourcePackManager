# ResourcePackManager — Proxy Plugin

> **⚠ This plugin is only useful for servers running Geyser + Floodgate.**
> Its sole purpose is to merge each backend's converted Bedrock resource pack
> and serve it to Bedrock clients via Geyser. **Java Edition players don't
> need this plugin** — Java pack delivery in network setups is handled by the
> individual backends directly. If your network is Java-only (no Bedrock
> players coming through Geyser), you can ignore this folder entirely.

This folder contains the **RSPM proxy plugin** jars. They go on your **proxy**
(Velocity / BungeeCord / Waterfall), NOT on the backend Minecraft server. The proxy
plugin merges every backend's converted Bedrock pack into a single pack and serves
it to Bedrock clients via Geyser.

If you run a standalone server (no proxy in front of it), you can ignore this folder
entirely. The jars are extracted on every boot regardless, so they're ready if you
ever add a proxy later.

---

## Which jar do I use?

Pick exactly ONE based on what your proxy software is:

| Proxy software           | Use this jar                             |
|--------------------------|------------------------------------------|
| Velocity                 | `ResourcePackManager-Velocity.jar`       |
| BungeeCord               | `ResourcePackManager-BungeeCord.jar`     |
| Waterfall (Bungee fork)  | `ResourcePackManager-BungeeCord.jar`     |

Don't install both — only the matching one. Installing both will cause boot errors
on whichever platform tries to load the wrong one.

---

## Installation — 2 steps

### 1. Copy the jar to your proxy host

Copy the relevant jar from THIS folder to your proxy's `plugins/` directory.
Common methods:

- **Same machine**: just drag-and-drop, or `cp` / `copy`.
- **Remote proxy**: `scp ResourcePackManager-Velocity.jar user@proxy-host:/path/to/proxy/plugins/`
- **Hosted proxy panel** (Pterodactyl, etc.): upload via the panel's file manager.

### 2. Restart the proxy

That's it. No config to edit, no key to paste.

The plugin reads `plugins/floodgate/key.pem` on the proxy at boot and derives
the network identity from it automatically. Since Floodgate already requires
that file to be the same on every backend AND on the proxy (for Bedrock auth
to work at all), the derived key matches every backend automatically.

First merge typically completes within ~10 seconds of all backends being up.

---

## Verifying it works

**Proxy console** (within ~10s of restart, assuming backends are running):

```
[ResourcePackManager] Merged pack ready at .../merged/Bedrock.zip (sha1 ...)
[ResourcePackManager] Geyser mappings deployed to .../custom_mappings
[ResourcePackManager] ✔ Network resource pack is now ready (... KB, sha1ABCD1234)
```

**Bedrock client**: connect via Bedrock. You should see the resource-pack
download prompt before reaching the world. Custom items render with their
intended models instead of plain armor stands.

**`/rspm status`** on the backend: shows pack state, hosting mode, and
network-key fingerprint. Match the last 4 chars to the proxy's config to
confirm both sides are linked.

---

## Common issues

### "Floodgate key.pem missing" on proxy boot

The proxy plugin couldn't find `plugins/floodgate/key.pem` and idled. Fix:

1. **Install Floodgate** on the proxy. It's required for Bedrock players to
   reach the proxy anyway, so this is something you need regardless of RSPM.
2. Make sure `plugins/floodgate/key.pem` on the proxy is **byte-for-byte
   identical** to the same file on every backend. Floodgate auto-generates
   different keys per install by default — copy one canonical `key.pem` from
   any backend to every other component (other backends + the proxy), then
   restart everything. Floodgate already requires this for Bedrock auth, so
   if Bedrock players currently work across your network, this is already done.

### Bedrock connects but sees no custom models

Most common cause: the proxy started BEFORE the backend produced its first
Bedrock pack. Geyser registers custom items only at boot — once it's running
with an empty mappings file, it stays that way. **Restart the proxy** after
the backend has logged `Wrote merged Geyser mappings: N entries`. Subsequent
merges are picked up automatically by Geyser's pack-serving path, but the
custom-item table is set at boot.

### "Duplicate bedrock_identifier" warnings on proxy boot

Two backends emitted the same Bedrock identifier for the same base item.
Last-writer-wins; harmless if you only need one backend to provide that
item. If both backends should host distinct custom items under the same
base item, you have a real collision — rename one of the source Java
models so the auto-generated hashes differ.

### Updating RSPM

After bumping the backend RSPM jar, also re-copy the matching
`ResourcePackManager-Velocity.jar` / `-BungeeCord.jar` from this folder to
the proxy and restart the proxy. The backend regenerates these on every
boot so they're always in sync with the backend version.

---

Generated by ResourcePackManager v${version} on backend boot.
Other languages: see `README - espanol.md` (Spanish), `README - francais.md` (French),
`README - portugues.md` (Portuguese), `README - zhongwen.md` (Mandarin),
`README - hindi.md` (Hindi), `README - bangla.md` (Bengali), `README - arabi.md` (Arabic),
`README - russkij.md` (Russian), `README - urdu.md` (Urdu).
