package com.magmaguy.resourcepackmanager.autohost;

import com.magmaguy.resourcepackmanager.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.FileEntity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class AutoHost {
    private static final String hostURL = "http://localhost:3000/sha1";
    private static final String stillAliveURL = "http://localhost:3000/still_alive";
    private static final String rspURL = "http://localhost:3000/upload";
    private static BukkitTask keepAlive = null;

    private AutoHost() {
    }

    public static void initialize() {
        if (!DefaultConfig.isAutoHost()) return;
        if (Mix.getFinalResourcePack() == null) return;
        keepAlive = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (!sendSHA1(Mix.getFinalSHA1(), hostURL)) {
                        uploadFile(Mix.getFinalResourcePack(), rspURL);
                    }
                    sendStillAlive(stillAliveURL);
                } catch (Exception e) {
                    Logger.warn("Failed to autohost resource pack!");
                    e.printStackTrace();
                }
            }
        }.runTaskTimerAsynchronously(ResourcePackManager.plugin, 0, 3 * 24 * 60 * 60 * 20L);
    }

    public static void uploadFile(File file, String url) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost uploadFile = new HttpPost(url);

            // Use FileEntity for streaming large files
            FileEntity fileEntity = new FileEntity(file, ContentType.APPLICATION_OCTET_STREAM);
            uploadFile.setEntity(fileEntity);

            try (CloseableHttpResponse response = httpClient.execute(uploadFile)) {
                String responseString = EntityUtils.toString(response.getEntity());
                System.out.println("Response from server: " + responseString);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static Boolean sendSHA1(String stringData, String url) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(url);

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("stringData", stringData, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            httpPost.setEntity(builder.build());

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseString = EntityUtils.toString(response.getEntity());
                System.out.println("Response from server: " + responseString);
                return Boolean.parseBoolean(responseString.trim());
            } catch (Exception e) {
                Logger.warn("Failed to communicate with remote server!");
                e.printStackTrace();
                return null;
            }
        }
    }

    private static void sendStillAlive(String url) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(url);

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addTextBody("status", "alive", ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            httpPost.setEntity(builder.build());

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseString = EntityUtils.toString(response.getEntity());
                System.out.println("Response from server: " + responseString);
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
