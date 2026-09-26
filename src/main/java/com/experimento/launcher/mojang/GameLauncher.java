package com.experimento.launcher.mojang;

import com.experimento.launcher.LauncherMetadata;
import com.experimento.launcher.util.VersionComparator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class GameLauncher {

    private final Path librariesDir;
    private final Path assetsDir;
    private final Path versionsDir;
    private final OsContext os = OsContext.current();

    public GameLauncher(Path librariesDir, Path assetsDir, Path versionsDir) {
        this.librariesDir = librariesDir;
        this.assetsDir = assetsDir;
        this.versionsDir = versionsDir;
    }

    public List<String> buildCommand(
            JsonNode mergedVersion,
            String versionId,
            Path gameDir,
            String username,
            String uuidString,
            List<String> extraJvmArgs,
            String customJavaPath,
            LaunchFeatures features)
            throws Exception {

        Path versionRoot = versionsDir.resolve(versionId);
        Path clientJar = versionRoot.resolve(versionId + ".jar");
        Path nativesDir = versionRoot.resolve("natives");

        List<Path> classpath = buildClasspath(mergedVersion, clientJar);

        String cpSep = File.pathSeparator;
        String classpathStr =
                classpath.stream().map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining(cpSep));
        int requiredJava = getRequiredJavaVersion(mergedVersion, versionId);
        boolean useArgFile = os.isWindows() && requiredJava != 8 && classpathStr.length() > 3500;

        if (!useArgFile) {
            // Windows workaround for command line length limit (8191 chars) - Legacy Java 8 manifest JAR fallback
            if (os.isWindows() && classpathStr.length() > 3500) {
                try {
                    Path tmpDir = versionsDir.resolve("tmp");
                    Files.createDirectories(tmpDir);
                    Path cpJar = Files.createTempFile(tmpDir, "meacore-classpath-", ".jar");
                    cpJar.toFile().deleteOnExit();
                    java.util.jar.Manifest mf = new java.util.jar.Manifest();
                    mf.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
                    String cpEntries = classpath.stream()
                            .map(p -> p.toAbsolutePath().toUri().toString())
                            .collect(java.util.stream.Collectors.joining(" "));
                    mf.getMainAttributes().put(java.util.jar.Attributes.Name.CLASS_PATH, cpEntries);
                    try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                            new java.io.BufferedOutputStream(Files.newOutputStream(cpJar)), mf)) {
                        // empty jar with just the manifest
                    }
                    classpathStr = cpJar.toAbsolutePath().toString();
                } catch (Exception e) {
                    // fallback — if it fails, use the long classpath (will likely fail too)
                }
            }
        }

        JsonNode assetIndex = mergedVersion.get("assetIndex");
        String assetIndexName = assetIndex.get("id").asText();

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", username);
        vars.put("version_name", versionId);
        vars.put("game_directory", gameDir.toAbsolutePath().toString());
        vars.put("assets_root", assetsDir.toAbsolutePath().toString());
        vars.put("assets_index_name", assetIndexName);
        vars.put("auth_uuid", uuidString.replace("-", ""));
        vars.put("auth_access_token", UUID.randomUUID().toString().replace("-", ""));
        vars.put("clientid", "0");
        vars.put("auth_xuid", "0");
        vars.put("user_type", "mojang");
        vars.put("version_type", mergedVersion.path("type").asText("release"));
        vars.put("natives_directory", nativesDir.toAbsolutePath().toString());
        vars.put("launcher_name", LauncherMetadata.TECHNICAL_ID);
        vars.put("launcher_version", LauncherMetadata.VERSION);
        vars.put("user_properties", "{}");
        vars.put("classpath", classpathStr);
        vars.put("classpath_separator", cpSep);
        vars.put("library_directory", librariesDir.toAbsolutePath().toString());
        vars.put("game_assets", assetsDir.toAbsolutePath().toString());
        vars.put("resolution_width", String.valueOf(features.resolutionWidth));
        vars.put("resolution_height", String.valueOf(features.resolutionHeight));
        vars.put("quickPlayPath", "");
        vars.put("quickPlaySingleplayer", "");
        vars.put("quickPlayMultiplayer", "");
        vars.put("quickPlayRealms", "");

        Path logFile = resolveLogConfig(mergedVersion, versionRoot);
        if (logFile != null) {
            vars.put("logging_config_path", logFile.toAbsolutePath().toString());
        }

        List<String> jvm = new ArrayList<>();
        jvm.add(customJavaPath != null && !customJavaPath.isBlank() ? customJavaPath : resolveJavaBinary());
        if (extraJvmArgs != null) {
            for (String a : extraJvmArgs) {
                if (a != null && !a.isBlank()) {
                    jvm.add(a);
                }
            }
        }
        JsonNode args = mergedVersion.get("arguments");
        if (args != null && args.has("jvm")) {
            for (String a : ArgumentFlattener.flatten(args, "jvm", os, features)) {
                jvm.add(substitute(a, vars));
            }
        } else {
            jvm.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
            jvm.add("-cp");
            jvm.add(classpathStr);
        }

        String mainClass = mergedVersion.get("mainClass").asText("net.minecraft.client.main.Main");
        jvm.add(mainClass);

        List<String> game = new ArrayList<>();
        if (args != null && args.has("game")) {
            for (String a : ArgumentFlattener.flatten(args, "game", os, features)) {
                game.add(substitute(a, vars));
            }
        } else {
            String legacy = mergedVersion.get("minecraftArguments").asText();
            for (String part : splitMinecraftArguments(legacy)) {
                game.add(substitute(part, vars));
            }
        }

        if (useArgFile) {
            try {
                Path tmpDir = versionsDir.resolve("tmp");
                Files.createDirectories(tmpDir);
                Path argFile = Files.createTempFile(tmpDir, "meacore-jvm-", ".args");
                argFile.toFile().deleteOnExit();

                List<String> lines = new ArrayList<>();
                // All JVM arguments are from index 1 to jvm.size() - 2 (mainClass is at jvm.size() - 1)
                for (int i = 1; i < jvm.size() - 1; i++) {
                    lines.add(formatArg(jvm.get(i)));
                }
                Files.write(argFile, lines, java.nio.charset.StandardCharsets.UTF_8);

                String javaExe = jvm.get(0);
                String gameMainClass = jvm.get(jvm.size() - 1);
                jvm.clear();
                jvm.add(javaExe);
                jvm.add("@" + argFile.toAbsolutePath().toString().replace('\\', '/'));
                jvm.add(gameMainClass);
            } catch (Exception e) {
                // fallback
            }
        }

        List<String> full = new ArrayList<>(jvm);
        full.addAll(game);
        return full;
    }

    private static Path resolveLogConfig(JsonNode merged, Path versionRoot) throws Exception {
        JsonNode file = merged.path("logging").path("client").path("file");
        if (file.isMissingNode() || !file.has("id")) {
            return null;
        }
        Path p = versionRoot.resolve(file.get("id").asText());
        return Files.exists(p) ? p : null;
    }

    public static String resolveJavaBinary() {
        String home = System.getProperty("java.home");
        if (home != null) {
            Path bin = Path.of(home, "bin", "java");
            if (Files.exists(bin)) {
                return bin.toString();
            }
            Path binExe = Path.of(home, "bin", "java.exe");
            if (Files.exists(binExe)) {
                return binExe.toString();
            }
        }
        return "java";
    }

    private List<Path> buildClasspath(JsonNode merged, Path clientJar) throws Exception {
        List<Path> cp = new ArrayList<>();
        java.util.Map<String, Path> artifacts = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> artifactVersions = new java.util.HashMap<>();

        if (merged.has("libraries")) {
            for (JsonNode lib : merged.get("libraries")) {
                if (!RuleEvaluator.libraryAllowed(lib, os)) {
                    continue;
                }
                
                Path libPath = null;
                JsonNode downloads = lib.get("downloads");
                
                if (downloads != null && downloads.has("artifact")) {
                    libPath = librariesDir.resolve(downloads.get("artifact").get("path").asText());
                } else if (lib.has("name")) {
                    // Fallback: Resolver por nombre Maven (necesario para Forge 1.12.2 y librerías viejas)
                    libPath = librariesDir.resolve(nameToPath(lib.get("name").asText()));
                }

                if (libPath != null && Files.exists(libPath)) {
                    if (lib.has("name")) {
                        String name = lib.get("name").asText();
                        String[] parts = name.split(":");
                        if (parts.length >= 3) {
                            String classifier = parts.length > 3 ? ":" + parts[3] : "";
                            String ga = parts[0] + ":" + parts[1] + classifier;
                            String version = parts[2];
                            
                            String currentVersion = artifactVersions.get(ga);
                            // Conservar la versión más alta para evitar clases duplicadas (ej. org.ow2.asm:asm)
                            if (currentVersion == null || com.experimento.launcher.util.VersionComparator.compare(version, currentVersion) > 0) {
                                artifactVersions.put(ga, version);
                                artifacts.put(ga, libPath);
                            }
                            continue;
                        }
                    }
                    if (!cp.contains(libPath)) {
                        cp.add(libPath);
                    }
                }
            }
        }
        cp.addAll(artifacts.values());
        cp.add(clientJar);
        return cp;
    }

    /** Convierte un nombre Maven (g:a:v) en una ruta de archivo. */
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

    private static String substitute(String s, Map<String, String> vars) {
        String out = s;
        for (var e : vars.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        if (out.contains("${")) {
            // Best-effort: leave remaining tokens empty to avoid broken launches on unknown placeholders
            out = out.replaceAll("\\$\\{[^}]+\\}", "");
        }
        return out;
    }

    private static List<String> splitMinecraftArguments(String raw) {
        List<String> parts = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return parts;
        }
        StringBuilder cur = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                quote = !quote;
            } else if (!quote && Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            parts.add(cur.toString());
        }
        return parts;
    }

    private static String formatArg(String arg) {
        String clean = arg.replace('\\', '/');
        if (clean.contains(" ") || clean.contains("\"")) {
            return "\"" + clean.replace("\"", "\\\"") + "\"";
        }
        return clean;
    }

    private int getRequiredJavaVersion(JsonNode merged, String versionId) {
        if (merged.has("javaVersion")) {
            int major = merged.get("javaVersion").path("majorVersion").asInt(0);
            if (major > 0) return major;
        }
        String mainClass = merged.path("mainClass").asText("").toLowerCase();
        if (mainClass.contains("launchwrapper") || mainClass.contains("net.minecraft.launchwrapper.launch") ||
            VersionComparator.isVersionAtLeast(versionId, 1, 12, 2) &&
            !VersionComparator.isVersionAtLeast(versionId, 1, 13, 0)) {
            return 8;
        }
        // 1.20.5+ requiere Java 21
        if (VersionComparator.isVersionAtLeast(versionId, 1, 20, 5)) {
            return 21;
        }
        // 1.17-1.20.4 requiere Java 17
        if (VersionComparator.isVersionAtLeast(versionId, 1, 17, 0) &&
            !VersionComparator.isVersionAtLeast(versionId, 1, 20, 5)) {
            return 17;
        }
        return 0;
    }

    private static String determineUserType(JsonNode mergedVersion, String versionId) {
        String id1 = versionId != null ? versionId.toLowerCase() : "";
        String id2 = (mergedVersion != null && mergedVersion.has("id")) 
                ? mergedVersion.get("id").asText().toLowerCase() 
                : "";
                
        boolean isAtLeast116 = id1.matches(".*1\\.1[6-9].*")
                || id1.matches(".*1\\.2\\d.*")
                || id1.matches(".*1\\.[3-9]\\d.*")
                || id1.matches(".*[2-9]\\d+.*")
                || id2.matches(".*1\\.1[6-9].*")
                || id2.matches(".*1\\.2\\d.*")
                || id2.matches(".*1\\.[3-9]\\d.*")
                || id2.matches(".*[2-9]\\d+.*");
                
        return isAtLeast116 ? "msa" : "legacy";
    }
}
