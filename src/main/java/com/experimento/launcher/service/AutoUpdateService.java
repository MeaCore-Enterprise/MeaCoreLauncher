package com.experimento.launcher.service;

import com.experimento.launcher.LauncherMetadata;
import com.experimento.launcher.mojang.HttpFiles;
import javafx.application.Platform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Servicio de auto-actualización del launcher — Windows y Linux.
 *
 * Mejoras v2.0:
 *  1. Descarga delegada a HttpFiles (8MB buffer, BufferedI/O, retry x3, backoff exponencial).
 *  2. Guards atómicos — imposible lanzar dos checks o dos descargas simultáneas.
 *  3. Rate limiting — el check de GitHub no se repite más de una vez por hora.
 *  4. Limpieza automática de instaladores viejos antes de descargar.
 *  5. Limpieza de archivos parciales si la descarga falla.
 *  6. Thread.sleep reducido a 300ms (bat ya está completamente desacoplado).
 *  7. Nombre de bat con timestamp único — evita colisión si se clica Reiniciar dos veces.
 *  8. Bat se autolimpia con "del %~f0" al terminar.
 *  9. Mensajes de error humanizados — sin rutas técnicas crudas al usuario.
 * 10. Timeout explícito en el HttpRequest al GitHub API.
 * 11. Unblock-File lanzado antes de notificar al listener — ya está listo cuando el usuario clica.
 */
public final class AutoUpdateService {

    private static final String GITHUB_API_LATEST =
            "https://api.github.com/repos/MeaCore-Enterprise/MeaCoreLauncher/releases/latest";

    private static final ObjectMapper M = new ObjectMapper();

    // Listener de eventos — volatile para visibilidad entre hilos
    private static volatile UpdateListener listener;

    // Guards: impiden checks, descargas e instalaciones duplicadas simultáneas
    private static final AtomicBoolean checking    = new AtomicBoolean(false);
    private static final AtomicBoolean downloading = new AtomicBoolean(false);
    private static final AtomicBoolean installing  = new AtomicBoolean(false);

    // Rate limiting: no chequear GitHub más de una vez por hora
    private static volatile Instant lastCheck    = Instant.EPOCH;
    private static final Duration   CHECK_COOLDOWN = Duration.ofHours(1);

    public interface UpdateListener {
        void onUpdateFound(String version, String url);
        void onDownloadProgress(double fraction);
        /** Descarga completada. El listener decide si llamar installFromPath(). */
        void onDownloadComplete(Path installerPath);
        void onDownloadError(String message);
    }

