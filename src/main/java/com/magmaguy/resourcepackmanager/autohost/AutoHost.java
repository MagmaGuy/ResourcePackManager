package com.magmaguy.resourcepackmanager.autohost;

import com.magmaguy.resourcepackmanager.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
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

    private static void uploadFile(File file, String urlString) throws IOException {
        String boundary = Long.toHexString(System.currentTimeMillis());
        String CRLF = "\r\n";
        HttpURLConnection connection = createConnection(urlString, boundary);

        try (
                OutputStream output = connection.getOutputStream();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true)
        ) {
            // Send file part
            writer.append("--" + boundary).append(CRLF);
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"").append(CRLF);
            writer.append("Content-Type: " + HttpURLConnection.guessContentTypeFromName(file.getName())).append(CRLF);
            writer.append("Content-Transfer-Encoding: binary").append(CRLF);
            writer.append(CRLF).flush();

            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[1024];
                for (int length; (length = input.read(buffer)) > 0; ) {
                    output.write(buffer, 0, length);
                }
                output.flush();
            }

            writer.append(CRLF).flush();
            writer.append("--" + boundary + "--").append(CRLF).flush(); // Closing boundary
        }

        String response = getResponse(connection);
    }


    private static Boolean sendSHA1(String stringData, String urlString) throws IOException {
        String boundary = Long.toHexString(System.currentTimeMillis());
        String CRLF = "\r\n";
        HttpURLConnection connection = createConnection(urlString, boundary);

        try (
                OutputStream output = connection.getOutputStream();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true)
        ) {
            // Send string data part
            writer.append("--" + boundary).append(CRLF);
            writer.append("Content-Disposition: form-data; name=\"stringData\"").append(CRLF);
            writer.append("Content-Type: text/plain; charset=UTF-8").append(CRLF);
            writer.append(CRLF).append(stringData).append(CRLF).flush();

            // End of multipart/form-data
            writer.append("--" + boundary + "--").append(CRLF).flush();
        }

        String response = getResponse(connection);
        if (response != null) return Boolean.parseBoolean(response.trim());
        return null;
    }

    private static void sendStillAlive(String urlString) throws IOException {
        String boundary = Long.toHexString(System.currentTimeMillis());
        HttpURLConnection connection = createConnection(urlString, boundary);
        String response = getResponse(connection);
    }

    private static HttpURLConnection createConnection(String urlString, String boundary) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        return connection;
    }

    private static String getResponse(HttpURLConnection connection) throws IOException {
        // Get response from server
        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String responseLine;
                StringBuilder response = new StringBuilder();

                while ((responseLine = in.readLine()) != null) {
                    response.append(responseLine);
                }

                System.out.println("Response from server: " + response);
                return response.toString();

            } catch (Exception e) {
                Logger.warn("Failed to communicate with remote server!");
                e.printStackTrace();
            }
        } else {
            System.out.println("Server returned non-OK status: " + responseCode);
        }
        return null;
    }

    public static void shutdown() {
        if (keepAlive != null) keepAlive.cancel();
    }
}
