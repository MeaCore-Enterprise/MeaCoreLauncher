package com.experimento.launcher.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Servicio de captura y análisis de crashes de Minecraft.
 * Detecta errores, excepciones y excepciones de OutOfMemory en tiempo real,
 * y genera reportes detallados para diagnóstico.
 */
public final class CrashReportService {

    private static final DateTimeFormatter FILENAME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter CONTENT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private static final Pattern CRASH_PATTERN = Pattern.compile(
        "(?i)(Exception|FATAL|ERROR|java\\.lang\\.|OutOfMemoryError|StackOverflowError|ClassNotFoundException)"
    );
    
    private final Path crashesDir;
    private final StringBuilder currentLog;
    private final List<String> crashLines;
    private boolean hasCrashed;

    public CrashReportService(Path launcherDataDir) throws IOException {
        this.crashesDir = launcherDataDir.resolve("crash-reports");
        Files.createDirectories(crashesDir);
        this.currentLog = new StringBuilder();
        this.crashLines = new ArrayList<>();
        this.hasCrashed = false;
    }

    /**
     * Procesa una línea de log del juego y detecta crashes.
     */
    public void processLogLine(String line) {
        if (line == null || line.isBlank()) return;

        currentLog.append(line).append("\n");

        if (isCrashLine(line)) {
            hasCrashed = true;
            crashLines.add(line);
        }
    }

    /**
     * Detecta si una línea es indicativa de un crash.
     */
    private boolean isCrashLine(String line) {
        String lower = line.toLowerCase();
        return lower.contains("exception") || lower.contains("error") || lower.contains("fatal")
            || lower.contains("crash") || lower.contains("outofmemory")
            || CRASH_PATTERN.matcher(line).find();
    }

    /**
     * Finaliza la captura y guarda el reporte si hubo crashes o logs significativos.
     */
    public Path finalizeCrashReport(String profileName, String versionId) throws IOException {
        if (!hasCrashed && currentLog.length() < 100) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        String filename = String.format("crash_%s_%s_%s.log", 
            profileName.replaceAll("[\\\\/:*?\"<>| ]", "_"),
            versionId.replaceAll("[\\\\/:*?\"<>|. ]", "_"),
            now.format(FILENAME_FMT));
        
        Path reportFile = crashesDir.resolve(filename);

        StringBuilder report = new StringBuilder();
        report.append("=== MeaCore Crash Report ===\n");
        report.append("Timestamp: ").append(now.format(CONTENT_FMT)).append("\n");
        report.append("Profile: ").append(profileName).append("\n");
        report.append("Version: ").append(versionId).append("\n");
        report.append("Status: ").append(hasCrashed ? "CRASHED" : "CLOSED_NORMALLY").append("\n");
        report.append("Error Lines: ").append(crashLines.size()).append("\n\n");

        report.append("--- System Info ---\n");
        try {
            var hw = SystemInfoService.getInfo();
            report.append("CPU: ").append(hw.cpuName()).append(" (").append(hw.physicalCores()).append(" cores)\n");
            report.append("Total RAM: ").append(hw.totalRamBytes() / (1024 * 1024)).append(" MB\n");
            report.append("Available RAM: ").append(hw.availableRamBytes() / (1024 * 1024)).append(" MB\n");
            report.append("OS: ").append(hw.osName()).append(" ").append(System.getProperty("os.version", "unknown")).append("\n");
            report.append("Java Version: ").append(System.getProperty("java.version")).append("\n\n");
        } catch (Exception ignored) {}

        if (!crashLines.isEmpty()) {
            report.append("--- Crash Indicators (").append(crashLines.size()).append(" línea(s)) ---\n");
            for (String crashLine : crashLines) {
                report.append("  > ").append(crashLine).append("\n");
            }
            report.append("\n");
        }

        report.append("--- Full Game Log (últimas 200 líneas) ---\n");
        String[] logLines = currentLog.toString().split("\n");
        int start = Math.max(0, logLines.length - 200);
        for (int i = start; i < logLines.length; i++) {
            report.append(logLines[i]).append("\n");
        }

        Files.writeString(reportFile, report.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
        // Reportar a Supabase si hubo un error real (Asíncrono para no bloquear la salida)
        if (hasCrashed) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                SupabaseService.reportCrash(report.toString(), com.experimento.launcher.LauncherMetadata.VERSION);
            }, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        }

        return reportFile;
    }

    /**
     * Obtiene el directorio de crash reports.
     */
    public Path getCrashesDir() {
        return crashesDir;
    }

    /**
     * Obtiene una lista de crashs anteriores.
     */
    public List<Path> getPreviousCrashes() throws IOException {
        List<Path> crashes = new ArrayList<>();
        try (var stream = Files.list(crashesDir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("crash_") && p.getFileName().toString().endsWith(".log"))
                  .sorted((a, b) -> b.getFileName().compareTo(a.getFileName()))
                  .limit(50)
                  .forEach(crashes::add);
        }
        return crashes;
    }

    /**
     * Analiza un reporte guardado y extrae información clave.
     */
    public CrashAnalysis analyzeCrash(Path reportFile) throws IOException {
        String content = Files.readString(reportFile);
        CrashAnalysis analysis = new CrashAnalysis();
        analysis.filename = reportFile.getFileName().toString();
        analysis.filePath = reportFile;

        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.contains("OutOfMemoryError")) {
                analysis.errorType = "OutOfMemoryError";
                analysis.severity = 10;
                analysis.probable_cause = "RAM insuficiente - aumenta memoria asignada";
            } else if (line.contains("StackOverflowError")) {
                analysis.errorType = "StackOverflowError";
                analysis.severity = 9;
                analysis.probable_cause = "Recursión infinita - problema en mod";
            } else if (line.contains("ClassNotFoundException")) {
                analysis.errorType = "ClassNotFoundException";
                analysis.severity = 8;
                analysis.probable_cause = "Mod compatible omitida o corrupción";
            } else if (line.contains("NoSuchMethodError")) {
                analysis.errorType = "NoSuchMethodError";
                analysis.severity = 7;
                analysis.probable_cause = "Mod incompatible (version mismatch)";
            } else if (line.contains("NullPointerException")) {
                analysis.errorType = "NullPointerException";
                analysis.severity = 6;
                analysis.probable_cause = "Error en mod o config";
            }

            if (line.contains("Version:")) {
                analysis.versionId = line.substring(line.indexOf(":") + 1).trim();
            }
            if (line.contains("Profile:")) {
                analysis.profileName = line.substring(line.indexOf(":") + 1).trim();
            }
        }

        return analysis;
    }

    /**
     * Información sobre un crash analizado.
     */
    public static class CrashAnalysis {
        public String filename;
        public Path filePath;
        public String profileName = "Unknown";
        public String versionId = "Unknown";
        public String errorType = "Unknown Error";
        public String probable_cause = "Ver logs para más detalles";
        public int severity = 5;

        @Override
        public String toString() {
            return String.format("[%s] %s - %s (Severity: %d/10)", filename, errorType, probable_cause, severity);
        }
    }
}
