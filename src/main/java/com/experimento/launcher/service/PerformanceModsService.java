package com.experimento.launcher.service;

import com.experimento.launcher.mojang.HttpFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Descarga e instala automáticamente los mods de rendimiento más potentes
 * para Fabric, Quilt, Forge y NeoForge de forma paralela y optimizada.
 * 
 * Todos los mods son 100% de código abierto (FOSS) y libres de costo.
 */
public final class PerformanceModsService {

    private static final String MODRINTH_API = "https://api.modrinth.com/v2";
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private PerformanceModsService() {}

    public record PerformanceMod(String slug, String name, String description) {}
    public record ModDownloadInfo(String url, String sha1) {}

    /** Fabric 1.14+ */
    public static final List<PerformanceMod> FABRIC_MODS = List.of(
        new PerformanceMod("sodium",          "Sodium",          "Motor de renderizado moderno — +50-300% FPS"),
        new PerformanceMod("lithium",         "Lithium",         "Optimización de lógica del juego y servidor"),
        new PerformanceMod("ferrite-core",    "FerriteCore",     "Reducción masiva de uso de RAM (-30%)"),
        new PerformanceMod("indium",          "Indium",          "Compatibilidad de Sodium con Fabric Rendering API"),
        new PerformanceMod("immediatelyfast", "ImmediatelyFast", "Renderizado de entidades y UI ultra-rápido"),
        new PerformanceMod("krypton",         "Krypton",         "Optimización del stack de red y reducción de ping"),
        new PerformanceMod("starlight",       "Starlight",       "Motor de iluminación reescrito (MC ≤1.19)"),
        new PerformanceMod("entityculling",   "EntityCulling",   "Oculta entidades detrás de paredes — +20-50% FPS")
    );

    /** Quilt 1.14+ — compatible con mods Fabric */
    public static final List<PerformanceMod> QUILT_MODS = List.of(
        new PerformanceMod("qsl",             "Quilted Fabric API", "API base necesaria para compatibilidad de Quilt"),
        new PerformanceMod("sodium",          "Sodium",             "Motor de renderizado moderno — +50-300% FPS"),
        new PerformanceMod("lithium",         "Lithium",            "Optimización de lógica del juego y servidor"),
        new PerformanceMod("ferrite-core",    "FerriteCore",        "Reducción masiva de uso de RAM (-30%)"),
        new PerformanceMod("immediatelyfast", "ImmediatelyFast",    "Renderizado de entidades y UI ultra-rápido"),
        new PerformanceMod("krypton",         "Krypton",            "Optimización del stack de red y reducción de ping"),
        new PerformanceMod("entityculling",   "EntityCulling",      "Oculta entidades detrás de paredes — +20-50% FPS")
    );

    /** Forge 1.12.2–1.21+ */
    public static final List<PerformanceMod> FORGE_MODS = List.of(
        new PerformanceMod("ferrite-core", "FerriteCore", "Reducción masiva de uso de RAM (-30%)"),
        new PerformanceMod("embeddium",    "Embeddium",   "Motor de renderizado para Forge — +50-200% FPS"),
        new PerformanceMod("modernfix",    "ModernFix",   "Tiempos de carga -50%, RAM -20%, FPS +10%"),
        new PerformanceMod("ksyxis",       "Ksyxis",      "Elimina pantalla de carga de mundo innecesaria"),
        new PerformanceMod("oculus",       "Oculus",      "Soporte de Shaders para Forge compatible con Embeddium")
    );

    /** NeoForge 1.20.2+ — usa Embeddium */
    public static final List<PerformanceMod> NEOFORGE_MODS = List.of(
        new PerformanceMod("ferrite-core", "FerriteCore", "Reducción masiva de uso de RAM (-30%)"),
        new PerformanceMod("embeddium",    "Embeddium",   "Motor de renderizado para NeoForge/Forge — +50-200% FPS"),
        new PerformanceMod("modernfix",    "ModernFix",   "Tiempos de carga -50%, RAM -20%, FPS +10%"),
        new PerformanceMod("ksyxis",       "Ksyxis",      "Elimina pantalla de carga de mundo innecesaria"),
        new PerformanceMod("oculus",       "Oculus",      "Soporte de Shaders para NeoForge compatible con Embeddium")
    );

