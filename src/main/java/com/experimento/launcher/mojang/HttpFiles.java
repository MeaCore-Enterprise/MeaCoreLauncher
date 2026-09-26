package com.experimento.launcher.mojang;

import com.experimento.launcher.util.DownloadConstants;
import com.experimento.launcher.util.Hashing;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.function.Consumer;

public final class HttpFiles {

    private static final HttpClient HTTP =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

    private static final int MAX_RETRIES = 3;

    private HttpFiles() {}

    public static byte[] getBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " for " + url);
        }
        return res.body();
    }

    public static void downloadIfHashMismatch(String url, Path dest, String expectedSha1) throws Exception {
        downloadIfHashMismatch(url, dest, expectedSha1, null);
    }

    public static void downloadIfHashMismatch(String url, Path dest, String expectedSha1, Consumer<Double> onProgress) throws Exception {
        Files.createDirectories(dest.getParent());
        if (Files.exists(dest) && expectedSha1 != null && !expectedSha1.isBlank()) {
            try (InputStream in = Files.newInputStream(dest)) {
                String got = Hashing.sha1Hex(in);
                if (expectedSha1.equalsIgnoreCase(got)) {
                    if (onProgress != null) onProgress.accept(1.0);
                    return;
                }
            }
        }

        downloadWithRetry(url, dest, expectedSha1, onProgress);
    }

    private static void downloadWithRetry(String url, Path dest, String expectedSha1, Consumer<Double> onProgress) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                downloadWithProgress(url, dest, expectedSha1, onProgress);
                return;
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    long waitMs = (long) Math.pow(2, attempt - 1) * 1000;
                    Thread.sleep(Math.min(waitMs, 10000));
                }
            }
        }
        throw lastException;
    }

    private static void downloadWithProgress(String url, Path dest, String expectedSha1, Consumer<Double> onProgress) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " for " + url);
        }

        long contentLength = res.headers().firstValueAsLong("content-length").orElse(-1L);

        try (InputStream in = new BufferedInputStream(res.body(), DownloadConstants.BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), DownloadConstants.BUFFER_SIZE)) {

            byte[] buffer = new byte[DownloadConstants.BUFFER_SIZE];
            long totalRead = 0;
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                if (onProgress != null) {
                    if (contentLength > 0) {
                        onProgress.accept((double) totalRead / contentLength);
                    } else {
                        onProgress.accept(-1.0);
                    }
                }
            }
            out.flush();
        }

        if (onProgress != null) {
            onProgress.accept(1.0);
        }

        if (expectedSha1 != null && !expectedSha1.isBlank()) {
            try (InputStream in = Files.newInputStream(dest)) {
                String got = Hashing.sha1Hex(in);
                if (!expectedSha1.equalsIgnoreCase(got)) {
                    Files.deleteIfExists(dest);
                    throw new IllegalStateException("SHA1 mismatch for " + url + " expected " + expectedSha1 + " got " + got);
                }
            }
        }
    }
}

