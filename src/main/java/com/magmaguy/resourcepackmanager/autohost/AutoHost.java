package com.magmaguy.resourcepackmanager.autohost;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import lombok.Getter;
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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public class AutoHost {
//        private static final String finalURL = "http://localhost:3000/";
    private static final String finalURL = "http://magmaguy.com:50000/";
    private static final UUID pluginRSPUUID = UUID.randomUUID();
    private static BukkitTask keepAlive = null;
    @Getter
    private static String rspUUID = null;
    private static boolean firstUpload = true;

    private AutoHost() {
    }

    public static void sendResourcePack(Player player) {
        if (rspUUID == null) return;
//        player.setResourcePack(pluginRSPUUID, finalURL + "rsp_" + rspUUID, Mix.getFinalSHA1Bytes(), DefaultConfig.getResourcePackPrompt(), DefaultConfig.isForceResourcePack());
        player.setResourcePack(finalURL + "rsp_" + rspUUID, Mix.getFinalSHA1Bytes(), DefaultConfig.getResourcePackPrompt(), DefaultConfig.isForceResourcePack());

    }

    public static void initialize() {
        if (!DefaultConfig.isAutoHost()) return;
        if (Mix.getFinalResourcePack() == null) return;
        firstUpload = true;
        new BukkitRunnable() {
            @Override
            public void run() {
                checkFileExistence();
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
                }.runTaskTimerAsynchronously(ResourcePackManager.plugin, 0, 12 * 60 * 60 * 20L);
            }
        }.runTaskAsynchronously(ResourcePackManager.plugin);
    }

    private static void checkFileExistence() {
        initializeLink();
        if (rspUUID == null) return;
        if (!sendSHA1()) uploadFile();
    }

    public static void initializeLink() {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(finalURL + "initialize");
            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("uuid", DataConfig.getRspUUID(), ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            httpPost.setEntity(builder.build());

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseString = EntityUtils.toString(response.getEntity());
                rspUUID = responseString.trim();
                DataConfig.setRspUUID(rspUUID);
            } catch (Exception e) {
                Logger.warn("Failed to communicate with remote server!");
                e.printStackTrace();
            }
        } catch (Exception e) {
            rspUUID = null;
            Logger.warn("Failed remote server initialization.");
            e.printStackTrace();
        }
    }

    public static void uploadFile() {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost uploadFile = new HttpPost(finalURL + "upload");

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("uuid", rspUUID, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            builder.addBinaryBody("file", Mix.getFinalResourcePack(), ContentType.APPLICATION_OCTET_STREAM, Mix.getFinalResourcePack().getName());

            uploadFile.setEntity(builder.build());

            try (CloseableHttpResponse response = httpClient.execute(uploadFile)) {
            } catch (Exception e) {
                Logger.warn("Failed to communicate with remote server!");
                e.printStackTrace();
                return;
            }
            Logger.info("Uploaded resource pack for automatic hosting!");
            if (firstUpload)
                //Recover from a reload by sending the pack to online players
                for (Player player : Bukkit.getOnlinePlayers())
                    AutoHost.sendResourcePack(player);
            firstUpload = false;
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

            // Convert the entity to a string for logging
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            entity.writeTo(out);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseString = EntityUtils.toString(response.getEntity());
                return Boolean.valueOf(responseString);
            } catch (Exception e) {
                Logger.warn("Failed to communicate with remote server!");
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private static void sendStillAlive() throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(finalURL + "still_alive");

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("uuid", rspUUID);
            httpPost.setEntity(builder.build());

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            } catch (Exception e) {
                Logger.warn("Failed to communicate with remote server!");
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
    }
}
