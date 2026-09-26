package com.experimento.launcher.modloaders;

import com.experimento.launcher.mojang.HttpFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Consulta las APIs oficiales para listar TODAS las versiones disponibles
 * de Forge, Fabric, NeoForge y Quilt para una versión de Minecraft dada.
 */
public final class ModloaderVersionService {

    private static final ObjectMapper M = new ObjectMapper();

    private static final java.util.Map<String, List<String>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private ModloaderVersionService() {}

    /**
     * Lista todas las versiones de Forge para la MC version dada.
     * Devuelve solo la parte "forge" (ej: "47.4.20"), más reciente primero.
     * Ejemplo: getForgeVersions("1.20.1") → ["47.4.20", "47.4.19", ..., "47.0.1"]
     */
    public static List<String> getForgeVersions(String mcVersion) throws Exception {
        String cacheKey = "forge:" + mcVersion;
        if (CACHE.containsKey(cacheKey)) return CACHE.get(cacheKey);

        byte[] bytes = HttpFiles.getBytes(
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml");
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ByteArrayInputStream(bytes));
        List<String> versions = new ArrayList<>();
        String prefix = mcVersion + "-";

        NodeList nodes = doc.getElementsByTagName("version");
        for (int i = 0; i < nodes.getLength(); i++) {
            String ver = nodes.item(i).getTextContent().trim();
            if (ver.startsWith(prefix) && !ver.contains("_mapped_") && !ver.contains("_recomp")) {
                versions.add(ver.substring(prefix.length()));
            }
        }
        Collections.reverse(versions);
        CACHE.put(cacheKey, versions);
        return versions;
    }

    /**
     * Lista todas las versiones del Fabric Loader compatibles con la MC version dada.
     * Ya viene más reciente primero desde la API de FabricMC.
     */
    public static List<String> getFabricLoaderVersions(String mcVersion) throws Exception {
        String cacheKey = "fabric:" + mcVersion;
        if (CACHE.containsKey(cacheKey)) return CACHE.get(cacheKey);

        byte[] bytes = HttpFiles.getBytes(
            "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion);
        JsonNode arr = M.readTree(bytes);
        List<String> versions = new ArrayList<>();
        for (JsonNode entry : arr) {
            String ver = entry.path("loader").path("version").asText(null);
            if (ver != null && !ver.isBlank()) {
                versions.add(ver);
            }
        }
        CACHE.put(cacheKey, versions);
        return versions;
    }

    /**
     * Verifica si Fabric Loader soporta la versión de Minecraft dada.
     * Fabric solo funciona desde Minecraft 1.14 en adelante.
     */
    public static boolean isFabricSupported(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) return false;
        try {
            String clean = mcVersion.split("-")[0];
            String[] parts = clean.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major >= 2 || (major == 1 && minor >= 14);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Lista todas las versiones de NeoForge para la MC version dada.
     * MC "1.21.1" → busca versiones que empiecen con "21.1.", más reciente primero.
     */
    public static List<String> getNeoForgeVersions(String mcVersion) throws Exception {
        String cacheKey = "neoforge:" + mcVersion;
        if (CACHE.containsKey(cacheKey)) return CACHE.get(cacheKey);

        byte[] bytes = HttpFiles.getBytes(
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml");
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ByteArrayInputStream(bytes));
        List<String> versions = new ArrayList<>();

        String mcShort = mcVersion.startsWith("1.") ? mcVersion.substring(2) : mcVersion;
        String prefix = mcShort + ".";

        NodeList nodes = doc.getElementsByTagName("version");
        for (int i = 0; i < nodes.getLength(); i++) {
            String ver = nodes.item(i).getTextContent().trim();
            if (ver.startsWith(prefix)) {
                versions.add(ver);
            }
        }
        Collections.reverse(versions);
        CACHE.put(cacheKey, versions);
        return versions;
    }

    /**
     * Lista todas las versiones del Quilt Loader compatibles con la MC version dada.
     * Quilt soporta Minecraft 1.14+ y es compatible con la mayoría de mods Fabric.
     * Devuelve las versiones más recientes primero.
     */
    public static List<String> getQuiltLoaderVersions(String mcVersion) throws Exception {
        String cacheKey = "quilt:" + mcVersion;
        if (CACHE.containsKey(cacheKey)) return CACHE.get(cacheKey);

        byte[] bytes = HttpFiles.getBytes(
            "https://meta.quiltmc.org/v3/versions/loader/" + mcVersion);
        JsonNode arr = M.readTree(bytes);
        List<String> versions = new ArrayList<>();
        for (JsonNode entry : arr) {
            String ver = entry.path("loader").path("version").asText(null);
            if (ver != null && !ver.isBlank()) {
                versions.add(ver);
            }
        }
        CACHE.put(cacheKey, versions);
        return versions;
    }

    /**
     * Verifica si Quilt Loader soporta la versión de Minecraft dada.
     * Quilt funciona desde Minecraft 1.14 en adelante (igual que Fabric).
     */
    public static boolean isQuiltSupported(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) return false;
        try {
            String clean = mcVersion.split("-")[0];
            String[] parts = clean.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major >= 2 || (major == 1 && minor >= 14);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Obtiene la versión recomendada (stable) de Forge para una MC version.
     * Retorna null si no existe recomendada.
     */
    public static String getForgeRecommended(String mcVersion) {
        try {
            byte[] bytes = HttpFiles.getBytes(
                "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json");
            JsonNode promos = M.readTree(bytes).path("promos");
            String rec = promos.path(mcVersion + "-recommended").asText(null);
            if (rec == null) rec = promos.path(mcVersion + "-latest").asText(null);
            return rec;
        } catch (Exception ignored) {
            return null;
        }
    }
}
