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