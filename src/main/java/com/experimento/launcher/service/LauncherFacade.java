package com.experimento.launcher.service;

import com.experimento.launcher.mojang.GameFilesInstaller;
import com.experimento.launcher.mojang.GameLauncher;
import com.experimento.launcher.mojang.LaunchFeatures;
import com.experimento.launcher.mojang.ManifestVersionEntry;
import com.experimento.launcher.mojang.MojangVersionResolver;
import com.experimento.launcher.mojang.OsContext;
import com.experimento.launcher.model.LauncherProfile;
import com.experimento.launcher.paths.LauncherDirectories;
import com.experimento.launcher.servers.ServersDatService;
import com.experimento.launcher.util.OfflineUuid;
import com.experimento.launcher.util.VersionComparator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class LauncherFacade {

    private final LauncherDirectories dirs;
    private final ProfileStore profiles;
    private final ObjectMapper mapper = new ObjectMapper();
    private final JavaRuntimeService runtimeService;

    public LauncherFacade(LauncherDirectories dirs) {
        this.dirs = dirs;
        this.profiles = new ProfileStore(dirs.launcherData());
        this.runtimeService = new JavaRuntimeService(dirs.launcherData());
    }

    public LauncherDirectories directories() {
        return dirs;
    }

    public ProfileStore profiles() {
        return profiles;
    }

    public JavaRuntimeService runtime() {
        return runtimeService;
    }

    public Path gameDirFor(LauncherProfile p) {
        if (p.useGlobalMinecraftFolder) {
            if (com.experimento.launcher.mojang.OsContext.current().isWindows()) {
                String appdata = System.getenv("APPDATA");
                if (appdata != null) return Path.of(appdata, ".minecraft");
            }
            return Path.of(System.getProperty("user.home"), ".minecraft");
        }
        return dirs.instanceGameDir(p.instanceId);
    }

    public void installVersion(String versionId, Consumer<String> log) throws Exception {
        dirs.ensureBaseDirs();
        var resolver = new MojangVersionResolver(dirs.versionsDir());
        var installer =
                new GameFilesInstaller(dirs.librariesDir(), dirs.assetsDir(), dirs.versionsDir());
        installer.installVersion(versionId, resolver, log::accept);
    }

    public List<ManifestVersionEntry> fetchManifestVersions() throws Exception {
        dirs.ensureBaseDirs();
        var resolver = new MojangVersionResolver(dirs.versionsDir());
        JsonNode manifest = resolver.loadManifest();
        List<ManifestVersionEntry> mojangVersions = MojangVersionResolver.versionEntriesFromManifest(manifest);
        
        List<ManifestVersionEntry> allVersions = new java.util.ArrayList<>();
        if (Files.isDirectory(dirs.versionsDir())) {
            try (var stream = Files.list(dirs.versionsDir())) {
                stream.filter(Files::isDirectory).forEach(p -> {
                    String id = p.getFileName().toString();
                    if (Files.isRegularFile(p.resolve(id + ".json"))) {
                        if (mojangVersions.stream().noneMatch(v -> v.id().equals(id))) {
                            String type = "híbrido/custom";
                            try {
                                JsonNode localVer = mapper.readTree(p.resolve(id + ".json").toFile());
                                if (localVer.has("inheritsFrom")) {
                                    String inherits = localVer.get("inheritsFrom").asText().toLowerCase();
                                    if (id.toLowerCase().contains("forge") || inherits.contains("forge")) type = "forge";
                                    else if (id.toLowerCase().contains("fabric") || inherits.contains("fabric")) type = "fabric";
                                }
                            } catch (Exception ignored) {}
                            allVersions.add(new ManifestVersionEntry(id, type));
                        }
                    }
                });
            } catch (Exception ignored) {}
        }
        
        // Orden alfabético simple para las versiones custom al principio
        allVersions.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        allVersions.addAll(mojangVersions);
        
        return allVersions;
    }

    public void prepareInstance(LauncherProfile p, long ramMiB, Consumer<String> log) throws Exception {
        dirs.ensureBaseDirs();
        Path gameDir = gameDirFor(p);
        AutoOptimizerService.applyOptionsTxt(gameDir, p, ramMiB);
        ServersDatService.writeServers(gameDir, p.servers);
        cleanLanguageInterface(gameDir, log);

        // Dynamic Language Index Filter on Launch!
        try {
            String versionId = p.lastVersionId;
            if (versionId != null) {
                Path versionJson = dirs.versionsDir().resolve(versionId).resolve("version.json");
                if (Files.exists(versionJson)) {
                    JsonNode merged = mapper.readTree(Files.readAllBytes(versionJson));
                    JsonNode assetIndex = merged.get("assetIndex");
                    if (assetIndex != null && assetIndex.has("id")) {
                        String indexId = assetIndex.get("id").asText();
                        Path indexFile = dirs.assetsDir().resolve("indexes").resolve(indexId + ".json");
                        if (Files.exists(indexFile)) {
                            JsonNode indexJson = mapper.readTree(Files.readAllBytes(indexFile));
                            JsonNode objects = indexJson.get("objects");
                            if (objects instanceof com.fasterxml.jackson.databind.node.ObjectNode objNode) {
                                boolean indexModified = false;
                                java.util.List<String> toRemove = new java.util.ArrayList<>();
                                objNode.fieldNames().forEachRemaining(key -> {
                                    if (key.contains("/lang/") || key.startsWith("minecraft/lang/") || key.startsWith("realms/lang/")) {
                                        boolean keep = key.contains("/en_us")
                                                   || key.contains("/es_ar")
                                                   || key.contains("/es_cl")
                                                   || key.contains("/es_es")
                                                   || key.contains("/es_mx")
                                                   || key.contains("/es_uy")
                                                   || key.contains("/es_ve");
                                        if (!keep) toRemove.add(key);
                                    }
                                });
                                if (!toRemove.isEmpty()) {
                                    toRemove.forEach(objNode::remove);
                                    indexModified = true;
                                }

                                // Filter pack.mcmeta inside objects
                                JsonNode packMcMetaNode = objNode.get("pack.mcmeta");
                                if (packMcMetaNode != null && packMcMetaNode.has("hash")) {
                                    String origHash = packMcMetaNode.get("hash").asText();
                                    String prefix = origHash.substring(0, 2);
                                    Path origFile = dirs.assetsDir().resolve("objects").resolve(prefix).resolve(origHash);
                                    if (Files.exists(origFile)) {
                                        JsonNode origMetaJson = mapper.readTree(Files.readAllBytes(origFile));
                                        JsonNode languageNode = origMetaJson.get("language");
                                        if (languageNode instanceof com.fasterxml.jackson.databind.node.ObjectNode langNode) {
                                            java.util.List<String> langKeys = new java.util.ArrayList<>();
                                            langNode.fieldNames().forEachRemaining(langKeys::add);
                                            boolean packModified = false;
                                            for (String langKey : langKeys) {
                                                boolean keep = langKey.equals("en_us")
                                                           || langKey.equals("es_ar")
                                                           || langKey.equals("es_cl")
                                                           || langKey.equals("es_es")
                                                           || langKey.equals("es_mx")
                                                           || langKey.equals("es_uy")
                                                           || langKey.equals("es_ve");
                                                if (!keep) {
                                                    langNode.remove(langKey);
                                                    packModified = true;
                                                }
                                            }
                                            if (packModified) {
                                                byte[] filteredBytes = mapper.writeValueAsBytes(origMetaJson);
                                                String newHash = com.experimento.launcher.util.Hashing.sha1Hex(
                                                    new java.io.ByteArrayInputStream(filteredBytes)
                                                );
                                                Path newFile = dirs.assetsDir().resolve("objects").resolve(newHash.substring(0, 2)).resolve(newHash);
                                                if (!Files.exists(newFile)) {
                                                    Files.createDirectories(newFile.getParent());
                                                    Files.write(newFile, filteredBytes);
                                                }
                                                if (packMcMetaNode instanceof com.fasterxml.jackson.databind.node.ObjectNode pmNode) {
                                                    pmNode.put("hash", newHash);
                                                    pmNode.put("size", filteredBytes.length);
                                                    indexModified = true;
                                                }
                                            }
                                        }
                                    }
                                }

                                if (indexModified) {
                                    Files.writeString(indexFile, mapper.writeValueAsString(indexJson));
                                    log.accept("[LAUNCHER] 🧹 Deep Clean: Ocultados idiomas de la lista de selección de forma automática");
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.accept("[LAUNCHER] ⚠️ Error en filtro dinámico de idiomas: " + e.getMessage());
        }

        log.accept("[LAUNCHER] Servidores sincronizados (" + (p.servers != null ? p.servers.size() : 0) + ")");
        log.accept("Instancia lista en: " + gameDir);
    }
    
    private void cleanLanguageInterface(Path gameDir, Consumer<String> log) {
        try {
            Path assetsDir = gameDir.resolve("assets/minecraft/lang");
            if (!Files.isDirectory(assetsDir)) return;
            
            java.util.Set<String> keepLanguages = java.util.Set.of(
                "en_us.json",
                "es_ar.json", "es_cl.json", "es_es.json", "es_mx.json", 
                "es_uy.json", "es_ve.json"
            );
            
            int cleaned = 0;
            try (var stream = Files.list(assetsDir)) {
                for (Path langFile : stream.toList()) {
                    String fileName = langFile.getFileName().toString();
                    if (fileName.endsWith(".json") && !keepLanguages.contains(fileName)) {
                        Files.delete(langFile);
                        cleaned++;
                    }
                }
            }
            
            if (cleaned > 0) {
                log.accept("[LAUNCHER] 🧹 Idiomas limpiados: " + cleaned + " eliminados (solo ES + EN)");
            }
        } catch (Exception e) {
            log.accept("[LAUNCHER] ⚠️ Error limpiando idiomas: " + e.getMessage());
        }
    }

    public List<String> buildLaunchCommand(LauncherProfile p, long ramMiB) throws Exception {
        String versionId = p.lastVersionId;
        Path mergedPath = dirs.versionsDir().resolve(versionId).resolve("version.json");
        if (!Files.isRegularFile(mergedPath)) {
            throw new IllegalStateException("Falta instalar la versión " + versionId + " (usa Instalar).");
        }
        JsonNode merged = mapper.readTree(Files.readAllBytes(mergedPath));
        if (!OfflineUuid.uuidMatchesUsername(p.username, p.offlineUuid)) {
            throw new IllegalStateException(
                    "El UUID offline no coincide con el nombre; renombrar puede romper datos en servidor.");
        }
        Path gameDir = gameDirFor(p);
        List<String> jvm = JvmPresetService.argsFor(p, ramMiB, HardwareProbe.physicalCores(), HardwareProbe.availableProcessors());
        
        // Fixes de compatibilidad de módulos para Java 17+
        jvm.add("-Djdk.module.illegalAccess.silent=true");
        jvm.add("-XX:+IgnoreUnrecognizedVMOptions");
        jvm.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        
        // Fix de Red / LAN — separados en LAN (local) + servidores externos (Aternos etc.)
        jvm.addAll(LanFixService.getLanArgs());
        jvm.addAll(LanFixService.getServerConnectArgs());
        
        String effectiveJava = p.javaExecutable;
        int requiredVer = getRequiredJavaVersion(merged, versionId);
        
        // Detección inteligente de la versión de Java requerida si no hay una manual
        if (effectiveJava == null || effectiveJava.isBlank()) {
            if (requiredVer > 0) {
                Path portable = runtimeService.getExecutable(requiredVer);
                if (portable != null) {
                    effectiveJava = portable.toAbsolutePath().toString();
                } else {
                    System.out.println("[LAUNCHER] Descargando Java " + requiredVer + " requerido (puede tardar un momento)...");
                    try {
                        portable = runtimeService.downloadJavaSync(requiredVer, pct -> {});
                        if (portable != null) effectiveJava = portable.toAbsolutePath().toString();
                    } catch (Exception e) {
                        System.err.println("[LAUNCHER] Falló la descarga automática de Java: " + e.getMessage());
                    }
                }
                
                // Fallback a Java 17 solo si se solicitaba 21 y la descarga falló
                if (effectiveJava == null && requiredVer == 21) {
                    portable = runtimeService.getExecutable(17);
                    if (portable != null) effectiveJava = portable.toAbsolutePath().toString();
                }
            }
        }

        // VALIDACIÓN CRÍTICA: Verificar que el ejecutable realmente exista antes de intentar lanzar
        if (effectiveJava == null || effectiveJava.isBlank() || effectiveJava.equalsIgnoreCase("java")) {
            // Intentar resolver el java del sistema como último recurso
            effectiveJava = com.experimento.launcher.mojang.GameLauncher.resolveJavaBinary();
            
            // Si después de todo sigue siendo solo "java" o nulo, verificar si "java" existe en el PATH
            if (effectiveJava.equalsIgnoreCase("java")) {
                if (!isJavaInPath()) {
                    String msg = (requiredVer > 0) 
                        ? "Falta Java " + requiredVer + ". Por favor, descárgalo desde la pestaña de Java en el launcher."
                        : "No se encontró Java en el sistema. Por favor, instala Java o descarga una versión portátil desde el launcher.";
                    throw new IllegalStateException(msg);
                }
            }
        } else {
            // Si hay una ruta específica (portátil o manual), verificar que el archivo físico exista
            Path javaPath = Path.of(effectiveJava);
            if (!Files.exists(javaPath)) {
                throw new IllegalStateException("El ejecutable de Java no existe en la ruta: " + effectiveJava + 
                    ". Por favor, intenta descargar la versión de Java de nuevo desde la pestaña Java.");
            }
        }

        var launcher = new GameLauncher(dirs.librariesDir(), dirs.assetsDir(), dirs.versionsDir());
        return launcher.buildCommand(
                merged,
                versionId,
                gameDir,
                p.username,
                p.offlineUuid,
                jvm,
                effectiveJava,
                LaunchFeatures.defaults());
    }

    private void enforceVersionIsolation(Path profileDir, String currentVersion, Consumer<String> log) {
        if (currentVersion == null || currentVersion.isBlank()) return;
        try {
            Path versionFile = profileDir.resolve(".meacore_version");
            Path modsDir = profileDir.resolve("mods");
            
            // Si el archivo de versión no existe pero hay mods, significa que venimos de una versión 
            // del launcher que no trackeaba aislamiento, o el usuario los puso a mano.
            // Backup preventivo radical.
            boolean versionMissing = !Files.exists(versionFile);
            String lastVersion = Files.exists(versionFile) ? Files.readString(versionFile).trim() : null;
            
            boolean versionChanged = lastVersion != null && !lastVersion.equals(currentVersion);
            boolean hasMods = Files.exists(modsDir) && isDirectoryNotEmpty(modsDir);

            if ((versionChanged || (versionMissing && hasMods))) {
                String reason = versionChanged ? "cambio de versión (" + lastVersion + " -> " + currentVersion + ")" 
                                               : "detección de mods sin trackear";
                
                log.accept("[LAUNCHER] 🛡️ Protector de Instancia: Detectado " + reason);
                log.accept("[LAUNCHER] Resguardando carpeta 'mods' para evitar crasheos...");
                
                String suffix = (lastVersion != null ? lastVersion : "untracked").replace(".", "_");
                Path backupDir = profileDir.resolve("mods_backup_" + suffix + "_" + System.currentTimeMillis());
                
                if (Files.exists(modsDir)) {
                    Files.move(modsDir, backupDir);
                    log.accept("[LAUNCHER] ✅ Mods antiguos movidos a: " + backupDir.getFileName());
                }
                Files.createDirectories(modsDir);
            }
            
            // (Eliminada la validación estricta de nombres para evitar falsos positivos con mods válidos)

            // Asegurar que el directorio exista antes de escribir el archivo de versión
            Files.createDirectories(profileDir);
            Files.writeString(versionFile, currentVersion);
        } catch (Exception e) {
            // Usar un mensaje descriptivo en lugar de solo el mensaje de la excepción (que suele ser sólo la ruta)
            log.accept("[LAUNCHER] ⚠️ Error en aislamiento de mods: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }



    private boolean isDirectoryNotEmpty(Path path) throws IOException {
        if (!Files.isDirectory(path)) return false;
        try (var stream = Files.list(path)) {
            return stream.findAny().isPresent();
        }
    }

    private boolean isJavaInPath() {
        String cmd = com.experimento.launcher.mojang.OsContext.current().isWindows() ? "where java" : "which java";
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int getRequiredJavaVersion(JsonNode merged, String versionId) {
        if (merged.has("javaVersion")) {
            int major = merged.get("javaVersion").path("majorVersion").asInt(0);
            if (major > 0) return major;
        }

        String mainClass = merged.path("mainClass").asText("").toLowerCase();
        if (mainClass.contains("launchwrapper") || mainClass.contains("net.minecraft.launchwrapper.launch") || 
            (VersionComparator.isVersionAtLeast(versionId, 1, 12, 2) && !VersionComparator.isVersionAtLeast(versionId, 1, 13, 0))) {
            return 8;
        }

        // Java 21 para 1.20.5+ y versiones futuras (IDs altos como 26.x.x)
        if (VersionComparator.isVersionAtLeast(versionId, 1, 20, 5)) {
            return 21;
        }

        // Java 17 para 1.17 - 1.20.4
        if (VersionComparator.isVersionAtLeast(versionId, 1, 17, 0) && !VersionComparator.isVersionAtLeast(versionId, 1, 20, 5)) {
            return 17;
        }

        return 0;
    }

    public Process startGame(LauncherProfile p, long ramMiB, Consumer<String> log) throws Exception {
        Path profileDir = gameDirFor(p);
        enforceVersionIsolation(profileDir, p.lastVersionId, log);



        // Alerta informativa sobre RAM total
        try {
            var hw = SystemInfoService.getInfo();
            long totalMiB = hw.totalRamBytes() / (1024 * 1024);
            long availableMiB = hw.availableRamBytes() / (1024 * 1024);
            
            if (ramMiB > totalMiB) {
                log.accept("[LAUNCHER] ADVERTENCIA: Asignados " + ramMiB + "MB. RAM Total: " + totalMiB + "MB. (Posible crasheo)");
            }
            if (availableMiB < 1024) {
                log.accept("[LAUNCHER] CRÍTICO: Tienes solo " + availableMiB + "MB libres. MeaCore recomienda cerrar aplicaciones para evitar cierres inesperados.");
            } else if (ramMiB > availableMiB) {
                log.accept("[LAUNCHER] OJO: Tienes solo " + availableMiB + "MB libres. Recomendamos cerrar otras apps.");
            }
        } catch (Exception ignored) {}

        Path versionJar = dirs.versionsDir().resolve(p.lastVersionId).resolve(p.lastVersionId + ".jar");
        if (!Files.exists(versionJar)) {
            log.accept("[LAUNCHER] ❌ La versión " + p.lastVersionId + " no está instalada. Haz clic en 'Instalar' primero.");
            throw new IllegalStateException("La versión " + p.lastVersionId + " no está instalada.");
        }

        prepareInstance(p, ramMiB, log);
        List<String> cmd = buildLaunchCommand(p, ramMiB);
        log.accept(String.join(" ", cmd.subList(0, Math.min(6, cmd.size()))) + " …");
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gameDirFor(p).toFile());
        pb.redirectErrorStream(true);
        
        applyProcessPriority(pb, log);
        
        Process process = pb.start();
        
        applyProcessPriorityWindows(process, log);
        applyCpuAffinity(process, log);
        
        // Crear servicio de crash report para esta sesión
        CrashReportService crashReporter;
        try {
            crashReporter = new CrashReportService(dirs.launcherData());
        } catch (Exception e) {
            crashReporter = null;
            log.accept("[LAUNCHER] ⚠️ No se pudo inicializar telemetría de crashes: " + e.getMessage());
        }
        
        // Hilo de lectura de consola del juego con captura de logs
        final CrashReportService reporter = crashReporter;
        new Thread(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String captured = line;
                    String prefix = "";
                    
                    if (captured.contains("WARN")) prefix = "⚠️ ";
                    else if (captured.contains("ERROR") || captured.contains("FATAL") || captured.contains("Exception")) {
                        prefix = "❌ ";
                    }
                    else if (captured.contains("INFO")) prefix = "ℹ️ ";
                    
                    log.accept("[GAME] " + prefix + captured);
                    
                    // Capturar línea para reporte de crash
                    if (reporter != null) {
                        reporter.processLogLine(captured);
                    }
                }
            } catch (Exception e) {
                log.accept("[LAUNCHER] Error leyendo consola del juego: " + e.getMessage());
            }
            
            // Finalizar reporte de crash cuando el proceso termina
            if (reporter != null) {
                try {
                    Path crashReport = reporter.finalizeCrashReport(p.displayName, p.lastVersionId);
                    if (crashReport != null) {
                        log.accept("[LAUNCHER] 📋 Reporte de telemetría guardado en: " + crashReport.getFileName());
                    }
                } catch (Exception e) {
                    log.accept("[LAUNCHER] Error guardando telemetría: " + e.getMessage());
                }
            }
        }).start();

        return process;
    }

    public void fullDeleteProfile(LauncherProfile p, List<LauncherProfile> allProfiles) throws Exception {
        // 1. Quitar de la lista y persistir en el JSON
        allProfiles.remove(p);
        profiles.save(allProfiles);

        // 2. Borrar permanentemente los archivos físicos si es una instancia aislada
        if (!p.useGlobalMinecraftFolder) {
            Path gameDir = gameDirFor(p);
            if (Files.exists(gameDir)) {
                deleteDirectoryRecursively(gameDir);
            }
        }
    }

    private void deleteDirectoryRecursively(Path path) throws Exception {
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(java.io.File::delete);
        }
    }

    /** Apply TLauncher JVM args as custom once (user can edit after). */
    public static void maybeImportTlauncherJvm(LauncherProfile p) {
        if (p.customJvmArgs != null && !p.customJvmArgs.isBlank()) {
            return;
        }
        String s = TlauncherConfigReader.suggestedJvmArgsFromTlauncher();
        if (!s.isBlank()) {
            p.customJvmArgs = s;
        }
    }
    
    private void applyProcessPriority(ProcessBuilder pb, java.util.function.Consumer<String> log) {
        OsContext os = com.experimento.launcher.mojang.OsContext.current();
        
        if (os.isLinux() || os.isMac()) {
            try {
                List<String> originalCmd = new java.util.ArrayList<>(pb.command());
                List<String> niceCmd = new java.util.ArrayList<>();
                niceCmd.add("nice");
                niceCmd.add("-n");
                niceCmd.add("-10");
                niceCmd.addAll(originalCmd);
                pb.command(niceCmd);
                log.accept("[LAUNCHER] ⚡ Prioridad nice -10 (alta) activada");
            } catch (Exception e) {
                log.accept("[LAUNCHER] ⚠️ No se pudo configurar prioridad: " + e.getMessage());
            }
        }
    }

    private void applyProcessPriorityWindows(Process process, java.util.function.Consumer<String> log) {
        OsContext os = com.experimento.launcher.mojang.OsContext.current();
        if (!os.isWindows()) return;
        try {
            long pid = process.toHandle().pid();
            ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-Command",
                "$process = Get-Process -Id " + pid + " -ErrorAction SilentlyContinue; if ($process) { $process.PriorityClass = 'High' }"
            );
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            log.accept("[LAUNCHER] ⚡ Prioridad Alta (high) activada para el proceso (PID " + pid + ")");
        } catch (Exception e) {
            log.accept("[LAUNCHER] ⚠️ No se pudo configurar prioridad de proceso en Windows: " + e.getMessage());
        }
    }
    
    private void applyCpuAffinity(Process process, java.util.function.Consumer<String> log) {
        try {
            int physicalCores = HardwareProbe.physicalCores();
            
            if (physicalCores <= 1) {
                log.accept("[LAUNCHER] ℹ️ CPU Affinity: Solo 1 núcleo disponible");
                return;
            }
            
            OsContext os = com.experimento.launcher.mojang.OsContext.current();
            
            if (os.isWindows()) {
                int coresToUse = Math.max(1, physicalCores - 1);
                String maskBinary = "1".repeat(coresToUse);
                java.math.BigInteger mask = new java.math.BigInteger(maskBinary, 2);
                
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                        "powershell", "-NoProfile", "-Command",
                        "(Get-Process -Id " + process.pid() + ").ProcessorAffinity = " + mask.toString()
                    );
                    pb.start();
                    log.accept("[LAUNCHER] ⚡ CPU Affinity: " + coresToUse + " núcleos asignados al juego");
                } catch (Exception e) {
                    log.accept("[LAUNCHER] ℹ️ CPU Affinity no disponible: " + e.getMessage());
                }
            } 
            else if (os.isLinux()) {
                int coresToUse = Math.max(1, physicalCores - 1);
                String cpuRange = "0-" + (coresToUse - 1);
                
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                        "taskset", "-cp", cpuRange, String.valueOf(process.pid())
                    );
                    pb.start();
                    log.accept("[LAUNCHER] ⚡ CPU Affinity: cores 0-" + (coresToUse - 1) + " asignados");
                } catch (Exception e) {
                    log.accept("[LAUNCHER] ℹ️ CPU Affinity no disponible");
                }
            }
        } catch (Exception e) {
            log.accept("[LAUNCHER] ℹ️ CPU Affinity: Automático");
        }
    }
}
