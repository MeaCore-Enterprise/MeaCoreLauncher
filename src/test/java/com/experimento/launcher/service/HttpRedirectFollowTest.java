package com.experimento.launcher.service;

import com.experimento.launcher.mojang.HttpFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresión del 302: GitHub redirige la API ({@code /releases/latest} -> tag) y el CDN de
 * {@code releases/download} antes de servir el binario. Si el cliente HTTP del check no sigue
 * las redirecciones, el usuario ve "No se pudo comprobar si hay actualizaciones (código 302)".
 *
 * <p>Un servidor falso responde 302 -> 200 en los tres tramos: manifest (override), API y
 * descarga del instalador. Además se comprueba que seguir la redirección no debilita la
 * verificación de integridad ni deja un {@code .part} huérfano.</p>
 */
class HttpRedirectFollowTest {

    private static final String PROPERTY = "meacore.update.manifest";
    private static final byte[] PAYLOAD = "MeaCore-Installer-Despues-De-La-Redireccion".getBytes();

    private final String originalUserHome = System.getProperty("user.home");
    private final String previousProperty = System.getProperty(PROPERTY);

    private String realSha256;
    private String wrongSha256;

    @TempDir
    Path tmpHome;

    @BeforeEach
    void setUp() throws Exception {
        realSha256  = "sha256:" + sha256Hex(PAYLOAD);
        wrongSha256 = "sha256:" + sha256Hex("Otro binario tras una redirección".getBytes());
        System.setProperty("user.home", tmpHome.toString());
        System.clearProperty(PROPERTY);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalUserHome);
        if (previousProperty == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, previousProperty);
        }
    }

    // ── Manifest (override) ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Override: el 302 del CDN se sigue y se ofrece la actualización")
    void manifestOverrideRedirectIsFollowed() throws Exception {
        var hits = new CopyOnWriteArrayList<String>();
        String body = manifestBody();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest.json", exchange -> {
            hits.add("/latest.json");
            exchange.getResponseHeaders().add("Location", "/real.json");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/real.json", exchange -> {
            hits.add("/real.json");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        server.start();
        try {
            System.setProperty(PROPERTY,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/latest.json");

            var done = new CountDownLatch(1);
            var offered = new AtomicReference<String>();
            var errors = new CopyOnWriteArrayList<String>();
            AutoUpdateService.setListener(new AutoUpdateService.UpdateListener() {
                @Override public void onUpdateFound(String v, String u) { offered.set(v); }
                @Override public void onDownloadProgress(double f) {}
                @Override public void onDownloadComplete(Path p) {}
                @Override public void onDownloadError(String m) { errors.add(m); }
                @Override public void onCheckError(String m) { errors.add(m); }
                @Override public void onCheckFinished() { done.countDown(); }
            });

            AutoUpdateService.checkForUpdatesAsync(true);
            assertTrue(done.await(20, TimeUnit.SECONDS), "el check no terminó");

            assertEquals("99.0.0", offered.get(), "se debe ofrecer la versión del manifest final");
            assertTrue(errors.isEmpty(), "errores: " + errors);
            assertEquals(List.of("/latest.json", "/real.json"), hits,
                    "el check debe seguir la redirección y leer el manifest final");
        } finally {
            AutoUpdateService.setListener(null);
            server.stop(0);
        }
    }

    // ── API (y mirror: mismo cliente) ───────────────────────────────────────────────

    @Test
    @DisplayName("API: fetchLatestRelease sigue el 302 y lee el JSON final")
    void apiRedirectIsFollowed() throws Exception {
        var hits = new CopyOnWriteArrayList<String>();
        String body = manifestBody();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases/latest", exchange -> {
            hits.add("/releases/latest");
            exchange.getResponseHeaders().add("Location", "/releases/tags/99.0.0");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/releases/tags/99.0.0", exchange -> {
            hits.add("/releases/tags/99.0.0");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        server.start();
        try {
            Method fetch = AutoUpdateService.class.getDeclaredMethod(
                    "fetchLatestRelease", HttpClient.class, String.class);
            fetch.setAccessible(true);

            // newCheckClient() es exactamente el cliente que usa el check para API, mirror y
            // override: si alguien le quita followRedirects, aquí falla.
            Object res = fetch.invoke(null, AutoUpdateService.newCheckClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/releases/latest");

            int status = (int) res.getClass().getMethod("status").invoke(res);
            JsonNode bodyNode = (JsonNode) res.getClass().getMethod("body").invoke(res);

            assertEquals(200, status, "debe seguir el 302 y llegar al 200 final");
            assertNotNull(bodyNode, "el cuerpo del 200 final debe parsearse");
            assertEquals("99.0.0", bodyNode.path("tag_name").asText());
            assertEquals(List.of("/releases/latest", "/releases/tags/99.0.0"), hits);
        } finally {
            server.stop(0);
        }
    }

    // ── Descarga del instalador ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Descarga: el 302 del CDN se sigue, la integridad se verifica y no queda .part")
    void downloadRedirectIsFollowedAndVerified() throws Exception {
        var server = redirectingServer();
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/installer.deb";
            Recorder rec = new Recorder();
            AutoUpdateService.setListener(rec);
            AutoUpdateService.downloadAndInstallAsync(url, realSha256, (long) PAYLOAD.length);

            assertTrue(rec.awaitTerminal(15_000), "no llegó ningún evento terminal");
            assertTrue(rec.completed.get(), "con digest correcto debe completar");
            assertTrue(rec.errors.isEmpty(), "errores: " + rec.errors);

            Path out = rec.downloaded.get();
            assertNotNull(out);
            assertTrue(Arrays.equals(PAYLOAD, Files.readAllBytes(out)),
                    "el contenido debe ser el del 200 final, el que el CDN sirvió tras el 302");
            assertFalse(Files.exists(HttpFiles.partPathFor(out)),
                    "no debe quedar .part tras una descarga correcta");
        } finally {
            AutoUpdateService.setListener(null);
            server.stop(0);
            awaitDownloadGuardFree();
        }
    }

    @Test
    @DisplayName("Descarga: 302 -> 200 con digest erróneo se rechaza y no debilita la integridad")
    void downloadRedirectDoesNotWeakenIntegrity() throws Exception {
        var server = redirectingServer();
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/installer.deb";
            Recorder rec = new Recorder();
            AutoUpdateService.setListener(rec);
            AutoUpdateService.downloadAndInstallAsync(url, wrongSha256, (long) PAYLOAD.length);

            assertTrue(rec.awaitTerminal(15_000), "no llegó ningún evento terminal");
            assertFalse(rec.completed.get(), "un digest erróneo no se instala, ni tras redirección");
            assertEquals(1, rec.errors.size(), "debe informar del error: " + rec.errors);
            assertTrue(rec.errors.getFirst().contains("corrupto"),
                    "mensaje de archivo corrupto: " + rec.errors.getFirst());

            Path updatesDir = Path.of(tmpHome.toString(), ".cache", "meacore", "updates");
            try (var files = Files.list(updatesDir)) {
                List<Path> leftovers = files.toList();
                for (Path p : leftovers) {
                    String n = p.getFileName().toString();
                    assertFalse(n.endsWith(".deb"), "no debe quedar instalador: " + leftovers);
                    assertFalse(n.endsWith(".part"), "no debe quedar .part: " + leftovers);
                }
            }
        } finally {
            AutoUpdateService.setListener(null);
            server.stop(0);
            awaitDownloadGuardFree();
        }
    }

    // ── Utilidades ──────────────────────────────────────────────────────────────────

    /** /installer.deb -> 302 -> /payload.deb (200 con PAYLOAD). */
    private HttpServer redirectingServer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/installer.deb", exchange -> {
            exchange.getResponseHeaders().add("Location", "/payload.deb");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/payload.deb", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, PAYLOAD.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(PAYLOAD);
            }
        });
        return server;
    }

    private static String manifestBody() {
        String digest = "sha256:" + "0".repeat(64);
        return """
                {"tag_name":"99.0.0","assets":[
                  {"name":"meacore-launcher-99.0.0.exe",
                   "browser_download_url":"http://127.0.0.1/instalador.exe",
                   "size":10,"digest":"%s"},
                  {"name":"meacore-launcher_99.0.0_amd64.deb",
                   "browser_download_url":"http://127.0.0.1/instalador.deb",
                   "size":10,"digest":"%s"}]}
                """.formatted(digest, digest);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static void awaitDownloadGuardFree() throws Exception {
        Field f = AutoUpdateService.class.getDeclaredField("downloading");
        f.setAccessible(true);
        var guard = (AtomicBoolean) f.get(null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (guard.get() && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(guard.get(), "la guarda de descarga quedó bloqueada");
    }

    private static final class Recorder implements AutoUpdateService.UpdateListener {
        final CountDownLatch terminal = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<Path> downloaded = new AtomicReference<>();
        final List<String> errors = new CopyOnWriteArrayList<>();

        boolean awaitTerminal(long ms) throws InterruptedException {
            return terminal.await(ms, TimeUnit.MILLISECONDS);
        }

        @Override public void onUpdateFound(String version, String url) {}
        @Override public void onDownloadProgress(double f) {}
        @Override public void onDownloadComplete(Path installer) {
            downloaded.set(installer);
            completed.set(true);
            terminal.countDown();
        }
        @Override public void onDownloadError(String message) {
            errors.add(message);
            terminal.countDown();
        }
        @Override public void onUnverifiedDownload(Path installer) { terminal.countDown(); }
        @Override public void onCheckFinished() {}
    }
}