    /**
     * Descarga e instala en paralelo los mods de rendimiento en la carpeta de mods.
     * Solo instala los que sean compatibles con la versión dada.
     */
    public static void installPerformanceMods(
            Path modsDir,
            String mcVersion,
            String loader,
            Consumer<String> log) throws Exception {

        Files.createDirectories(modsDir);

        String loaderLow = loader != null ? loader.toLowerCase() : "vanilla";
        List<PerformanceMod> mods;
        if (loaderLow.contains("neoforge")) {
            mods = NEOFORGE_MODS;
        } else if (loaderLow.contains("quilt")) {
            mods = QUILT_MODS;
        } else if (loaderLow.contains("fabric")) {
            mods = FABRIC_MODS;
        } else if (loaderLow.contains("forge")) {
            mods = FORGE_MODS;
        } else {
            log.accept("[PERF] Loader '" + loader + "' no soportado. Instala Fabric, Quilt, Forge o NeoForge primero.");
            return;
        }

        log.accept("[PERF] Iniciando optimización con mods de rendimiento para " + mcVersion + " (" + loader + ")...");
        log.accept("[PERF] Mods a verificar: " + mods.stream().map(PerformanceMod::name).collect(Collectors.joining(", ")));

        AtomicInteger installed = new AtomicInteger(0);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (PerformanceMod mod : mods) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        ModDownloadInfo info = resolveDownloadUrl(mod.slug(), mcVersion, loaderLow, log);
                        if (info == null) {
                            log.accept("[PERF] ℹ " + mod.name() + " no requerido o no disponible para " + mcVersion + ", omitiendo.");
                            return;
                        }

                        String fileName = mod.slug() + "-" + mcVersion + "-" + loaderLow + ".jar";
                        Path dest = modsDir.resolve(fileName);

                        if (Files.exists(dest)) {
                            if (info.sha1() != null) {
                                try (InputStream fis = Files.newInputStream(dest)) {
                                    String currentHash = com.experimento.launcher.util.Hashing.sha1Hex(fis);
                                    if (info.sha1().equalsIgnoreCase(currentHash)) {
                                        log.accept("[PERF] ✓ " + mod.name() + " ya está instalado y al día.");
                                        installed.incrementAndGet();
                                        return;
                                    } else {
                                        log.accept("[PERF] 🔄 Actualizando " + mod.name() + "...");
                                    }
                                } catch (Exception ignored) {}
                            } else {
                                log.accept("[PERF] ✓ " + mod.name() + " ya está instalado.");
                                installed.incrementAndGet();
                                return;
                            }
                        }

                        log.accept("[PERF] Descargando " + mod.name() + "...");
                        HttpFiles.downloadIfHashMismatch(info.url(), dest, info.sha1());
                        log.accept("[PERF] ✅ " + mod.name() + " instalado — " + mod.description());
                        installed.incrementAndGet();

                    } catch (Exception e) {
                        log.accept("[PERF] ⚠ No se pudo instalar " + mod.name() + ": " + e.getMessage());
                    }
                }, pool));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }

        log.accept("[PERF] ═══════════════════════════════════════════════");
        log.accept("[PERF] Optimización lista: " + installed.get() + "/" + mods.size() + " mods de rendimiento activos.");
        if (installed.get() > 0) {
            log.accept("[PERF] Los mods se aplicarán automáticamente al lanzar el juego.");
            log.accept("[PERF] Rendimiento estimado: +50-200% FPS, menor consumo de RAM y tiempos de carga reducidos.");
        }
    }

    private static ModDownloadInfo resolveDownloadUrl(String slug, String mcVersion, String loader, Consumer<String> log) {
        ModDownloadInfo info = queryModrinth(slug, mcVersion, loader);
        if (info == null && loader.equalsIgnoreCase("quilt")) {
            // Quilt puede usar mods etiquetados como Fabric
            info = queryModrinth(slug, mcVersion, "fabric");
        }
        return info;
    }

    private static ModDownloadInfo queryModrinth(String slug, String mcVersion, String loader) {
        try {
            String searchUrl = MODRINTH_API + "/project/" + slug + "/version?game_versions=" +
                    URLEncoder.encode("[\"" + mcVersion + "\"]", StandardCharsets.UTF_8) + "&loaders=" +
                    URLEncoder.encode("[\"" + loader + "\"]", StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .header("User-Agent", "MeaCore-Launcher/performance-mods-installer")
                    .GET()
                    .build();

            HttpResponse<InputStream> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) return null;

            JsonNode root = M.readTree(res.body());
            if (!root.isArray() || root.isEmpty()) return null;

            JsonNode firstVersion = root.get(0);
            JsonNode files = firstVersion.path("files");
            if (!files.isArray() || files.isEmpty()) return null;

            JsonNode targetFile = null;
            for (JsonNode file : files) {
                if (file.path("primary").asBoolean(false)) {
                    targetFile = file;
                    break;
                }
            }
            if (targetFile == null) {
                targetFile = files.get(0);
            }

            String url = targetFile.path("url").asText(null);
            String sha1 = targetFile.path("hashes").path("sha1").asText(null);

            if (url == null) return null;
            return new ModDownloadInfo(url, sha1);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verifica si la combinación de loader y versión es compatible con los mods de rendimiento.
     */
    public static boolean isSupported(String loader) {
        if (loader == null) return false;
        String l = loader.toLowerCase();
        return l.contains("fabric") || l.contains("quilt") || l.contains("forge") || l.contains("neoforge");
    }
}
