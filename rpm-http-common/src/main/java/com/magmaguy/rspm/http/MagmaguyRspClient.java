package com.magmaguy.rspm.http;

import com.google.gson.Gson;
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
        return doUpload(BASE_URL + "upload", uuid, pack, null);
    }

    private UploadResult doUpload(String url, String uuid, File pack, String networkKey) throws IOException {
        CloseableHttpClient uploadClient = buildClient(uploadSocketTimeoutSeconds);
        inFlight = uploadClient;
        try {
            HttpPost uploadRequest = new HttpPost(url);

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("uuid", uuid, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            if (networkKey != null) {
                builder.addTextBody(
                        "network-key",
                        networkKey,
                        ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            }
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
    // New protocol (Phase 3+) — stubs until magmaguy.com adds endpoints
    // ------------------------------------------------------------------

    /**
     * Upload a Bedrock-variant pack under the same UUID.
     * POST {@code /rsp/upload?variant=bedrock&uuid=<u>}.
     *
     * @throws UnsupportedOperationException until the server adds variant support
     */
    public UploadResult uploadBedrockVariant(String uuid, File pack) throws IOException {
        throw new UnsupportedOperationException(
                "Bedrock-variant uploads not yet implemented server-side");
    }

    /**
     * Upload tagged for a network. POST {@code /rsp/upload} with a {@code network-key}
     * form field added alongside the regular {@code uuid} + {@code file} fields.
     *
     * <p>If the server does not yet recognize the {@code network-key} field (Phase 3
     * server contract), it ignores the extra field and the upload still succeeds as
     * a regular upload — the desired graceful-degradation behavior.</p>
     */
    public UploadResult uploadNetworkTagged(String uuid, File pack, String networkKey) throws IOException {
        return doUpload(BASE_URL + "upload", uuid, pack, networkKey);
    }

    /**
     * GET {@code /rsp/network/<key>/manifest}. Returns the current set of
     * backend pack URLs registered in this network.
     *
     * @throws UnsupportedOperationException until the server adds network endpoints
     */
    public ManifestResult fetchNetworkManifest(String networkKey) throws IOException {
        throw new UnsupportedOperationException(
                "Network manifest not yet implemented server-side");
    }

    /**
     * POST {@code /rsp/network/<key>/merged}. Uploads the proxy-merged network pack.
     *
     * @throws UnsupportedOperationException until the server adds network endpoints
     */
    public UploadResult uploadNetworkMerged(String networkKey, File mergedPack) throws IOException {
        throw new UnsupportedOperationException(
                "Network merged upload not yet implemented server-side");
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

    public record ManifestResult(List<Entry> entries) {
        public record Entry(String uuid, String url, String sha1, int priority, long lastSeenMillis) {
        }
    }
}
