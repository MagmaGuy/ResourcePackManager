<p align="center">
  <a href="https://discord.gg/GKyzTgT">
    <img src="https://imgur.com/mvyJwOf.png" alt="Discord" width="140"/>
  </a>
  <a href="https://nightbreak.io/plugin/resourcepackmanager/">
    <img src="https://imgur.com/OLYJlM8.png" alt="Nightbreak" width="140"/>
  </a>
  <a href="https://www.patreon.com/magmaguy">
    <img src="https://imgur.com/rWCe9W9.png" alt="Patreon" width="140"/>
  </a>
  <a href="https://wiki.nightbreak.io/ResourcePackManager/info">
    <img src="https://imgur.com/M48vTUC.png" alt="Wiki" width="140"/>
  </a>
</p>

<div align="center">
  <iframe width="560" height="315" src="https://www.youtube.com/embed/5qHSecOv5Tg" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe>
  <p><strong>This dev log is the only video I have right now, a dedicated one will (hopefully) come soon!</strong></p>
</div>

# 🟠 How it Works

**Got more than one plugin that uses resource packs?**
**Tired of manually merging them, recalculating SHA1, and rehosting?**
**Want drag & drop support where players instantly get custom models?**
This plugin is the solution!

ResourcePackManager automatically grabs the resource packs from automerge-compatible plugins, merges them based on config priorities, and hosts them directly from your server. Players automatically receive the merged pack. You can also add manual packs in the mixer folder.

# 🟠 Main Features

