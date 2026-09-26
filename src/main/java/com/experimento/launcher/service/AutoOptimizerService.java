package com.experimento.launcher.service;

import com.experimento.launcher.model.JvmPresetKind;
import com.experimento.launcher.model.LauncherProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Escribe claves de rendimiento en options.txt antes de cada lanzamiento.
 * Compatible con Minecraft 1.8 → 1.21+. Solo sobreescribe las claves gestionadas,
 * preservando el resto de la configuración del jugador.
 */
public final class AutoOptimizerService {

    private AutoOptimizerService() {}

    public static void applyOptionsTxt(Path gameDir, LauncherProfile profile, long totalRamMiB) throws Exception {
        Files.createDirectories(gameDir);
        Path opt = gameDir.resolve("options.txt");
        Map<String, String> existing = new LinkedHashMap<>();
        if (Files.isRegularFile(opt)) {
            for (String line : Files.readAllLines(opt)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int i = line.indexOf(':');
                if (i > 0) {
                    existing.put(line.substring(0, i), line.substring(i + 1));
                }
            }
        }
        JvmPresetKind kind =
                profile.jvmPreset == JvmPresetKind.AUTO
                        ? JvmPresetService.resolveAutoKind(totalRamMiB)
                        : profile.jvmPreset;
        Map<String, String> patch =
                switch (kind) {
                    case LOW            -> lowOptions();
                    case BALANCED, AUTO -> balancedOptions();
                    case HIGH           -> highOptions();
                    case ULTRA          -> ultraOptions();
                };
        existing.putAll(patch);
        String body =
                existing.entrySet().stream()
                        .map(e -> e.getKey() + ":" + e.getValue())
                        .collect(Collectors.joining("\n"))
                        + "\n";
        Files.writeString(opt, body);
    }

    private static Map<String, String> lowOptions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("renderDistance", "5");
        m.put("simulationDistance", "5");
        m.put("graphicsMode", "1");          // Fast
        m.put("ao", "0");                    // Ambient Occlusion OFF
        m.put("particles", "2");             // Minimal
        m.put("entityDistanceMul", "0.5");
        m.put("maxFps", "60");
        m.put("mipmapLevels", "2");
        m.put("biomeBlendRadius", "1");
        m.put("glDebugVerbosity", "0");
        m.put("useNativeTransport", "true");
        m.put("skipMultiplayerWarning", "true");
        m.put("reducedDebugInfo", "false");
        m.put("enableVsync", "false");
        m.put("renderClouds", "false");      // Sin nubes en LOW
        m.put("narrator", "0");             // Narrador OFF (consume CPU)
        m.put("showSubtitles", "false");
        m.put("fancyGraphics", "false");
        m.put("smoothLighting", "minimum");
        return m;
    }

    private static Map<String, String> balancedOptions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("renderDistance", "10");
        m.put("simulationDistance", "8");
        m.put("graphicsMode", "0");          // Fast (no Fancy)
        m.put("ao", "1");
        m.put("particles", "0");             // All
        m.put("entityDistanceMul", "0.75");
        m.put("maxFps", "120");
        m.put("mipmapLevels", "4");
        m.put("biomeBlendRadius", "3");
        m.put("glDebugVerbosity", "0");
        m.put("useNativeTransport", "true");
        m.put("skipMultiplayerWarning", "true");
        m.put("enableVsync", "false");
        m.put("renderClouds", "false");      // Sin nubes — +5 FPS aproximado
        m.put("narrator", "0");
        m.put("showSubtitles", "false");
        return m;
    }

    private static Map<String, String> highOptions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("renderDistance", "16");
        m.put("simulationDistance", "12");
        m.put("graphicsMode", "0");          // Fast para mantener FPS
        m.put("ao", "2");
        m.put("particles", "0");
        m.put("entityDistanceMul", "1.0");
        m.put("maxFps", "260");
        m.put("mipmapLevels", "4");
        m.put("biomeBlendRadius", "5");
        m.put("glDebugVerbosity", "0");
        m.put("useNativeTransport", "true");
        m.put("skipMultiplayerWarning", "true");
        m.put("enableVsync", "false");
        m.put("narrator", "0");
        m.put("showSubtitles", "false");
        return m;
    }

    /**
     * Preset ULTRA: Calidad máxima + FPS sin límite.
     * Diseñado para GPUs dedicadas con suficiente VRAM (≥6 GB).
     */
    private static Map<String, String> ultraOptions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("renderDistance", "24");       // Máximo visión en Minecraft vanilla
        m.put("simulationDistance", "16");   // Full tick distance
        m.put("graphicsMode", "2");          // Fancy — todo activado
        m.put("ao", "2");                    // Ambient Occlusion máximo
        m.put("particles", "0");             // All particles
        m.put("entityDistanceMul", "1.0");   // 100% distancia de entidades
        m.put("maxFps", "0");               // Sin límite de FPS — dejar que la GPU vuele
        m.put("mipmapLevels", "4");          // Máximo calidad de texturas lejanas
        m.put("biomeBlendRadius", "7");      // Máximo suavizado de biomas
        m.put("glDebugVerbosity", "0");
        m.put("useNativeTransport", "true");
        m.put("skipMultiplayerWarning", "true");
        m.put("enableVsync", "false");       // VSync OFF — sin cap artificial
        m.put("narrator", "0");
        m.put("showSubtitles", "false");
        m.put("renderClouds", "fancy");      // Nubes volumétricas en ULTRA
        m.put("fancyGraphics", "true");      // Hojas transparentes, agua mejorada
        m.put("smoothLighting", "maximum");  // Iluminación suave máxima
        return m;
    }

    public static String modSuggestionText(JvmPresetKind effective) {
        return switch (effective) {
            case LOW, AUTO -> "Rendimiento (FOSS): Fabric + Sodium + Lithium + Starlight + FerriteCore + EntityCulling.";
            case BALANCED  -> "Opcional: Fabric + Sodium + Starlight para más FPS sin sacrificar calidad.";
            case HIGH      -> "Recomendado: Sodium + ImmediatelyFast + Krypton si usas Fabric.";
            case ULTRA     -> "ULTRA: Usa Sodium + ImmediatelyFast. Si usas shaders, agrega Iris (Fabric) u Oculus (Forge).";
        };
    }
}