    public static void setListener(UpdateListener l) {
        listener = l;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHECK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Consulta GitHub Releases en segundo plano.
     * - No lanza un segundo hilo si ya hay uno corriendo (guard atómico).
     * - No consulta si el último check fue hace menos de 1 hora (rate limiting).
     */
    public static void checkForUpdatesAsync() {
        if (!checking.compareAndSet(false, true)) return;

        if (Duration.between(lastCheck, Instant.now()).compareTo(CHECK_COOLDOWN) < 0) {
            checking.set(false);
            return;
        }

        Thread t = new Thread(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(GITHUB_API_LATEST))
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "MeaCoreLauncher/" + LauncherMetadata.VERSION)
                        // Timeout total del request — sin esto usa solo el connectTimeout
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();

                HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                lastCheck = Instant.now();
                if (res.statusCode() != 200) {
                    if (listener != null) listener.onDownloadError("GitHub API no disponible (código " + res.statusCode() + "). Intenta más tarde.");
                    return;
                }

                JsonNode release = M.readTree(res.body());
                String tagName       = release.path("tag_name").asText("");
                String latestRaw     = tagName;
                String currentRaw    = LauncherMetadata.VERSION;
                String latestVersion = normalizeVersion(tagName);
                String currentVersion = normalizeVersion(LauncherMetadata.VERSION);

                if (latestVersion.isBlank()) return;
                if (latestVersion.equals(currentVersion) && !latestRaw.equals(currentRaw)) {
                    // remote is a stable release where local is a pre-release — proceed
                } else if (!isNewer(latestVersion, currentVersion)) {
                    return;
                }

                boolean isWindows    = isWindows();
                String  preferredExt = isWindows ? ".exe" : ".deb";
                String  downloadUrl  = null;

                for (JsonNode asset : release.path("assets")) {
                    if (asset.path("name").asText("").endsWith(preferredExt)) {
                        downloadUrl = asset.path("browser_download_url").asText("");
                        break;
                    }
                }

                if (downloadUrl != null && listener != null) {
                    final String url = downloadUrl;
                    listener.onUpdateFound(latestVersion, url);
                }

            } catch (Exception e) {
                if (listener != null) listener.onDownloadError("Error de conexión: " + e.getMessage());
            } finally {
                checking.set(false);
            }
        }, "meacore-update-check");
        t.setDaemon(true);
        t.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DOWNLOAD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Descarga el instalador usando HttpFiles:
     *   - Buffer 8MB con BufferedInputStream + BufferedOutputStream en capas
     *   - Retry automático x3 con backoff exponencial (1s, 2s, 4s)
     *   - Timeout de conexión 30s / lectura 10min
     *   - Progreso en tiempo real vía Consumer<Double>
     *
     * Solo notifica onDownloadComplete — la instalación la dispara el usuario.
     */
    public static void downloadAndInstallAsync(String installerUrl) {
        if (!downloading.compareAndSet(false, true)) return;

        Thread t = new Thread(() -> {
            Path dest = null;
            try {
                boolean isWindows = isWindows();
                String  ext       = isWindows ? ".exe" : ".deb";

                Path updateDir = isWindows
                        ? Path.of(System.getenv().getOrDefault("LOCALAPPDATA",
                                  System.getProperty("java.io.tmpdir")), "MeaCore", "updates")
                        : Path.of(System.getProperty("user.home"), ".cache", "meacore", "updates");

                Files.createDirectories(updateDir);

                // Limpiar instaladores anteriores antes de descargar
                cleanOldInstallers(updateDir, ext);

                dest = updateDir.resolve("meacore-update-" + System.currentTimeMillis() + ext);
                final Path finalDest = dest;

                // HttpFiles: 8MB buffer + BufferedI/O + retry x3 + backoff exponencial
                // sin hash (null) porque GitHub no expone el SHA del asset en la API
                HttpFiles.downloadIfHashMismatch(installerUrl, finalDest, null, fraction -> {
                    if (listener != null) {
                        listener.onDownloadProgress(fraction < 0 ? -1.0 : fraction);
                    }
                });

                // Sanidad: verificar que el archivo no esté vacío
                if (!Files.exists(finalDest) || Files.size(finalDest) == 0) {
                    throw new IllegalStateException("El archivo descargado está vacío.");
                }

                // Unblock-File ANTES de notificar al listener
                // → cuando el usuario clique "Reiniciar Ahora" ya estará desbloqueado
                if (isWindows) {
                    unblockFileAsync(finalDest);
                }

                if (listener != null) listener.onDownloadComplete(finalDest);

            } catch (Exception ex) {
                // Limpiar archivo parcial para no dejar basura en disco
                if (dest != null) {
                    try { Files.deleteIfExists(dest); } catch (Exception ignored) {}
                }
                if (listener != null) listener.onDownloadError(humanizeError(ex));
            } finally {
                downloading.set(false);
            }
        }, "meacore-update-download");
        t.setDaemon(true);
        t.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INSTALL  (llamado desde la UI cuando el usuario confirma)
    // ─────────────────────────────────────────────────────────────────────────

    public static void installFromPath(Path installerPath) {
        if (!installing.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                executeInstaller(installerPath, isWindows());
            } finally {
                installing.set(false);
            }
        }, "meacore-update-install").start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IMPLEMENTACIÓN INTERNA
    // ─────────────────────────────────────────────────────────────────────────

    private static void executeInstaller(Path installerPath, boolean isWindows) {
        try {
            String absPath = installerPath.toAbsolutePath().toString();

            if (isWindows) {
                // Obtener ruta del ejecutable actual para relanzarlo con precisión
                String currentExe = ProcessHandle.current().info().command().orElse("MeaCore Launcher.exe");
                
                Path batFile = installerPath.getParent()
                        .resolve("meacore_updater_" + System.currentTimeMillis() + ".bat");

                String bat =
                    "@echo off\r\n" +
                    "chcp 65001 > nul\r\n" + // Forzar UTF-8 en la consola
                    "title MeaCore Updater - Actualizando...\r\n" +
                    "echo. \r\n" +
                    "echo ================================================\r\n" +
                    "echo        ACTUALIZANDO MEACORE LAUNCHER\r\n" +
                    "echo ================================================\r\n" +
                    "echo. \r\n" +
                    "echo 1. Esperando a que el Launcher se cierre...\r\n" +
                    "timeout /t 5 /nobreak > nul\r\n" +
                    "taskkill /F /IM \"MeaCore Launcher.exe\" /T > nul 2>&1\r\n" +
                    "echo. \r\n" +
                    "echo 2. Iniciando Instalador (Si pide permisos, acepta)...\r\n" +
                    "start /wait \"\" \"" + absPath + "\" /SILENT /NORESTART /CLOSEAPPLICATIONS /FORCECLOSEAPPLICATIONS /SUPPRESSMSGBOXES\r\n" +
                    "echo. \r\n" +
                    "echo 3. Relanzando MeaCore Launcher...\r\n" +
                    "if exist \"" + currentExe + "\" (\r\n" +
                    "    start \"\" \"" + currentExe + "\"\r\n" +
                    ") else (\r\n" +
                    "    echo No se pudo relanzar automaticamente desde: \"" + currentExe + "\"\r\n" +
                    "    echo Por favor, abre el launcher manualmente.\r\n" +
                    "    pause\r\n" +
                    ")\r\n" +
                    "del \"%~f0\"\r\n" +
                    "exit\r\n";

                Files.writeString(batFile, "\uFEFF" + bat);

                new ProcessBuilder(
                        "cmd", "/c", "start", "\"MeaCore Updater\"",
                        "\"" + batFile.toAbsolutePath().toString() + "\"")
                        .start();

            } else {
                // Linux: detect package manager and elevation tool, then install & relaunch
                String currentExe = ProcessHandle.current().info().command().orElse("/opt/meacore-launcher/bin/MeaCore Launcher");
                String pmCmd;
                if (Files.exists(Path.of("/usr/bin/apt"))) {
                    pmCmd = "apt install";
                } else if (Files.exists(Path.of("/usr/bin/dnf"))) {
                    pmCmd = "dnf install";
                } else {
                    pmCmd = "dpkg -i";
                }

                // pkexec muestra un diálogo gráfico de PolicyKit y no necesita TTY ni
                // contraseña en sudoers → funciona desde el launcher sin consola.
                // Fallback a sudo para sistemas sin PolicyKit.
                String elevate = Files.exists(Path.of("/usr/bin/pkexec")) ? "pkexec" : "sudo";

                boolean useY = !pmCmd.equals("dpkg -i");
                String installCmd = String.format(
                        "sleep 2 && %s %s %s\"%s\" 2>&1",
                        elevate, pmCmd, useY ? "-y " : "", absPath);
                String cmd = installCmd + " && (nohup \"" + currentExe + "\" > /dev/null 2>&1 &)";
                new ProcessBuilder("bash", "-c", cmd).start();
            }

            // 2000ms para que el proceso hijo se desacople completamente
            Thread.sleep(2000);
            Platform.exit();
            System.exit(0);

        } catch (Exception e) {
            // No cerramos el launcher si la elevación/instalación falló:
            // el usuario necesita feedback y poder reintentar.
            if (listener != null) {
                listener.onDownloadError("No se pudo lanzar la instalación: " + e.getMessage());
            }
        }
    }

    /**
     * Lanza PowerShell Unblock-File como fire-and-forget.
     * No usa .waitFor() — nunca bloquea el hilo de descarga.
     */
    private static void unblockFileAsync(Path file) {
        try {
            new ProcessBuilder(
                    "powershell", "-ExecutionPolicy", "Bypass", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-Command", "Unblock-File -Path '" + file.toAbsolutePath() + "'")
                    .start();
        } catch (Exception ignored) {}
    }

    /**
     * Elimina instaladores anteriores del directorio de updates.
     * Evita acumulación de archivos .exe/.deb de versiones previas.
     */
    private static void cleanOldInstallers(Path dir, String ext) {
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("meacore-update-")
                           && p.getFileName().toString().endsWith(ext))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                  });
        } catch (Exception ignored) {}
    }

    /**
     * Convierte excepciones técnicas en mensajes legibles para el usuario.
     * El listener no debería mostrar rutas del sistema ni stack traces.
     */
    private static String humanizeError(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null) return "Error inesperado al descargar la actualización.";
        if (msg.contains("updates\\meacore-update") || msg.contains("Access is denied")) {
            return "El archivo de actualización está bloqueado por Windows o el antivirus. Reinicia el launcher e intenta de nuevo.";
        }
        if (msg.contains("Connection refused") || msg.contains("UnknownHost")
                || msg.contains("UnknownHostException")) {
            return "Sin conexión a internet. Verifica tu red e intenta de nuevo.";
        }
        if (msg.contains("timed out") || msg.contains("timeout")) {
            return "La descarga tardó demasiado. Verifica tu conexión e intenta de nuevo.";
        }
        if (msg.contains("SHA1 mismatch")) {
            return "El archivo descargado está corrupto. Intenta de nuevo.";
        }
        if (msg.contains("vacío")) {
            return "Se descargó un archivo vacío. Verifica tu conexión e intenta de nuevo.";
        }
        return "Error al descargar: " + msg;
    }

    private static String normalizeVersion(String raw) {
        if (raw == null) return "";
        return raw.replaceFirst("^bat-", "")
                  .replaceFirst("^[vV]", "")
                  .replaceAll("(?i)-(alfa|alpha|beta|rc|snapshot|pre|dev).*", "")
                  .trim();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isNewer(String latest, String current) {
        try {
            String[] lp = latest.split("\\.");
            String[] cp = current.split("\\.");
            int max = Math.max(lp.length, cp.length);
            for (int i = 0; i < max; i++) {
                int l = i < lp.length ? Integer.parseInt(lp[i].replaceAll("[^0-9]", "")) : 0;
                int c = i < cp.length ? Integer.parseInt(cp[i].replaceAll("[^0-9]", "")) : 0;
                if (l > c) return true;
                if (l < c) return false;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
