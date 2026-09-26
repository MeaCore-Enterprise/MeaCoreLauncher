package com.experimento.launcher.mojang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GameFilesInstaller {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String RESOURCE_HOST = "https://resources.download.minecraft.net/";
    private static final int DOWNLOAD_THREADS = 16;

    private final Path librariesDir;
    private final Path assetsDir;
    private final Path versionsDir;
    private final OsContext os = OsContext.current();

    public GameFilesInstaller(Path librariesDir, Path assetsDir, Path versionsDir) {
        this.librariesDir = librariesDir;
        this.assetsDir = assetsDir;
        this.versionsDir = versionsDir;
    }

    public JsonNode installVersion(String versionId, MojangVersionResolver resolver, ProgressSink progress)
            throws Exception {
        progress.log("Resolviendo versión " + versionId + "…");
        JsonNode merged = resolver.resolveMerged(versionId);
        Path versionRoot = versionsDir.resolve(versionId);
        Files.createDirectories(versionRoot);

        JsonNode client = merged.get("downloads").get("client");
        Path clientJar = versionRoot.resolve(versionId + ".jar");
        if (!Files.exists(clientJar)) {
            progress.log("⬇ Descargando cliente JAR (" + (client.get("size").asLong() / (1024 * 1024)) + " MB)…");
            HttpFiles.downloadIfHashMismatch(
                    client.get("url").asText(), clientJar, client.get("sha1").asText());
            progress.log("✓ Cliente JAR descargado");
        } else {
            progress.log("✓ Cliente JAR ya presente");
        }

        progress.log("⬇ Descargando bibliotecas en paralelo…");
        Path nativesDir = versionRoot.resolve("natives");
        Files.createDirectories(nativesDir);

        List<JsonNode> libraries = new ArrayList<>();
        for (JsonNode lib : merged.get("libraries")) {
            if (RuleEvaluator.libraryAllowed(lib, os)) {
                libraries.add(lib);
            }
        }

        List<Future<?>> libFutures = new ArrayList<>();
        try (ExecutorService libPool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (JsonNode lib : libraries) {
                libFutures.add(libPool.submit(() -> {
                    try {
                        downloadLibrary(lib, nativesDir, progress);
                    } catch (Exception e) {
                        progress.log("[WARN] Librería omitida: " + e.getMessage());
                    }
                    return null;
                }));
            }
            for (Future<?> f : libFutures) {
                f.get();
            }
        }
        progress.log("✓ Librerías completadas (" + libraries.size() + " procesadas)");

        JsonNode assetIndex = merged.get("assetIndex");
        Path indexesDir = assetsDir.resolve("indexes");
        Files.createDirectories(indexesDir);
        Path indexFile = indexesDir.resolve(assetIndex.get("id").asText() + ".json");
        Path cleanMarker = indexesDir.resolve(assetIndex.get("id").asText() + ".json.cleaned");

        if (!Files.exists(cleanMarker)) {
            progress.log("⬇ Descargando índice de assets…");
            HttpFiles.downloadIfHashMismatch(
                    assetIndex.get("url").asText(), indexFile, assetIndex.get("sha1").asText());
            progress.log("✓ Índice descargado");

            JsonNode indexJsonNode = M.readTree(Files.readAllBytes(indexFile));
            JsonNode objectsNode = indexJsonNode.get("objects");

            if (objectsNode instanceof com.fasterxml.jackson.databind.node.ObjectNode objNode) {
                List<String> toRemove = new ArrayList<>();
                objNode.fieldNames().forEachRemaining(key -> {
                    if (key.contains("/lang/") || key.startsWith("minecraft/lang/") || key.startsWith("realms/lang/")) {
                        // Deep Clean MeaCore: solo en_us (fallback obligatorio) +
                        // variantes español latinoamérica/españa solicitadas.
                        boolean keep = key.contains("/en_us")   // Inglés (fallback Minecraft)
                                   || key.contains("/es_ar")    // Español argentino
                                   || key.contains("/es_cl")    // Español chileno
                                   || key.contains("/es_es")    // Español (España)
                                   || key.contains("/es_mx")    // Español mexicano
                                   || key.contains("/es_uy")    // Español uruguayo
                                   || key.contains("/es_ve");   // Español venezolano
                        if (!keep) toRemove.add(key);
                    }
                });
                toRemove.forEach(objNode::remove);
                if (!toRemove.isEmpty()) {
                    progress.log("🧹 Deep Clean: Ocultados " + toRemove.size() + " idiomas");
                    try {
                        Files.writeString(indexFile, M.writeValueAsString(indexJsonNode));
                        Files.writeString(cleanMarker, "cleaned");
                    } catch (Exception e) {
                        progress.log("[WARN] No se pudo guardar el índice de assets filtrado: " + e.getMessage());
                    }
                } else {
                    try {
                        Files.writeString(cleanMarker, "cleaned");
                    } catch (Exception ignored) {}
                }
            }
        } else {
            progress.log("✓ Índice de assets ya presente y optimizado");
        }

        JsonNode indexJson = M.readTree(Files.readAllBytes(indexFile));
        JsonNode objects = indexJson.get("objects");

        int total = objects.size();
        AtomicInteger done = new AtomicInteger();
        progress.log("📦 Sincronizando assets (" + total + " objetos)…");

        Path objectsDir = assetsDir.resolve("objects");
        Files.createDirectories(objectsDir);
        List<Future<?>> assetFutures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            objects.fields().forEachRemaining(e -> {
                JsonNode h = e.getValue();
                String hash = h.get("hash").asText();
                String prefix = hash.substring(0, 2);
                Path dest = objectsDir.resolve(prefix).resolve(hash);
                assetFutures.add(pool.submit(() -> {
                    try {
                        if (Files.exists(dest)) {
                            try (InputStream in = Files.newInputStream(dest)) {
                                String got = com.experimento.launcher.util.Hashing.sha1Hex(in);
                                if (hash.equalsIgnoreCase(got)) {
                                    int c = done.incrementAndGet();
                                    if (c % 500 == 0 || c == total) progress.log("  🔄 Assets: " + c + "/" + total);
                                    return;
                                }
                            }
                        }
                        String url = RESOURCE_HOST + prefix + "/" + hash;
                        HttpFiles.downloadIfHashMismatch(url, dest, hash);
                        int c = done.incrementAndGet();
                        if (c % 500 == 0 || c == total) progress.log("  🔄 Assets: " + c + "/" + total);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }));
            });
            for (Future<?> f : assetFutures) {
                f.get();
            }
        } catch (Exception e) {
            progress.log("[ERROR] Error en descarga de assets: " + e.getMessage());
        }
        progress.log("✓ Assets completados");

        progress.log("📝 Descargando configuración de logging…");
        JsonNode logging = merged.path("logging").path("client").path("file");
        if (!logging.isMissingNode() && logging.has("url")) {
            Path logCfg = versionRoot.resolve(logging.get("id").asText());
            HttpFiles.downloadIfHashMismatch(
                    logging.get("url").asText(), logCfg, logging.path("sha1").asText(null));
            progress.log("✓ Config de logging descargada");
        }

        progress.log("✅ Instalación de " + versionId + " COMPLETADA.");
        return merged;
    }

    private void downloadLibrary(JsonNode lib, Path nativesDir, ProgressSink progress) throws Exception {
        String libName = lib.has("name") ? lib.get("name").asText() : "unknown";
        JsonNode downloads = lib.get("downloads");
        boolean downloaded = false;

        if (downloads != null && downloads.has("artifact")) {
            JsonNode art = downloads.get("artifact");
            Path dest = librariesDir.resolve(art.get("path").asText());
            String fileName = dest.getFileName().toString();
            if (!Files.exists(dest)) {
                progress.log("⬇ Descargando librería: " + fileName);
                HttpFiles.downloadIfHashMismatch(art.get("url").asText(), dest, art.get("sha1").asText());
            }
            downloaded = true;
        }

        if (!downloaded && lib.has("name")) {
            String nameToDownload = lib.get("name").asText();
            boolean isLegacyNative = lib.has("natives");
            if (isLegacyNative) {
                JsonNode nativesObj = lib.get("natives");
                String osName = os.name().toLowerCase();
                String key = osName.contains("win") ? "windows" : (osName.contains("mac") ? "osx" : "linux");
                if (nativesObj.has(key)) {
                    String classifier = nativesObj.get(key).asText().replace("${arch}", os.arch().contains("64") ? "64" : "32");
                    nameToDownload += ":" + classifier;
                }
            }
            
            downloadMavenLibrary(nameToDownload);
            
            if (isLegacyNative) {
                Path zipPath = librariesDir.resolve(nameToPath(nameToDownload));
                if (Files.exists(zipPath)) {
                    synchronized (nativesDir) {
                        progress.log("📦 Extrayendo nativo legacy: " + libName);
                        extractNatives(zipPath, nativesDir);
                    }
                }
            }
        }

        if (downloads != null && downloads.has("classifiers")) {
            JsonNode cls = downloads.get("classifiers");
            String key = pickNativeClassifier(cls);
            if (key != null && cls.has(key)) {
                JsonNode nat = cls.get(key);
                Path zipPath = librariesDir.resolve(nat.get("path").asText());
                if (!Files.exists(zipPath)) {
                    progress.log("⬇ Descargando nativo: " + libName);
                    HttpFiles.downloadIfHashMismatch(nat.get("url").asText(), zipPath, nat.get("sha1").asText());
                }
                synchronized (nativesDir) {
                    progress.log("📦 Extrayendo nativo: " + libName);
                    extractNatives(zipPath, nativesDir);
                }
            }
        }
    }

    private void downloadMavenLibrary(String name) {
        String path = nameToPath(name);
        Path dest = librariesDir.resolve(path);
        if (Files.exists(dest)) return;

        String[] mirrors = {
            "https://libraries.minecraft.net/",
            "https://maven.minecraftforge.net/",
            "https://maven.neoforged.net/releases/",
            "https://repo1.maven.org/maven2/"
        };

        for (String mirror : mirrors) {
            try {
                HttpFiles.downloadIfHashMismatch(mirror + path, dest, null);
                if (Files.exists(dest)) return;
            } catch (Exception e) {
                System.err.println("[INSTALL] Mirror failed: " + mirror + " - " + e.getMessage());
            }
        }
    }

    private static String nameToPath(String name) {
        String[] parts = name.split(":");
        if (parts.length < 3) return name.replace(":", "_") + ".jar";
        String group = parts[0].replace(".", "/");
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? parts[3] : null;
        String filename = artifact + "-" + version + (classifier != null ? "-" + classifier : "") + ".jar";
        return group + "/" + artifact + "/" + version + "/" + filename;
    }

    private String pickNativeClassifier(JsonNode classifiers) {
        String primary = os.nativeClassifier();
        if (classifiers.has(primary)) return primary;
        if ("osx".equals(os.name()) && classifiers.has("natives-macos")) return "natives-macos";
        return null;
    }

    private static void extractNatives(Path zipFile, Path nativesDir) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.endsWith(".gitignore") || name.endsWith(".sha1")) continue;
                if (name.contains("META-INF")) continue;
                if (name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib")) {
                    Path out = nativesDir.resolve(name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name);
                    Files.createDirectories(out.getParent());
                    try (BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(out))) {
                        zin.transferTo(bos);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    public interface ProgressSink {
        void log(String line);
    }
}
