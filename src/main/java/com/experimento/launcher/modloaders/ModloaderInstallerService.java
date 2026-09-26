package com.experimento.launcher.modloaders;

import com.experimento.launcher.mojang.HttpFiles;
import com.experimento.launcher.service.JavaRuntimeService;
import com.experimento.launcher.util.VersionComparator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.function.Consumer;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

public class ModloaderInstallerService {
    private static final ObjectMapper M = new ObjectMapper();

    /**
     * Instala la última versión estable de Forge para la MC version dada.
     */
    public static void installForge(String mcVersion, Path launcherDir, Consumer<String> logger) throws Exception {
        installForge(mcVersion, launcherDir, logger, null);
    }

    public static void installForge(String mcVersion, Path launcherDir, Consumer<String> logger, JavaRuntimeService runtime) throws Exception {
        logger.accept("[Forge] Resolviendo última versión de Forge para " + mcVersion + "...");
        byte[] promoBytes = HttpFiles.getBytes("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json");
        JsonNode promosObj = M.readTree(promoBytes).path("promos");
        String forgeLatest = promosObj.path(mcVersion + "-recommended").asText(null);
        if (forgeLatest == null) forgeLatest = promosObj.path(mcVersion + "-latest").asText(null);
        if (forgeLatest == null)
            throw new Exception("No se encontró Forge para " + mcVersion + ". Prueba NeoForge para 1.20.2+.");
        installForgeSpecific(mcVersion, forgeLatest, launcherDir, logger, runtime);
    }