* **Automatically merges all of your server's resource packs!**
* **Self-hosts your resource pack directly from your server!** Falls back to free remote hosting if self-hosting is unavailable.
* **Automatically sends the resource pack to players when they log in!**
* **Rebuilds the merged pack by itself** when another plugin's resource pack changes. [How it works](https://wiki.nightbreak.io/ResourcePackManager/faq#does-resourcepackmanager-rebuild-automatically-when-packs-change)
* **Bedrock support!** Automatically converts your merged Java pack into a Bedrock-compatible pack for GeyserMC.
* **Custom Bedrock entities!** FreeMinecraftModels and EliteMobs models render and animate for Bedrock players through the Geyser extension built into the same jar. [Geyser extension](https://wiki.nightbreak.io/ResourcePackManager/geyser-extension)
* **Proxy network support!** Works with Velocity, BungeeCord, and Waterfall — Bedrock players receive a single merged pack across all servers.
* **No network key to copy.** The proxy links each backend the first time a player joins it, and passes RSPM updates from your backends to the proxy. [Proxy networks](https://wiki.nightbreak.io/ResourcePackManager/proxy-networks#network-key)
* **Supports manual additions in the mixer folder.**
* **Turn off any plugin's pack** with one setting in its `compatible_plugins/` file. [How](https://wiki.nightbreak.io/ResourcePackManager/faq#how-do-i-exclude-one-automatic-plugin-integration)
* **Works behind an HTTPS reverse proxy** with `selfHostExternalUrl`. [Self-hosting](https://wiki.nightbreak.io/ResourcePackManager/self-hosting#https-and-reverse-proxies)
* **`/rspm status` shows the pack, hosting, Bedrock and network state in one command.** [Troubleshooting](https://wiki.nightbreak.io/ResourcePackManager/troubleshooting)
* **[24/7 Discord support](https://discord.gg/9f5QSka).**
* **No setup or maintenance required!**
* **Want an even better experience?** Works best with [Free Minecraft Models](https://nightbreak.io/plugin/freeminecraftmodels) and [EternalTD](https://nightbreak.io/plugin/eternaltd) — both free!

# 🟠 How to Setup

1. Drop `ResourcePackManager.jar` into your `plugins/` folder.
2. Restart your server.

That's it! The plugin works with recommended defaults out of the box. You can tweak settings in the config files. The [setup guide](https://wiki.nightbreak.io/ResourcePackManager/setup) explains every folder and option.

# 🟠 Bedrock Support (GeyserMC)

ResourcePackManager automatically converts your merged Java resource pack into a Bedrock-compatible pack for GeyserMC, so Bedrock and Java players see the same custom content. This is enabled by default and requires no additional setup. It converts custom items, 3D held items and custom armor from any plugin whose pack uses Minecraft 1.21.4+ item definitions, such as FreeMinecraftModels, EliteMobs, Oraxen and Nexo. See the [Bedrock conversion guide](https://wiki.nightbreak.io/ResourcePackManager/bedrock).

# 🟠 Proxy Network Setup (Bedrock)

Running multiple Geyser/Floodgate servers behind a proxy? Bedrock clients are limited to one resource pack per network connection. ResourcePackManager works around this by merging all the resource packs across your network into a single pack, which is then sent to Bedrock clients when they log in.

To set this up, just install ResourcePackManager both on your proxy server and the backend server (same ResourcePackManager.jar file). Once everything is set up, restart the proxy twice for the geyser mappings to get saved properly!

Java players receive their packs directly from each backend on server switch. Full guide: [Proxy networks](https://wiki.nightbreak.io/ResourcePackManager/proxy-networks).

# 🟠 Limitations & Compatibility

**Limitations:**

ResourcePackManager merges where possible but cannot fix fundamental conflicts (e.g. GUI unicode spacing clashes or duplicate item model IDs). In conflicts, the higher‑priority pack is chosen.

<details>
<summary><strong>Automerge-compatible plugins</strong></summary>

* [FreeMinecraftModels](https://nightbreak.io/plugin/freeminecraftmodels) (recommended models plugin!)
* [EliteMobs](https://nightbreak.io/plugin/elitemobs) (recommended bosses plugin!)
* [EternalTD](https://nightbreak.io/plugin/eternaltd) (recommended minigame plugin!)
* [BetterStructures](https://nightbreak.io/plugin/betterstructures)
* [CannonRTP](https://nightbreak.io/plugin/cannonrtp)
* [ResurrectionChest](https://nightbreak.io/plugin/resurrectionchest)
* BetterHUD
* ItemsAdder (requires unencrypting & disabling hosting; `/rspm itemsadder configure` does this for you)
* InfiniteVehicles
* MMOInventory
* ModelEngine
* Nexo
* Nova
* Oraxen
* ValhallaMMO (requires `/valhalla resourcepack download`)
* BackpackPlus
* RealisticSurvival
* Vane-Core

*Have a plugin you'd like supported? Let me know on the [Discord](https://discord.gg/9f5QSka)!*

</details>

# 🟠 Commands

* `/resourcepackmanager reload` - reloads the plugin.
* `/resourcepackmanager data_compliance_request` - downloads all hosted data for inspection.
* `/resourcepackmanager status` - gives the current status of RSPM.
* `/rspm setup` - opens the in-game setup menu for plugin updates and recommended plugins.
* `/rspm verbose [on|off]` - turns detailed console logging on or off.
* `/rspm itemsadder configure` - sets up ItemsAdder for ResourcePackManager.

`/rspm` is short for `/resourcepackmanager`. See the [full command and permission list](https://wiki.nightbreak.io/ResourcePackManager/setup#core-commands).

# 🟠 Hosting

ResourcePackManager attempts to self-host the resource packs directly from your server by default. This requires your server ports to be accessible to players. If you can't forward ports or prefer not to self-host, RSPM automatically falls back to free remote hosting on Nightbreak servers, made possible by [Patreon supporters](https://www.patreon.com/magmaguy). You can configure hosting mode in `config.yml`.

Free remote hosting accepts packs up to 51.2 MB; bigger packs need self-hosting. If players reach your server through an HTTPS reverse proxy, set `selfHostExternalUrl`. See [Self-hosting](https://wiki.nightbreak.io/ResourcePackManager/self-hosting) for ports and reachability checks.

<details>
<summary><strong>Remote hosting data policy & TOS</strong></summary>

### ResourcePackManager Data Policy and Compliance

**ResourcePackManager**, developed by MagmaGuy for the Nightbreak game studio, includes an optional auto-host feature that temporarily hosts resource pack data on a remote server.

As of this writing, the hosted data is fully anonymous and serves the sole purpose of simplifying the distribution of resource packs to clients of servers utilizing this service. Future versions of this document may be updated to reflect any changes in data policy and other related matters.

This system complies with:
- **Directive 2000/31/EC** of the European Parliament and of the Council of 8 June 2000
- **Regulation (EU) 2022/2065** of the European Parliament and of the Council of 19 October 2022

For data hosting transparency and compliance with these and other European norms, it is possible to obtain all data related to a server via the `/resourcepackmanager data_compliance_request` command. This command packages a full copy of all files and data associated with the requesting server.

To request the takedown of your server's data, contact MagmaGuy at `magmaguy/at\nightbreak.io` (replace `/at\ ` with `@`). However, due to the system's design, data is only retained for up to 24h after a server using ResourcePackManager shuts down, making email requests largely unnecessary. Nonetheless, the option remains available to ensure full compliance with European norms.

### Data Handling by ResourcePackManager and Nightbreak Servers

1. **Resource Pack Creation**
- ResourcePackManager aggregates resource packs on your server into a single zipped file containing all custom content.

2. **Initialization Request**
- An initialization request is sent to remote servers, creating a `.txt` file with a random UUID. This file can be obtained via the `/resourcepackmanager data_compliance_request`.

3. **SHA1 Request**
- ResourcePackManager transmits the SHA1 code of your resource pack to the remote server, which is saved in the `.txt` file.

4. **File Transmission**
- The zipped resource pack file is sent to the remote server, assigned the same UUID as the `.txt` file. This file can be obtained through `/resourcepackmanager data_compliance_request` and verified to be identical to the original in your output folder, as it is not modified by the Nightbreak servers.

5. **"Still Alive" Ping**
- ResourcePackManager sends a "still alive" ping every 6 hours, transmitting the UUID to the server, which updates the timestamp in the `.txt` file.
- If no "still alive" ping is received for over 24 hours, all data associated with that UUID (the `.txt` file and the resource pack) is deleted from the Nightbreak servers.

### Data Policy

- **Pseudonymous Identification:** Nightbreak assigns a random UUID to your server's files each time the server reboots, ensuring no IP address or identifiable information is stored unless users manually add such information to their resource packs.
- **No Download Logging:** Nightbreak does not log any data related to download requests by Minecraft clients.
- **No Data Sales:** Data uploaded to Nightbreak is not, has never been, and will never be sold.
- **Compliance with Takedown Requests:** Nightbreak will comply with takedown requests from both server administrators and law enforcement agencies.
- **Automatic Data Removal:** All data associated with your server is automatically removed 24 hours after your server shuts down and is reuploaded on every restart for as long as ResourcePackManager is in use and using the auto-host feature.

### Terms of service

As of writing this, the hosting service is provided for free for all users of ResourcePackManager.

It is the user's responsibility to ensure that the data uploaded to servers is not illegal and complies with any Mojang TOS as defined in their EULA.

Abusing the service to host material other than resource packages may result in a permanent denial of service for the offending IP.

The service may, at any time, cease or be modified in such a way that makes old versions unable to connect to it.

We reserve the right to unilaterally terminate this service at any time and for any reason.

</details>

# 🟠 View More by Magmaguy!

<p align="center">
  <a href="https://nightbreak.io/plugin/elitemobs">
    <img src="https://i.postimg.cc/zXQRn1jD/Elite-Mobs.png" width="300"/>
  </a>
  <a href="https://nightbreak.io/plugin/betterstructures">
    <img src="https://i.postimg.cc/3rVK1rPq/Better-Structures.png" width="300"/>
  </a>
</p>
<p align="center">
  <a href="https://nightbreak.io/plugin/extractioncraft">
    <img src="https://i.postimg.cc/gjV6VMYz/extractioncraft.png" width="300"/>
  </a>
  <a href="https://nightbreak.io/plugin/eternaltd">
    <img src="https://i.postimg.cc/Kjf8cCGM/ETD.png" width="300"/>
  </a>
</p>
<p align="center">
  <a href="https://nightbreak.io/plugin/resourcepackmanager">
    <img src="https://i.postimg.cc/Y0GdtBfX/Resource-Pack-Manager.png" width="300"/>
  </a>
  <a href="https://nightbreak.io/plugin/freeminecraftmodels">
    <img src="https://i.postimg.cc/0Qxv6ZHd/FMM.png" width="300"/>
  </a>
</p>
<p align="center">
  <a href="https://nightbreak.io/plugin/betterfood">
    <img src="https://i.postimg.cc/JzcDSB80/Better-Food.png" width="300"/>
  </a>
  <a href="https://nightbreak.io/plugin/resurrectionchest">
    <img src="https://i.imgur.com/qpgXCge.png" width="300"/>
  </a>
</p>

# 🟠 Partner servers

**Matecraft** is the official partner server! It's run by me and is always up to date with the latest experimental features and new dungeons. Great community, check it out!

<p align="center">
  <strong>eternaltd.matecraft.org</strong><br/>
  <img src="https://i.postimg.cc/wBhbQ8vs/header.gif"/>
</p>

# 🟠 Developers

- [Java API guide](https://wiki.nightbreak.io/ResourcePackManager/api): registering your plugin's resource pack for merging and triggering a reload
- [Generated Java reference](https://wiki.nightbreak.io/javadoc/resourcepackmanager/index.html)
- [Maven repository and coordinates](https://wiki.nightbreak.io/developers)
- [Source code](https://github.com/MagmaGuy/ResourcePackManager)
