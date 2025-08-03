package com.magmaguy.resourcepackmanager.autohost;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import lombok.Getter;
import lombok.Setter;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class AutoHost {
    private static final String finalURL = "https://magmaguy.com/rsp/";
    @Setter
    private static boolean done = false;
//    private static final String finalURL = "https://localhost:50000/";

    private static BukkitTask keepAlive = null;
    @Getter
    private static String rspUUID = null;
    @Setter
    private static boolean firstUpload = true;

    private AutoHost() {
    }

    public static void sendResourcePack(Player player) {
        Logger.info("Sending resource pack to " + player.getName() + ". rspUUID is null: " + (rspUUID == null) + ". done: " + done + ". firstUpload: " + firstUpload + ".");
        if (rspUUID == null || !done) return;
        Logger.info("Sending resource pack to " + player.getName());
        player.setResourcePack(finalURL + rspUUID, Mix.getFinalSHA1Bytes(), DefaultConfig.getResourcePackPrompt(), DefaultConfig.isForceResourcePack());
    }

    public static void initialize() {
        if (!DefaultConfig.isAutoHost()) return;
        if (Mix.getFinalResourcePack() == null) return;
        Logger.info("Starting autohost!");
        firstUpload = true;
        done = false;
        rspUUID = null;
        if (keepAlive != null) keepAlive.cancel();

        keepAlive = new BukkitRunnable() {
            int counter = 0;

            @Override
            public void run() {
                if (rspUUID != null) {
                    counter = 0;
                    try {
                        sendStillAlive();
                    } catch (Exception e) {
                        rspUUID = null;
                        Logger.warn("Failed to autohost resource pack!");
                        e.printStackTrace();
                    }
                } else {
                    checkFileExistence();
                    if (rspUUID == null && counter % 10 == 0) {
                        Logger.warn("Failed to connect to remote server to autohost the resource pack!");
                    }
                    counter++;
                }
            }
        }.runTaskTimerAsynchronously(ResourcePackManager.plugin, 0, 6 * 60 * 60 * 20L);
    }

    private static void checkFileExistence() {
        initializeLink();
        if (rspUUID == null) {
            Logger.info("No resource pack found on the server! Uploading resource pack to the server...");
            return;
        }
        if (!sendSHA1()) uploadFile();
        else {
            //Case if the remote server already has the resource pack
            done = true;
            if (firstUpload) {
                //Recover from a reload by sending the pack to online players
                for (Player player : Bukkit.getOnlinePlayers())
                    AutoHost.sendResourcePack(player);
            }
            firstUpload = false;
        }
    }

    public static void initializeLink() {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(finalURL + "initialize");
            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("uuid", DataConfig.getRspUUID(), ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            httpPost.setEntity(builder.build());

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseString = EntityUtils.toString(response.getEntity());
                int statusCode = response.getCode();

                if (statusCode >= 200 && statusCode < 300) {
                    // Success - parse JSON response
                    try {
                        Gson gson = new Gson();
                        JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);

                        // Check if response indicates success
                        if (jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean()) {
                            rspUUID = jsonResponse.get("uuid").getAsString();
                            DataConfig.setRspUUID(rspUUID);
                            Logger.info("Server initialized successfully: " + jsonResponse.get("message").getAsString());
                        } else {
                            // Server returned error in success status code
                            Logger.warn("Server returned error in response: " + responseString);
                            rspUUID = null;
                        }
                    } catch (Exception e) {
                        // JSON parsing failed - validate if it looks like a UUID before using it
                        String trimmedResponse = responseString.trim();
                        if (isValidUUID(trimmedResponse)) {
                            rspUUID = trimmedResponse;
                            DataConfig.setRspUUID(rspUUID);
                            Logger.info("Server initialized with UUID: " + rspUUID);
                        } else {
                            Logger.warn("Invalid response format from server: " + responseString);
                            rspUUID = null;
                        }
                    }
                } else {
                    // Error - parse and log detailed error message
                    handleErrorResponse(responseString, statusCode, "initialization");
                    rspUUID = null;
                }
            } catch (Exception e) {
                Logger.warn("Failed to communicate with remote server!");
                e.printStackTrace();
                rspUUID = null;
            }
        } catch (Exception e) {
            rspUUID = null;
            Logger.warn("Failed remote server initialization.");
            e.printStackTrace();
        }
    }

    // Helper method to validate UUID format
    private static boolean isValidUUID(String uuidString) {
        if (uuidString == null || uuidString.isEmpty()) {
            return false;
        }

        // Check if it looks like a UUID (basic validation)
        // UUIDs are typically 36 characters with dashes or 32 characters without
        String cleanUuid = uuidString.replaceAll("-", "");
        if (cleanUuid.length() != 32) {
            return false;
        }

        // Check if it contains only valid hex characters
        return cleanUuid.matches("[0-9a-fA-F]+");
    }

    public static void uploadFile() {
        Logger.info("Uploading resource!");

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost uploadFile = new HttpPost(finalURL + "upload");

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("uuid", rspUUID, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            builder.addBinaryBody("file", Mix.getFinalResourcePack(), ContentType.APPLICATION_OCTET_STREAM, Mix.getFinalResourcePack().getName());

            uploadFile.setEntity(builder.build());

            try (CloseableHttpResponse response = httpClient.execute(uploadFile)) {
                String responseString = EntityUtils.toString(response.getEntity());
                int statusCode = response.getCode();

                if (statusCode >= 200 && statusCode < 300) {
                    Logger.info("Uploaded resource pack for automatic hosting! url: " + finalURL + rspUUID);
                    done = true;
                    if (firstUpload) {
                        //Recover from a reload by sending the pack to online players
                        for (Player player : Bukkit.getOnlinePlayers())
                            AutoHost.sendResourcePack(player);
                    }
                    firstUpload = false;
                } else {
                    // Handle detailed error messages from server
                    handleErrorResponse(responseString, statusCode, "upload");
                }
            } catch (Exception e) {
                Logger.warn("Failed to communicate with remote server during upload!");
                e.printStackTrace();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean sendSHA1() {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(finalURL + "sha1");

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("uuid", rspUUID, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            builder.addTextBody("sha1", Mix.getFinalSHA1(), ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));

            HttpEntity entity = builder.build();
            httpPost.setEntity(entity);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseString = EntityUtils.toString(response.getEntity());
                int statusCode = response.getCode();

                if (statusCode >= 200 && statusCode < 300) {
                    try {
                        // Parse JSON response
                        Gson gson = new Gson();
                        JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);

                        // Check if response indicates success
                        if (jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean()) {
                            boolean uploadNeeded = jsonResponse.get("uploadNeeded").getAsBoolean();

                            if (!uploadNeeded) {
                                Logger.info("Remote server already has this resource pack!");
                                done = true;
                                if (firstUpload) {
                                    //Recover from a reload by sending the pack to online players
                                    for (Player player : Bukkit.getOnlinePlayers())
                                        AutoHost.sendResourcePack(player);
                                }
                                firstUpload = false;
                            }
                            return !uploadNeeded; // Return true if no upload needed
                        } else {
                            // Server returned error in success status code
                            Logger.warn("Server returned error in SHA1 response: " + responseString);
                            return false;
                        }
                    } catch (Exception e) {
                        // Fallback to boolean parsing (backward compatibility)
                        String trimmedResponse = responseString.trim();
                        if (trimmedResponse.equals("true") || trimmedResponse.equals("false")) {
                            boolean result = Boolean.valueOf(trimmedResponse);
                            Logger.info("Remote server already has this resource pack! Response: " + responseString);
                            return result;
                        } else {
                            Logger.warn("Invalid SHA1 response format from server: " + responseString);
                            return false;
                        }
                    }
                } else {
                    // Handle error response
                    handleErrorResponse(responseString, statusCode, "SHA1 check");
                    return false;
                }
            } catch (Exception e) {
                Logger.warn("Failed to communicate with remote server during SHA1 check!");
                e.printStackTrace();
                return false;
            }
        } catch (Exception e) {
            Logger.warn("Failed to create HTTP client for SHA1 check!");
            e.printStackTrace();
            return false;
        }
    }

    private static void sendStillAlive() throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(finalURL + "still_alive");

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("uuid", rspUUID);
            httpPost.setEntity(builder.build());

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseString = EntityUtils.toString(response.getEntity());
                int statusCode = response.getCode();

                if (statusCode >= 200 && statusCode < 300) {
                    // Success - optionally log the success message
                    // Logger.info("Still alive ping successful");
                } else {
                    // Handle error - this might indicate session expired
                    handleErrorResponse(responseString, statusCode, "still alive");
                    // Reset UUID to trigger re-initialization
                    rspUUID = null;
                }
            } catch (Exception e) {
                Logger.warn("Failed to communicate with remote server during still alive ping!");
                e.printStackTrace();
            }
        }
    }

    public static void dataComplianceRequest() throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(finalURL + "data_compliance");

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("uuid", rspUUID);
            httpPost.setEntity(builder.build());

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                HttpEntity responseEntity = response.getEntity();

                if (responseEntity != null) {
                    // Save the response as a zip file
                    File zipFile = new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath() + File.separatorChar + "data_compliance" + File.separatorChar + "data.zip");
                    if (!zipFile.getParentFile().exists()) zipFile.mkdirs();
                    if (zipFile.exists()) zipFile.delete();
                    zipFile.createNewFile();
                    try (FileOutputStream outStream = new FileOutputStream(zipFile)) {
                        responseEntity.writeTo(outStream);
                    }
                    InputStream inputStream = ResourcePackManager.plugin.getResource("ReadMe.md");

                    File readMe = new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath() + File.separatorChar + "data_compliance" + File.separatorChar + "ReadMe.md");
                    if (!readMe.exists()) readMe.createNewFile();

                    // Copy the InputStream to the file
                    Files.copy(inputStream, readMe.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                Logger.warn("Failed to communicate with remote server!");
                e.printStackTrace();
            }
        }
    }

    public static void shutdown() {
        if (keepAlive != null) keepAlive.cancel();
        done = false;
        rspUUID = null;
    }

    private static void handleErrorResponse(String responseString, int statusCode, String operation) {
        try {
            Gson gson = new Gson();
            JsonObject errorResponse = gson.fromJson(responseString, JsonObject.class);

            if (errorResponse.has("error")) {
                JsonObject error = errorResponse.getAsJsonObject("error");
                String errorCode = error.get("code").getAsString();
                String errorMessage = error.get("message").getAsString();
                String errorType = error.get("type").getAsString();

                Logger.warn("=== Resource Pack " + operation.toUpperCase() + " ERROR ===");
                Logger.warn("Error Code: " + errorCode);
                Logger.warn("Error Type: " + errorType);
                Logger.warn("Message: " + errorMessage);
                Logger.warn("HTTP Status: " + statusCode);
                Logger.warn("=====================================");

                // Handle specific error types
                switch (errorCode) {
                    case "MISSING_REQUIRED_FILES":
                        Logger.warn("Your resource pack structure is incorrect!");
                        Logger.warn("Make sure pack.png and pack.mcmeta are in the root of your zip file.");
                        break;
                    case "FILE_TOO_LARGE":
                        Logger.warn("Your resource pack is too large! Please reduce the file size.");
                        break;
                    case "INVALID_FILE_FORMAT":
                        Logger.warn("Your resource pack file is corrupted or not a valid zip file.");
                        break;
                    case "SESSION_NOT_FOUND":
                        Logger.warn("Server session expired. Will attempt to reinitialize...");
                        rspUUID = null; // Trigger re-initialization
                        break;
                    case "SERVER_UNAVAILABLE":
                        Logger.warn("Remote server is temporarily unavailable. Will retry later.");
                        break;
                }
            } else {
                // Fallback for non-JSON error responses
                Logger.warn("Server error during " + operation + " (HTTP " + statusCode + "): " + responseString);
            }
        } catch (Exception e) {
            // Fallback if JSON parsing fails
            Logger.warn("Server error during " + operation + " (HTTP " + statusCode + "): " + responseString);
        }
    }
}