    /**
     * Instala una versión ESPECÍFICA de Forge.
     * forgeVersion: solo la parte forge, ej: "47.4.20" (no "1.20.1-47.4.20")
     */
    public static void installForgeSpecific(String mcVersion, String forgeVersion,
            Path launcherDir, Consumer<String> logger, JavaRuntimeService runtime) throws Exception {
        String fullVersion = mcVersion + "-" + forgeVersion;
        logger.accept("[Forge] Instalando versión: " + fullVersion);
        String jarUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                + fullVersion + "/forge-" + fullVersion + "-installer.jar";
        Path tempInstaller = Files.createTempFile("forge-installer-", ".jar");
        logger.accept("[Forge] Descargando instalador...");
        HttpFiles.downloadIfHashMismatch(jarUrl, tempInstaller, null);
        Path fakeProfile = launcherDir.resolve("launcher_profiles.json");
        if (!Files.exists(fakeProfile)) Files.writeString(fakeProfile, "{ \"profiles\": {} }");
        int requiredJava = resolveJavaVersionForMinecraft(mcVersion);
        String javaExe = resolveJavaExecutable(runtime, requiredJava);
        logger.accept("[Forge] Usando Java " + requiredJava + " · Inyectando Forge " + forgeVersion + "...");
        ProcessBuilder pb = new ProcessBuilder(javaExe, "-jar",
                tempInstaller.toAbsolutePath().toString(),
                "--installClient", launcherDir.toAbsolutePath().toString());
        pb.directory(tempInstaller.getParent().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (Scanner s = new Scanner(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8.name())) {
            while (s.hasNextLine()) {
                String line = s.nextLine();
                if (line.contains("Downloading") || line.contains("Extracting")
                        || line.contains("Successfully") || line.contains("Installing")) {
                    logger.accept("[Forge-Bot] " + line);
                } else if (line.toLowerCase().contains("error")
                        || line.toLowerCase().contains("exception") || line.contains("Failed")) {
                    logger.accept("[Forge-Error] " + line);
                }
            }
        }
        int exitCode = p.waitFor();
        Files.deleteIfExists(tempInstaller);
        if (exitCode != 0)
            throw new Exception("Forge " + forgeVersion + " falló (código " + exitCode
                    + "). Instala primero la versión vanilla.");
        logger.accept("[Forge] ✅ Forge " + forgeVersion + " instalado correctamente.");
    }

    /**
     * Instala NeoForge (sucesor moderno de Forge para 1.20.2+).
     * Es el modloader recomendado para modpacks modernos como ATM9, Sky Odyssey, etc.
     */
    public static void installNeoForge(String mcVersion, Path launcherDir, Consumer<String> logger) throws Exception {
        installNeoForge(mcVersion, launcherDir, logger, null);
    }

    public static void installNeoForge(String mcVersion, Path launcherDir, Consumer<String> logger, JavaRuntimeService runtime) throws Exception {
        logger.accept("[NeoForge] Resolviendo última versión de NeoForge para Minecraft " + mcVersion + "...");
        String mcVersionShort = mcVersion.startsWith("1.") ? mcVersion.substring(2) : mcVersion;
        byte[] mavenMeta;
        try {
            mavenMeta = HttpFiles.getBytes(
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml");
        } catch (Exception e) {
            throw new Exception("No se pudo acceder a los servidores de NeoForge. Verifica tu conexión a internet.");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document doc = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(mavenMeta));
        NodeList versionNodes = doc.getDocumentElement().getElementsByTagName("version");
        List<String> versions = new ArrayList<>();
        for (int i = 0; i < versionNodes.getLength(); i++) {
            String v = versionNodes.item(i).getTextContent().trim();
            if (v.startsWith(mcVersionShort + ".") || v.startsWith(mcVersionShort + "-")) {
                versions.add(v);
            }
        }
        versions.sort((a, b) -> {
            try {
                String[] pa = a.split("\\.");
                String[] pb2 = b.split("\\.");
                for (int i = 0; i < Math.min(pa.length, pb2.length); i++) {
                    int na = Integer.parseInt(pa[i].replaceAll("[^0-9]", "").replaceAll("^$", "0"));
                    int nb = Integer.parseInt(pb2[i].replaceAll("[^0-9]", "").replaceAll("^$", "0"));
                    if (na != nb) return Integer.compare(nb, na);
                }
                return Integer.compare(pb2.length, pa.length);
            } catch (Exception e) { return 0; }
        });
        String latestVersion = versions.isEmpty() ? null : versions.get(0);
        if (latestVersion == null)
            throw new Exception("No se encontró NeoForge para Minecraft " + mcVersion
                    + ". NeoForge soporta 1.20.2+. Para versiones anteriores usa Forge.");
        installNeoForgeSpecific(mcVersion, latestVersion, launcherDir, logger, runtime);
    }

    /**
     * Instala una versión ESPECÍFICA de NeoForge.
     * neoforgeVersion: versión completa, ej: "21.1.88" (para Minecraft 1.21.1)
     */
    public static void installNeoForgeSpecific(String mcVersion, String neoforgeVersion,
            Path launcherDir, Consumer<String> logger, JavaRuntimeService runtime) throws Exception {
        logger.accept("[NeoForge] Instalando NeoForge " + neoforgeVersion + " para Minecraft " + mcVersion + "...");
        String installerUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                + neoforgeVersion + "/neoforge-" + neoforgeVersion + "-installer.jar";
        Path tempInstaller = Files.createTempFile("neoforge-installer-", ".jar");
        logger.accept("[NeoForge] Descargando instalador...");
        HttpFiles.downloadIfHashMismatch(installerUrl, tempInstaller, null);
        Path fakeProfile = launcherDir.resolve("launcher_profiles.json");
        if (!Files.exists(fakeProfile)) Files.writeString(fakeProfile, "{ \"profiles\": {} }");
        int requiredJava = resolveJavaVersionForMinecraft(mcVersion);
        String javaExe = resolveJavaExecutable(runtime, requiredJava);
        logger.accept("[NeoForge] Usando Java " + requiredJava + " · Inyectando NeoForge " + neoforgeVersion + "...");
        ProcessBuilder pb = new ProcessBuilder(javaExe, "-jar",
                tempInstaller.toAbsolutePath().toString(),
                "--installClient", launcherDir.toAbsolutePath().toString());
        pb.directory(tempInstaller.getParent().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (Scanner s = new Scanner(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8.name())) {
            while (s.hasNextLine()) logger.accept("[NeoForge-Bot] " + s.nextLine());
        }
        int exitCode = p.waitFor();
        Files.deleteIfExists(tempInstaller);
        if (exitCode != 0)
            throw new Exception("NeoForge " + neoforgeVersion + " falló (código " + exitCode
                    + "). Instala primero la versión vanilla.");
        logger.accept("[NeoForge] ✅ NeoForge " + neoforgeVersion + " instalado. Recarga la lista de versiones.");
    }

    /**
     * Instala la última versión del Fabric Loader para la MC version dada.
     */
    public static void installFabric(String mcVersion, Path launcherDir, Consumer<String> logger) throws Exception {
        installFabric(mcVersion, launcherDir, logger, null);
    }

    public static void installFabric(String mcVersion, Path launcherDir,
            Consumer<String> logger, JavaRuntimeService runtime) throws Exception {
        installFabricSpecific(mcVersion, null, launcherDir, logger, runtime);
    }

    /**
     * Instala una versión ESPECÍFICA del Fabric Loader.
     * loaderVersion: ej "0.16.9" — si es null usa la última disponible.
     */
    public static void installFabricSpecific(String mcVersion, String loaderVersion,
            Path launcherDir, Consumer<String> logger, JavaRuntimeService runtime) throws Exception {
        logger.accept("[Fabric] Resolviendo instalador Fabric...");
        String installerVersion = resolveLatestFabricInstaller(logger);
        String jarUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/"
                + installerVersion + "/fabric-installer-" + installerVersion + ".jar";
        Path tempInstaller = Files.createTempFile("fabric-installer-", ".jar");
        logger.accept("[Fabric] Descargando instalador " + installerVersion + "...");
        HttpFiles.downloadIfHashMismatch(jarUrl, tempInstaller, null);
        int requiredJava = resolveJavaVersionForMinecraft(mcVersion);
        String javaExe = resolveJavaExecutable(runtime, requiredJava);
        String loaderInfo = (loaderVersion != null && !loaderVersion.isBlank()) ? loaderVersion : "latest";
        logger.accept("[Fabric] Instalando Fabric Loader " + loaderInfo + " para Minecraft " + mcVersion + "...");
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                javaExe, "-jar", tempInstaller.toAbsolutePath().toString(),
                "client", "-mcversion", mcVersion,
                "-dir", launcherDir.toAbsolutePath().toString(),
                "-noprofile"));
        if (loaderVersion != null && !loaderVersion.isBlank()) {
            cmd.add("-loader");
            cmd.add(loaderVersion);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (Scanner s = new Scanner(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8.name())) {
            while (s.hasNextLine()) logger.accept("[Fabric-Bot] " + s.nextLine());
        }
        int exitCode = p.waitFor();
        Files.deleteIfExists(tempInstaller);
        if (exitCode != 0)
            throw new Exception("Fabric " + loaderInfo + " falló. Instala primero la versión vanilla.");
        logger.accept("[Fabric] ✅ Fabric Loader " + loaderInfo + " instalado correctamente.");
    }

    /**
     * Instala la última versión del Quilt Loader para la MC version dada.
     * Quilt es compatible con la mayoría de mods de Fabric.
     */
    public static void installQuilt(String mcVersion, Path launcherDir, Consumer<String> logger) throws Exception {
        installQuilt(mcVersion, launcherDir, logger, null);
    }

    public static void installQuilt(String mcVersion, Path launcherDir,
            Consumer<String> logger, JavaRuntimeService runtime) throws Exception {
        installQuiltSpecific(mcVersion, null, launcherDir, logger, runtime);
    }

    /**
     * Instala una versión ESPECÍFICA del Quilt Loader.
     * loaderVersion: ej "0.27.1" — si es null usa la última disponible.
     */
    public static void installQuiltSpecific(String mcVersion, String loaderVersion,
            Path launcherDir, Consumer<String> logger, JavaRuntimeService runtime) throws Exception {
        logger.accept("[Quilt] Descargando instalador universal de Quilt...");
        // Quilt ofrece un instalador JAR universal compatible con Java 17+
        String installerUrl = "https://quiltmc.org/api/v1/download-latest-installer/java-universal";
        Path tempInstaller = Files.createTempFile("quilt-installer-", ".jar");
        HttpFiles.downloadIfHashMismatch(installerUrl, tempInstaller, null);

        int requiredJava = Math.max(resolveJavaVersionForMinecraft(mcVersion), 17); // Quilt requiere Java 17+
        String javaExe = resolveJavaExecutable(runtime, requiredJava);

        String loaderInfo = (loaderVersion != null && !loaderVersion.isBlank()) ? loaderVersion : "latest";
        logger.accept("[Quilt] Instalando Quilt Loader " + loaderInfo + " para Minecraft " + mcVersion + "...");

        // Comando del instalador Quilt: install client <mcVersion> [<loaderVersion>] --install-dir <dir> --no-profile
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                javaExe, "-jar", tempInstaller.toAbsolutePath().toString(),
                "install", "client", mcVersion));
        if (loaderVersion != null && !loaderVersion.isBlank()) {
            cmd.add(loaderVersion);
        }
        cmd.addAll(java.util.List.of(
                "--install-dir=" + launcherDir.toAbsolutePath().toString(),
                "--no-profile"));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (Scanner s = new Scanner(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8.name())) {
            while (s.hasNextLine()) {
                String line = s.nextLine();
                if (line.toLowerCase().contains("error") || line.toLowerCase().contains("exception")) {
                    logger.accept("[Quilt-Error] " + line);
                } else if (!line.isBlank()) {
                    logger.accept("[Quilt-Bot] " + line);
                }
            }
        }
        int exitCode = p.waitFor();
        Files.deleteIfExists(tempInstaller);
        if (exitCode != 0)
            throw new Exception("Quilt " + loaderInfo + " falló (código " + exitCode
                    + "). Instala primero la versión vanilla.");
        logger.accept("[Quilt] ✅ Quilt Loader " + loaderInfo + " instalado. Compatible con mods de Fabric.");
    }

