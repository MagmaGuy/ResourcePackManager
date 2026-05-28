package com.magmaguy.resourcepackmanager.http;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reusable HTTP client for the magmaguy.com resource-pack hosting API.
 *
 * <p>This client is stateless with respect to session: callers (e.g. {@code AutoHost})
 * supply the per-call {@code uuid}/{@code sha1} arguments and own the lifecycle of
 * any keep-alive scheduling. The client only handles HTTP request/response plumbing.</p>
 *
 * <p>An in-flight cancellation mechanism is provided via {@link #abortInFlight()}:
 * the most recently issued blocking request keeps a reference to its underlying
 * {@code CloseableHttpClient} so that on plugin shutdown the multi-MB upload can be
 * torn down promptly rather than blocking the async task past {@code onDisable}.</p>
 */
public final class MagmaguyRspClient implements AutoCloseable {

    public static final String BASE_URL = "https://magmaguy.com/rsp/";

    private static final int DEFAULT_CONNECT_TIMEOUT = 30;

    private final Logger log;
    private final CloseableHttpClient httpClient;
    private final int uploadSocketTimeoutSeconds;

    /**
     * Tracks the HTTP client currently in flight (initialize / sha1 / upload /
     * still_alive / data_compliance). On plugin disable we close it to abort
     * the blocking request immediately. May reference either {@link #httpClient}
     * (regular requests) or a per-call upload client (longer socket timeout).
     */
    private volatile CloseableHttpClient inFlight;

    /**
     * @param logger                      JUL logger used for all status/error output. Plugin
     *                                    callers should pass {@code plugin.getLogger()} so that
     *                                    Bukkit's {@code PluginLogger} prefixes each line with
     *                                    {@code [ResourcePackManager]}.
     * @param defaultSocketTimeoutSeconds applied to initialize/sha1/still_alive calls
     * @param uploadSocketTimeoutSeconds  applied to upload calls (file transfers can take minutes)
     */
    public MagmaguyRspClient(Logger logger, int defaultSocketTimeoutSeconds, int uploadSocketTimeoutSeconds) {
        this.log = logger != null ? logger : Logger.getLogger(MagmaguyRspClient.class.getName());
        this.httpClient = buildClient(defaultSocketTimeoutSeconds);
        this.uploadSocketTimeoutSeconds = uploadSocketTimeoutSeconds;
    }

    private static CloseableHttpClient buildClient(int socketTimeoutSeconds) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(DEFAULT_CONNECT_TIMEOUT))
                .setSocketTimeout(Timeout.ofSeconds(socketTimeoutSeconds))
                .build();

        HttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(DEFAULT_CONNECT_TIMEOUT))
                .setResponseTimeout(Timeout.ofSeconds(socketTimeoutSeconds))
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    // ------------------------------------------------------------------
    // Existing protocol — preserves AutoHost's prior behavior
    // ------------------------------------------------------------------

    /**
     * POST {@code /rsp/initialize}. Pass {@code null} (or empty) to request a new
     * UUID, or pass an existing one to resume that session.
     *
     * @return the server-assigned uuid on success, or empty on error. Detailed
     *         error info is logged via JUL.
     */
    public Optional<String> initialize(String existingUuidOrNull) throws IOException {
        HttpPost httpPost = new HttpPost(BASE_URL + "initialize");
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addTextBody(
                "uuid",
                existingUuidOrNull == null ? "" : existingUuidOrNull,
                ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
        httpPost.setEntity(builder.build());

        inFlight = httpClient;
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseString = readEntity(response.getEntity());
            int statusCode = response.getCode();

            if (statusCode >= 200 && statusCode < 300) {
                try {
                    Gson gson = new Gson();
                    JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);

                    if (jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean()) {
                        String uuid = jsonResponse.get("uuid").getAsString();
                        String message = jsonResponse.has("message")
                                ? jsonResponse.get("message").getAsString()
                                : "";
                        log.info("Server initialized successfully: " + message);
                        return Optional.of(uuid);
                    } else {
                        log.warning("Server returned error in response: " + responseString);
                        return Optional.empty();
                    }
                } catch (Exception e) {
                    // JSON parsing failed — validate if it looks like a UUID before using it
                    String trimmedResponse = responseString.trim();
                    try {
                        UUID.fromString(trimmedResponse);
                        log.info("Server initialized with UUID: " + trimmedResponse);
                        return Optional.of(trimmedResponse);
                    } catch (IllegalArgumentException ignored) {
                        log.warning("Invalid response format from server: " + responseString);
                        return Optional.empty();
                    }
                }
            } else {
                logErrorResponse(responseString, statusCode, "initialization");
                return Optional.empty();
            }
        } finally {
            inFlight = null;
        }
    }

    /**
     * POST {@code /rsp/sha1}. Returns a structured {@link Sha1Result} describing
     * whether the server already has this pack and, on failure, the parsed
     * {@link RspError} (so callers can react to e.g. {@code SESSION_NOT_FOUND}
     * before wasting a multi-MB upload).
     *
     * @throws IOException on transport-level failures
     */
    public Sha1Result sha1Check(String uuid, String sha1) throws IOException {
        HttpPost httpPost = new HttpPost(BASE_URL + "sha1");

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addTextBody("uuid", uuid, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
        builder.addTextBody("sha1", sha1, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));

        HttpEntity entity = builder.build();
        httpPost.setEntity(entity);

        inFlight = httpClient;
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseString = readEntity(response.getEntity());
            int statusCode = response.getCode();

            if (statusCode >= 200 && statusCode < 300) {
                try {
                    Gson gson = new Gson();
                    JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);

                    if (jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean()) {
                        boolean uploadNeeded = jsonResponse.get("uploadNeeded").getAsBoolean();
                        return new Sha1Result(!uploadNeeded, null);
                    } else {
                        log.warning("Server returned error in SHA1 response: " + responseString);
                        return new Sha1Result(false, null);
                    }
                } catch (Exception e) {
                    // Fallback to boolean parsing (backward compatibility)
                    String trimmedResponse = responseString.trim();
                    if (trimmedResponse.equals("true") || trimmedResponse.equals("false")) {
                        return new Sha1Result(Boolean.valueOf(trimmedResponse), null);
                    } else {
                        log.warning("Invalid SHA1 response format from server: " + responseString);
                        return new Sha1Result(false, null);
                    }
                }
            } else {
                RspError err = logErrorResponse(responseString, statusCode, "SHA1 check");
                return new Sha1Result(false, err);
            }
        } finally {
            inFlight = null;
        }
    }

    /**
     * @deprecated prefer {@link #sha1Check(String, String)} so callers can react
     * to server-returned errors (notably {@code SESSION_NOT_FOUND}) before
     * proceeding to upload. This thin wrapper preserves the previous boolean
     * shape for callers that don't need the error detail.
     */
    @Deprecated
    public boolean sha1Matches(String uuid, String sha1) throws IOException {
        return sha1Check(uuid, sha1).matched();
    }

    /**
     * POST {@code /rsp/upload}. Uploads the file binary.
     *
     * @return structured upload result with success flag, access URL on success,
     *         or a parsed {@link RspError} on failure (also logged).
     */
    public UploadResult upload(String uuid, File pack) throws IOException {
        return doUpload(BASE_URL + "upload", uuid, pack);
    }

    private UploadResult doUpload(String url, String uuid, File pack) throws IOException {
        CloseableHttpClient uploadClient = buildClient(uploadSocketTimeoutSeconds);
        inFlight = uploadClient;
        try {
            HttpPost uploadRequest = new HttpPost(url);

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("uuid", uuid, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            builder.addBinaryBody("file", pack, ContentType.APPLICATION_OCTET_STREAM, pack.getName());

            uploadRequest.setEntity(builder.build());

            try (CloseableHttpResponse response = uploadClient.execute(uploadRequest)) {
                String responseString = readEntity(response.getEntity());
                int statusCode = response.getCode();

                if (statusCode >= 200 && statusCode < 300) {
                    return new UploadResult(true, BASE_URL + uuid, null);
                } else {
                    RspError err = logErrorResponse(responseString, statusCode, "upload");
                    if (err == null) {
                        err = new RspError(null, null, responseString, statusCode);
                    }
                    return new UploadResult(false, null, err);
                }
            }
        } finally {
            inFlight = null;
            try {
                uploadClient.close();
            } catch (IOException ignored) {
                // expected during in-flight cancellation
            }
        }
    }

    /**
     * POST {@code /rsp/still_alive}. Returns true on 2xx, false otherwise
     * (session may have expired).
     */
    public boolean stillAlive(String uuid) throws IOException {
        HttpPost httpPost = new HttpPost(BASE_URL + "still_alive");

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addTextBody("uuid", uuid);
        httpPost.setEntity(builder.build());

        inFlight = httpClient;
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseString = readEntity(response.getEntity());
            int statusCode = response.getCode();

            if (statusCode >= 200 && statusCode < 300) {
                return true;
            } else {
                logErrorResponse(responseString, statusCode, "still alive");
                return false;
            }
        } finally {
            inFlight = null;
        }
    }

    /**
     * Best-effort public IPv4 detection. Used by AutoHost when
     * {@code selfHostExternalHost} is not configured — the plugin has no other
     * way to know its own publicly-routable address (Bukkit.getIp() returns the
     * bind address, which is often 0.0.0.0 or a private LAN address).
     *
     * <p>Sends a GET to a small set of well-known third-party "what's my IP"
     * endpoints and returns the first non-empty response that parses as an
     * IPv4 dotted-quad. Returns {@link Optional#empty()} if every endpoint
     * fails or returns garbage — caller should treat that as "we can't probe,
     * fall back to remote hosting" rather than risk publishing a 0.0.0.0 URL
     * to clients.</p>
     *
     * <p>Endpoints (in order):</p>
     * <ol>
     *   <li>{@code https://api.ipify.org} — plain-text IPv4, no rate limit
     *       on the free tier as of 2026.</li>
     *   <li>{@code https://checkip.amazonaws.com} — AWS-hosted, plain text
     *       with trailing newline, used as fallback when ipify is degraded.</li>
     * </ol>
     */
    public Optional<String> detectPublicIp() {
        String[] endpoints = {
                "https://api.ipify.org",
                "https://checkip.amazonaws.com"
        };
        for (String endpoint : endpoints) {
            try {
                org.apache.hc.client5.http.classic.methods.HttpGet get =
                        new org.apache.hc.client5.http.classic.methods.HttpGet(endpoint);
                inFlight = httpClient;
                try (CloseableHttpResponse response = httpClient.execute(get)) {
                    if (response.getCode() < 200 || response.getCode() >= 300) continue;
                    String body = readEntity(response.getEntity()).trim();
                    if (isLikelyIpv4(body)) return Optional.of(body);
                }
            } catch (IOException | RuntimeException e) {
                log.fine("Public-IP probe via " + endpoint + " failed: " + e.getMessage());
            } finally {
                inFlight = null;
            }
        }
        return Optional.empty();
    }

    /**
     * Lightweight IPv4 dotted-quad check. We accept any 4-octet form with
     * each octet 0..255 — full RFC1918 / loopback filtering is the caller's
     * responsibility (they want to KNOW about a 127.0.0.1 response so they
     * can refuse to use it as a public host).
     */
    private static boolean isLikelyIpv4(String s) {
        if (s == null) return false;
        String[] parts = s.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * POST {@code /rsp/data_compliance} — downloads the data-compliance zip into
     * the given destination file. Caller is responsible for ensuring the parent
     * directory exists and is writable.
     */
    public void downloadDataCompliance(String uuid, File destination) throws IOException {
        HttpPost httpPost = new HttpPost(BASE_URL + "data_compliance");

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addTextBody("uuid", uuid);
        httpPost.setEntity(builder.build());

        inFlight = httpClient;
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            HttpEntity responseEntity = response.getEntity();
            if (responseEntity != null) {
                try (FileOutputStream outStream = new FileOutputStream(destination)) {
                    responseEntity.writeTo(outStream);
                }
            }
        } finally {
            inFlight = null;
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Abort any in-flight blocking request by closing the underlying HTTP client.
     * Used on plugin disable so multi-MB uploads don't keep async tasks alive
     * past {@code onDisable}.
     */
    public void abortInFlight() {
        CloseableHttpClient client = inFlight;
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
                // expected — abort during in-flight write may throw
            }
            inFlight = null;
        }
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (IOException ignored) {
            // ignore close failures
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * {@link EntityUtils#toString(HttpEntity)} declares a checked
     * {@link ParseException}. Convert it to {@link IOException} so callers
     * only see one failure mode.
     */
    private static String readEntity(HttpEntity entity) throws IOException {
        try {
            return EntityUtils.toString(entity);
        } catch (ParseException e) {
            throw new IOException("Failed to parse response entity", e);
        }
    }

    /**
     * Best-effort parse of a JSON error envelope of the shape
     * {@code {"error": {"code": "...", "type": "...", "message": "..."}}}.
     * Returns the parsed error on success (and logs it in the same format
     * AutoHost previously did), or {@code null} when the payload is not
     * recognizable — in which case a single fallback line is logged.
     */
    private RspError logErrorResponse(String responseString, int statusCode, String operation) {
        try {
            Gson gson = new Gson();
            JsonObject errorResponse = gson.fromJson(responseString, JsonObject.class);

            if (errorResponse != null && errorResponse.has("error")) {
                JsonObject error = errorResponse.getAsJsonObject("error");
                String errorCode = error.has("code") ? error.get("code").getAsString() : null;
                String errorMessage = error.has("message") ? error.get("message").getAsString() : null;
                String errorType = error.has("type") ? error.get("type").getAsString() : null;

                log.warning("=== Resource Pack " + operation.toUpperCase() + " ERROR ===");
                log.warning("Error Code: " + errorCode);
                log.warning("Error Type: " + errorType);
                log.warning("Message: " + errorMessage);
                log.warning("HTTP Status: " + statusCode);
                log.warning("=====================================");

                // Mirror the human-readable hints AutoHost previously emitted.
                if (errorCode != null) {
                    switch (errorCode) {
                        case "MISSING_REQUIRED_FILES":
                            log.warning("Your resource pack structure is incorrect!");
                            log.warning("Make sure pack.png and pack.mcmeta are in the root of your zip file.");
                            break;
                        case "FILE_TOO_LARGE":
                            log.warning("Your resource pack is too large! Please reduce the file size.");
                            break;
                        case "INVALID_FILE_FORMAT":
                            log.warning("Your resource pack file is corrupted or not a valid zip file.");
                            break;
                        case "SESSION_NOT_FOUND":
                            log.warning("Server session expired. Will attempt to reinitialize...");
                            break;
                        case "SERVER_UNAVAILABLE":
                            log.warning("Remote server is temporarily unavailable. Will retry later.");
                            break;
                        default:
                            break;
                    }
                }

                return new RspError(errorCode, errorType, errorMessage, statusCode);
            } else {
                log.warning("Server error during " + operation + " (HTTP " + statusCode + "): " + responseString);
                return null;
            }
        } catch (Exception e) {
            log.log(Level.WARNING,
                    "Server error during " + operation + " (HTTP " + statusCode + "): " + responseString, e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Result records
    // ------------------------------------------------------------------

    public record UploadResult(boolean success, String urlOrNull, RspError errorOrNull) {
    }

    /**
     * Result of a {@link #sha1Check(String, String)} call.
     *
     * @param matched     true if the server already has this sha1 (no upload needed)
     * @param errorOrNull populated when the server returned a structured error
     *                    (notably {@code SESSION_NOT_FOUND}); null otherwise
     */
    public record Sha1Result(boolean matched, RspError errorOrNull) {
    }

    // ------------------------------------------------------------------
    // Reachability probe — POST /rsp/probe
    // ------------------------------------------------------------------

    /**
     * Result of a {@link #probe(String)} call.
     *
     * @param reachable    true if the hoster fetched the URL successfully (any non-5xx)
     * @param status       HTTP status the hoster received (0 if no response)
     * @param durationMs   total time the hoster spent on the probe
     * @param reasonOrNull machine-readable reason on failure (e.g. {@code "PRIVATE_HOST_REJECTED"},
     *                     {@code "CONNECT_TIMEOUT"}, {@code "ECONNREFUSED"}, {@code "RATE_LIMITED"})
     */
    public record ProbeResult(boolean reachable, int status, long durationMs, String reasonOrNull) {}

    /**
     * Ask the hoster to fetch the given URL from its public network vantage and
     * report back whether the URL is reachable. Used by RSPM backends to verify
     * a self-host candidate URL is actually exposed to the internet before
     * announcing it to the proxy.
     *
     * <p>The hoster enforces SSRF guards (rejects RFC1918 / loopback / link-local
     * targets) and a per-source-IP rate limit. On rate-limit hit, the result
     * comes back as {@code reachable=false, reason="RATE_LIMITED"}.</p>
     */
    public ProbeResult probe(String url) throws IOException {
        HttpPost req = new HttpPost(BASE_URL + "probe");
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addTextBody("url", url, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
        req.setEntity(builder.build());

        inFlight = httpClient;
        try (CloseableHttpResponse response = httpClient.execute(req)) {
            String body = readEntity(response.getEntity());
            int code = response.getCode();
            if (code < 200 || code >= 300) {
                logErrorResponse(body, code, "probe");
                return new ProbeResult(false, 0, 0L, "HTTP_" + code);
            }
            try {
                JsonObject json = new Gson().fromJson(body, JsonObject.class);
                if (json == null || !json.has("success") || !json.get("success").getAsBoolean()) {
                    return new ProbeResult(false, 0, 0L, "BAD_RESPONSE");
                }
                boolean reachable = json.has("reachable") && json.get("reachable").getAsBoolean();
                int status = json.has("status") ? json.get("status").getAsInt() : 0;
                long durationMs = json.has("durationMs") ? json.get("durationMs").getAsLong() : 0L;
                String reason = (json.has("reason") && !json.get("reason").isJsonNull())
                        ? json.get("reason").getAsString() : null;
                return new ProbeResult(reachable, status, durationMs, reason);
            } catch (Exception e) {
                return new ProbeResult(false, 0, 0L, "PARSE_ERROR");
            }
        } finally {
            inFlight = null;
        }
    }

    // ------------------------------------------------------------------
    // Bedrock pack relay — POST /rsp/bedrock/{upload,list,delete}, GET /rsp/bedrock/file/...
    // ------------------------------------------------------------------

    /**
     * One entry in a network's relay index. Returned by
     * {@link #uploadBedrockRelay} (the just-stored entry) and
     * {@link #listBedrockRelay} (every live entry for a network).
     */
    public record BedrockRelayEntry(String backendId, String kind, String sha1OrNull,
                                    long sizeBytes, String lastUploadedAt) {}

    /**
     * Upload a Bedrock pack ({@code kind="zip"}) or Geyser mappings file
     * ({@code kind="mappings"}) to the relay under this network. The relay
     * stores by {@code (sha256(networkKey)[:32], backendId, kind)} — re-uploading
     * the same triple overwrites the previous file and resets its TTL.
     */
    public Optional<BedrockRelayEntry> uploadBedrockRelay(
            String networkKey, String backendId, String kind, File file, String sha1HexOrNull) throws IOException {
        CloseableHttpClient uploadClient = buildClient(uploadSocketTimeoutSeconds);
        inFlight = uploadClient;
        try {
            HttpPost req = new HttpPost(BASE_URL + "bedrock/upload");
            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("networkKey", networkKey, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            builder.addTextBody("backendId", backendId, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            builder.addTextBody("kind", kind, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            if (sha1HexOrNull != null && !sha1HexOrNull.isBlank()) {
                builder.addTextBody("sha1", sha1HexOrNull, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            }
            builder.addBinaryBody("file", file, ContentType.APPLICATION_OCTET_STREAM, file.getName());
            req.setEntity(builder.build());

            try (CloseableHttpResponse response = uploadClient.execute(req)) {
                String body = readEntity(response.getEntity());
                int code = response.getCode();
                if (code < 200 || code >= 300) {
                    logErrorResponse(body, code, "bedrock relay upload");
                    return Optional.empty();
                }
                JsonObject json = new Gson().fromJson(body, JsonObject.class);
                if (json == null || !json.has("success") || !json.get("success").getAsBoolean()
                        || !json.has("entry")) {
                    return Optional.empty();
                }
                return Optional.of(toEntry(json.getAsJsonObject("entry")));
            }
        } finally {
            inFlight = null;
            try { uploadClient.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * List all live relay entries for the given network. The proxy uses this on
     * direct-fetch failure to discover backends that uploaded via the bridge.
     */
    public List<BedrockRelayEntry> listBedrockRelay(String networkKey) throws IOException {
        HttpPost req = new HttpPost(BASE_URL + "bedrock/list");
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addTextBody("networkKey", networkKey, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
        req.setEntity(builder.build());

        inFlight = httpClient;
        try (CloseableHttpResponse response = httpClient.execute(req)) {
            String body = readEntity(response.getEntity());
            int code = response.getCode();
            if (code < 200 || code >= 300) {
                logErrorResponse(body, code, "bedrock relay list");
                return Collections.emptyList();
            }
            JsonObject json = new Gson().fromJson(body, JsonObject.class);
            if (json == null || !json.has("success") || !json.get("success").getAsBoolean()
                    || !json.has("entries")) {
                return Collections.emptyList();
            }
            JsonArray arr = json.getAsJsonArray("entries");
            List<BedrockRelayEntry> out = new ArrayList<>(arr.size());
            for (JsonElement e : arr) {
                if (e.isJsonObject()) out.add(toEntry(e.getAsJsonObject()));
            }
            return out;
        } finally {
            inFlight = null;
        }
    }

    /**
     * Download a relay entry to {@code dest}. The path is keyed by
     * {@code networkKeyHash} (the same 32-char sha256 prefix the hoster stores
     * under) rather than the raw key — the GET URL would otherwise leak the
     * network identity in access logs.
     *
     * @return true on 200 with body written to {@code dest}; false on 404 or
     *         transport error (caller should fall back to direct fetch / wait).
     */
    public boolean downloadBedrockRelay(String networkKeyHash, String backendId, String kind, File dest) throws IOException {
        String url = BASE_URL + "bedrock/file/" + networkKeyHash + "/" + backendId + "/" + kind;
        org.apache.hc.client5.http.classic.methods.HttpGet req =
                new org.apache.hc.client5.http.classic.methods.HttpGet(url);
        inFlight = httpClient;
        try (CloseableHttpResponse response = httpClient.execute(req)) {
            int code = response.getCode();
            if (code == 404) return false;
            if (code < 200 || code >= 300) {
                String body = readEntity(response.getEntity());
                logErrorResponse(body, code, "bedrock relay download");
                return false;
            }
            HttpEntity entity = response.getEntity();
            if (entity == null) return false;
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                entity.writeTo(fos);
            }
            return true;
        } finally {
            inFlight = null;
        }
    }

    /**
     * Drop a backend's relay entry (or both kinds if {@code kindOrNull == null}).
     * Best-effort — failure is logged but not surfaced; the hoster will sweep it
     * via TTL within {@code entryTtlMinutes}.
     */
    public void deleteBedrockRelay(String networkKey, String backendId, String kindOrNull) {
        try {
            HttpPost req = new HttpPost(BASE_URL + "bedrock/delete");
            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("networkKey", networkKey, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            builder.addTextBody("backendId", backendId, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            if (kindOrNull != null && !kindOrNull.isBlank()) {
                builder.addTextBody("kind", kindOrNull, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            }
            req.setEntity(builder.build());

            inFlight = httpClient;
            try (CloseableHttpResponse response = httpClient.execute(req)) {
                int code = response.getCode();
                if (code < 200 || code >= 300) {
                    log.fine("Bedrock relay delete returned HTTP " + code);
                }
            }
        } catch (IOException e) {
            log.fine("Bedrock relay delete failed: " + e.getMessage());
        } finally {
            inFlight = null;
        }
    }

    private static BedrockRelayEntry toEntry(JsonObject e) {
        String backendId = e.has("backendId") ? e.get("backendId").getAsString() : "";
        String kind = e.has("kind") ? e.get("kind").getAsString() : "";
        String sha1 = (e.has("sha1") && !e.get("sha1").isJsonNull()) ? e.get("sha1").getAsString() : null;
        long size = e.has("sizeBytes") ? e.get("sizeBytes").getAsLong() : 0L;
        String at = e.has("lastUploadedAt") ? e.get("lastUploadedAt").getAsString() : "";
        return new BedrockRelayEntry(backendId, kind, sha1, size, at);
    }
}