    private static String resolveLatestFabricInstaller(Consumer<String> logger) {
        try {
            byte[] meta = HttpFiles.getBytes(
                "https://maven.fabricmc.net/net/fabricmc/fabric-installer/maven-metadata.xml");
            String metaStr = new String(meta);
            String[] lines = metaStr.split("\n");
            String latest = null;
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("<latest>")) {
                    latest = t.replace("<latest>", "").replace("</latest>", "").trim();
                    break;
                }
                if (t.startsWith("<version>")) {
                    latest = t.replace("<version>", "").replace("</version>", "").trim();
                }
            }
            if (latest != null) {
                logger.accept("[Fabric] Instalador más reciente: " + latest);
                return latest;
            }
        } catch (Exception e) {
            logger.accept("[Fabric] No se pudo resolver versión dinámica, usando 1.0.1 como fallback.");
        }
        return "1.0.1";
    }

    /**
     * Determina la versión de Java requerida según la versión de Minecraft.
     * - Minecraft 1.8-1.16 → Java 8
     * - Minecraft 1.17-1.20.4 → Java 17
     * - Minecraft 1.20.5+ → Java 21
     */
    private static int resolveJavaVersionForMinecraft(String mcVersion) {
        // 1.20.5+ requiere Java 21
        if (VersionComparator.isVersionAtLeast(mcVersion, 1, 20, 5)) {
            return 21;
        }
        // 1.17-1.20.4 requiere Java 17
        if (VersionComparator.isVersionAtLeast(mcVersion, 1, 17, 0) &&
            !VersionComparator.isVersionAtLeast(mcVersion, 1, 20, 5)) {
            return 17;
        }
        // 1.16.5 y anteriores usan Java 8
        return 8;
    }

    private static String resolveJavaExecutable(JavaRuntimeService runtime, int preferredVersion) {
        if (runtime != null) {
            java.nio.file.Path exe = runtime.getExecutable(preferredVersion);
            if (exe != null) return exe.toAbsolutePath().toString();
            // Fallbacks: si no hay Java 21, usar 17. Si no hay 8, intentar 17.
            if (preferredVersion == 21) {
                exe = runtime.getExecutable(17);
                if (exe != null) return exe.toAbsolutePath().toString();
            } else if (preferredVersion == 8) {
                exe = runtime.getExecutable(17);
                if (exe != null) {
                    System.out.println("[Java] Advertencia: Java 8 no encontrado, usando Java 17 como fallback");
                    return exe.toAbsolutePath().toString();
                }
            }
        }
        String home = System.getProperty("java.home");
        if (home != null) {
            java.nio.file.Path bin = java.nio.file.Path.of(home, "bin", "java");
            if (java.nio.file.Files.exists(bin)) return bin.toString();
        }
        return "java";
    }
}
