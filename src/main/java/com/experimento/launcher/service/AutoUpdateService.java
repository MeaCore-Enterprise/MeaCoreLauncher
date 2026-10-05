package com.experimento.launcher.service;

import com.experimento.launcher.LauncherMetadata;
import com.experimento.launcher.mojang.HttpFiles;
import com.experimento.launcher.util.Hashing;
import com.experimento.launcher.util.SemVer;
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
 *
 * Mejoras v2.1 (arreglo del loop de auto-actualización):
 * 12. La decisión de "hay actualización" la toma {@link SemVer}, que normaliza AMBOS
 *     lados (tag "v1.6.1" y version.properties "1.6.1") antes de comparar. Antes se comparaba
 *     el tag crudo contra el valor crudo de version.properties, que nunca lleva prefijo, así que
 *     la comparación era siempre distinta y el launcher se re-detectaba a sí mismo en loop.
 * 13. Se eliminó la rama espuria de "pre-release → estable" que comparaba strings crudos; la
 *     promoción de pre-release a estable se resuelve por canal dentro de SemVer.
 * 14. {@link UpdateState} persiste las versiones ya instaladas o descartadas: no se vuelve a
 *     ofrecer la misma versión aunque el instalador haya fallado en silencio.
 * 15. El cooldown se aplica también cuando el check falla, y el check manual del botón salta
 *     el cooldown (antes se quedaba en "Buscando..." para siempre).
 */
public final class AutoUpdateService {

    private static final String GITHUB_API_LATEST =
            "https://api.github.com/repos/MeaCore-Enterprise/MeaCoreLauncher/releases/latest";

    /**
     * Respaldo cuando GitHub limita las peticiones. El Worker guarda la release en la caché
     * del edge, así que responde aunque la API de GitHub esté devolviendo 403. Trae los mismos
     * campos (digest y size incluidos) para que la verificación siga siendo posible.
     */
    private static final String MIRROR_API_LATEST =
            "https://meacorelauncher.meacore-enterprise.workers.dev/api/latest-release";

    private static final ObjectMapper M = new ObjectMapper();

    // Listener de eventos — volatile para visibilidad entre hilos
    private static volatile UpdateListener listener;

    // Guards: impiden checks, descargas e instalaciones duplicadas simultáneas
    private static final AtomicBoolean checking    = new AtomicBoolean(false);
    private static final AtomicBoolean downloading = new AtomicBoolean(false);
    private static final AtomicBoolean installing  = new AtomicBoolean(false);

    // Versión ofrecida/descargada esperando a que el usuario instale (null si no hay nada pendiente)
    private static volatile String pendingVersion;
    // Integridad del asset anunciado por la API: digest "sha256:..." y tamaño en bytes.
    private static volatile String pendingDigest;
    private static volatile Long   pendingSize;

    // Rate limiting: no chequear GitHub más de una vez por hora. El valor se persiste en
    // update-state.json para que reiniciar el launcher no gaste una petición de GitHub cada vez.
    private static final Duration CHECK_COOLDOWN = Duration.ofHours(1);

    /**
     * Eventos del ciclo de actualización. Los errores de COMPROBACIÓN ({@link #onCheckError})
     * van separados de los de DESCARGA/INSTALACIÓN ({@link #onDownloadError}) porque su
     * recuperación en la UI es distinta: un fallo de check nunca debe inutilizar el botón de
     * actualización.
     */
    public interface UpdateListener {
        void onUpdateFound(String version, String url);
        void onDownloadProgress(double fraction);
        /** Descarga completada. El listener decide si llamar installFromPath(). */
        void onDownloadComplete(Path installerPath);
        /** Fallo al descargar o instalar. */
        void onDownloadError(String message);

        /**
         * La descarga terminó íntegra pero SIN hash válido para verificarla (la API no lo
         * publica, o el digest no coincide con lo anunciado). El archivo queda en disco y la
         * UI debe ofrecer la descarga manual en el navegador en lugar de instalar.
         */
        default void onUnverifiedDownload(Path installerPath) {}

        /** Fallo al COMPROBAR si hay actualizaciones (red, API, rate limit). */
        default void onCheckError(String message) {}

        /** Ya estás en la última versión: no hay nada que ofrecer. */
        default void onUpToDate() {}

        /** Se saltó la comprobación por el rate limiting de 1 hora (solo check automático). */
        default void onCooldownSkipped() {}

        /**
         * Se emite siempre al terminar una comprobación, por cualquier camino.
         * Es la red de seguridad que garantiza que el botón "Buscar Actualizaciones"
         * vuelva a su estado y deje de estar deshabilitado.
         */
        default void onCheckFinished() {}
    }

    public static void setListener(UpdateListener l) {
        listener = l;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHECK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Comprobación automática en segundo plano: respeta el rate limiting de 1 hora.
     */
    public static void checkForUpdatesAsync() {
        checkForUpdatesAsync(false);
    }

    /**
     * @param force true para saltar el rate limiting (botón "Buscar Actualizaciones").
     *              Los guards atómicos aplican igual: nunca hay dos checks a la vez.
     */
    public static void checkForUpdatesAsync(boolean force) {
        if (!checking.compareAndSet(false, true)) {
            // Ya hay un check en marcha: su propio onCheckFinished restaurará el botón.
            return;
        }

        if (!force && isOnCooldown(updateState().lastCheck().orElse(Instant.EPOCH))) {
            checking.set(false);
            if (listener != null) {
                listener.onCooldownSkipped();
                listener.onCheckFinished();
            }
            return;
        }

        Thread t = new Thread(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                FetchResult result = fetchLatestRelease(client, GITHUB_API_LATEST);

                // GitHub limita a 60 peticiones/hora sin token: con el límite alcanzado el
                // usuario se quedaría sin actualizaciones hasta el siguiente arranque. Se
                // reintenta contra la copia en caché del Worker antes de rendirse.
                if (result.status != 200) {
                    FetchResult mirror = fetchLatestRelease(client, MIRROR_API_LATEST);
                    if (mirror.status == 200) {
                        result = mirror;
                    } else {
                        if (listener != null) listener.onCheckError(checkErrorMessage(result.status));
                        return;
                    }
                }

                JsonNode release = result.body;

                evaluateAndNotify(release.path("tag_name").asText(""), release.path("assets"), force);

            } catch (Exception e) {
                if (listener != null) listener.onCheckError(humanizeCheckError(e));
            } finally {
                // El cooldown aplica también tras un fallo: si GitHub está caído, no se reintenta
                // en bucle cada vez que se abre el launcher.
                updateState().markChecked(Instant.now());
                checking.set(false);
                if (listener != null) listener.onCheckFinished();
            }
        }, "meacore-update-check");
        t.setDaemon(true);
        t.start();
    }

/**
 * Decide si la release remota es una actualización real para esta instalación y notifica
 * al listener el resultado (o la ausencia de él).
 *
 * <p>Regla única: se compara siempre la versión NORMALIZADA de ambos lados
 * ({@code tag_name} "v1.6.1" → 1.6.1 contra {@code version.properties} 1.6.1). Si no es
 * estrictamente más nueva, no hay nada que ofrecer — da igual el prefijo del tag, el
 * sufijo de pre-release, o si local y remota son exactamente la misma versión.
 *
     * @param force check manual del usuario: ignora los descartes previos, no las instalaciones.
     */
    private static void evaluateAndNotify(String tagName, JsonNode assets, boolean force) {
        SemVer latest  = SemVer.parse(tagName);
        SemVer current = SemVer.parse(LauncherMetadata.VERSION);

        // Tag ilegible o versión local desconocida (version.properties ausente):
        // no se puede afirmar que haya nada nuevo, así que no se molesta al usuario.
        if (!latest.isValid() || !current.isValid()) return;

        if (!latest.isNewerThan(current)) {
            if (listener != null) listener.onUpToDate();
            return;
        }

        String latestVersion = latest.normalized();

        // Ya instalada: no se re-ofrece nunca (el instalador pudo fallar en silencio).
        UpdateState state = updateState();
        if (state.isInstalled(latestVersion)) return;
        // Descartada con la ✕: no se vuelve a ofrecer en checks automáticos, pero una
        // búsqueda manual del usuario sí la muestra de nuevo.
        if (!force && state.isDismissed(latestVersion)) return;

        boolean isWindows    = isWindows();
        String  preferredExt = isWindows ? ".exe" : ".deb";
        String  downloadUrl  = null;
        String  digest       = null;
        Long    assetSize    = null;

        for (JsonNode asset : assets) {
            if (asset.path("name").asText("").endsWith(preferredExt)) {
                downloadUrl = asset.path("browser_download_url").asText("");
                // GitHub publica "digest": "sha256:...". Si falta, se ofrece la descarga
                // manual en el navegador en vez de instalar sin verificar.
                String d = asset.path("digest").asText("");
                if (d.regionMatches(true, 0, "sha256:", 0, 7)) {
                    digest = d;
                }
                long size = asset.path("size").asLong(0L);
                if (size > 0) {
                    assetSize = size;
                }
                break;
            }
        }

        if (downloadUrl == null || downloadUrl.isBlank()) {
            if (listener != null) {
                listener.onDownloadError("Hay una versión nueva (v" + latestVersion
                        + ") pero no hay instalador disponible para tu sistema.");
            }
            return;
        }

        pendingVersion = latestVersion;
        pendingDigest  = digest;
        pendingSize    = assetSize;
        if (listener != null) listener.onUpdateFound(latestVersion, downloadUrl);
    }

    /** Versión ofrecida/descargada a la espera de que el usuario instale (null si no hay nada pendiente). */
    public static String pendingVersion() {
        return pendingVersion;
    }

    /**
     * El usuario cerró el banner de la actualización pendiente: se registra como descartada
     * para no volver a ofrecer esa misma versión en próximos arranques.
     */
    public static void dismissPendingUpdate() {
        String v = pendingVersion;
        pendingVersion = null;
        if (v != null && !v.isBlank()) {
            updateState().markDismissed(v);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DOWNLOAD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Descarga el instalador a un archivo temporal y solo lo publica cuando termina íntegro:
     *   - Se escribe en {@code <destino>.part} y se renombra con move atómico al completar.
     *   - Se contrasta el tamaño con Content-Length y con el tamaño que declara la API.
     *   - Retry automático x3 con backoff exponencial (1s, 2s, 4s).
     *   - Progreso en tiempo real vía Consumer<Double>.
     *
     * Solo notifica onDownloadComplete — la instalación la dispara el usuario.
     */
    public static void downloadAndInstallAsync(String installerUrl) {
        downloadAndInstallAsync(installerUrl, null, null);
    }

    /**
     * @param expectedSha256 hash SHA-256 del asset (formato {@code sha256:...} de la API), o null.
     * @param expectedSize   tamaño del asset declarado por la API, o null.
     */
    public static void downloadAndInstallAsync(String installerUrl, String expectedSha256, Long expectedSize) {
        if (!downloading.compareAndSet(false, true)) return;
        final String sha256 = (expectedSha256 == null || expectedSha256.isBlank()) ? pendingDigest : expectedSha256;
        final Long   size   = (expectedSize == null)                      ? pendingSize   : expectedSize;

        Thread t = new Thread(() -> {
            Path dest = null;
            try {
                String  ext = isWindows() ? ".exe" : ".deb";
                Path updateDir = updatesDir();

                Files.createDirectories(updateDir);

                // Limpiar instaladores anteriores y restos de descargas interrumpidas.
                cleanOldInstallers(updateDir, ext);
                cleanStaleParts(updateDir, ext);

                dest = updateDir.resolve("meacore-update-" + System.currentTimeMillis() + ext);
                final Path finalDest = dest;

                // .part + move atómico: el destino solo existe si la descarga fue completa.
                HttpFiles.downloadAtomically(installerUrl, finalDest, null, size, fraction -> {
                    if (listener != null) {
                        listener.onDownloadProgress(fraction < 0 ? -1.0 : fraction);
                    }
                });

                // Sanidad: verificar que el archivo no esté vacío
                if (!Files.exists(finalDest) || Files.size(finalDest) == 0) {
                    throw new IllegalStateException("El archivo descargado está vacío.");
                }

                // Verificación de integridad (SHA-256) antes de permitir la instalación.
                // Sin hash disponible NO se instala automáticamente: se ofrece descarga manual.
                String verified = sha256 == null || sha256.isBlank()
                        ? null
                        : verifySha256(finalDest, sha256);
                if (sha256 != null && !sha256.isBlank() && verified == null) {
                    throw new IllegalStateException("SHA256 mismatch");
                }

                if (verified == null) {
                    if (listener != null) listener.onUnverifiedDownload(finalDest);
                    return;
                }

                if (listener != null) listener.onDownloadComplete(finalDest);

            } catch (Exception ex) {
                // Limpiar el .part/archivo incompleto para no dejar basura en disco
                if (dest != null) {
                    try { Files.deleteIfExists(dest); } catch (Exception ignored) {}
                    try { Files.deleteIfExists(HttpFiles.partPathFor(dest)); } catch (Exception ignored) {}
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

    /**
     * Lanza el instalador y relanza el launcher. La versión queda registrada como instalada
     * solo si el proceso del instalador arrancó bien: si falla, el usuario puede reintentar.
     */
    public static void installFromPath(Path installerPath) {
        if (!installing.compareAndSet(false, true)) return;
        new Thread(() -> {
            if (!launchInstallerProcess(installerPath, isWindows())) {
                installing.set(false);
                return;
            }

            // Instalador en marcha: registrar la versión ANTES de cerrar el launcher para
            // que el próximo arranque no vuelva a ofrecer la misma actualización.
            String v = pendingVersion;
            if (v != null && !v.isBlank()) {
                updateState().markInstalled(v);
                pendingVersion = null;
            }

            installing.set(false);
            exitAppSoon();
        }, "meacore-update-install").start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IMPLEMENTACIÓN INTERNA
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Script de actualización para Windows.
     *
     * <p>Secuencia: espera a que el launcher cierre → mata el proceso → arranca el instalador
     * capturando su PID → <b>comprueba su exit code</b> → relanza el launcher solo si la
     * instalación funcionó. Si el instalador falla, deja constancia en un log junto al
     * instalador y NO relanza, para que el usuario vea el error en vez de un launcher
     * mysteriously igual que antes.
     *
     * <p>No se usa PowerShell ni Unblock-File: el SHA-256 ya se verificó antes de llegar
     * aquí, y si el antivirus bloquea el ejecutable es mejor decirlo que saltarse la
     * protección en silencio.
     *
     * @param installerPath ruta absoluta del instalador .exe verificado.
     * @param currentExe    ruta absoluta del ejecutable del launcher en curso.
     */
    static String windowsUpdaterScript(String installerPath, String currentExe) {
        String log = "%~dp0meacore_update.log";

        return "@echo off\r\n"
                + "chcp 65001 > nul\r\n"
                + "title MeaCore Updater - Actualizando...\r\n"
                + "setlocal\r\n"
                + "echo.\r\n"
                + "echo ================================================\r\n"
                + "echo        ACTUALIZANDO MEACORE LAUNCHER\r\n"
                + "echo ================================================\r\n"
                + "echo.\r\n"

                // 1. Dar tiempo a que el launcher cierre y libere el ejecutable.
                + "echo [1/3] Esperando a que el Launcher se cierre...\r\n"
                + "timeout /t 5 /nobreak > nul\r\n"
                + "taskkill /F /IM \"MeaCore Launcher.exe\" /T > nul 2>&1\r\n"

                // 2. Instalar guardando el PID para poder esperar su fin y su codigo.
                + "echo [2/3] Instalando...\r\n"
                + "start \"\" /wait \"MeaCore Updater\" \"" + installerPath + "\"\r\n"
                + "set \"INSTALLER_PID=%ERRORLEVEL%\"\r\n"
                + "echo PID del instalador: %INSTALLER_PID%\r\n"

                // 3. Solo si terminó bien, relanzar.
                + "if not \"%INSTALLER_PID%\"==\"0\" (\r\n"
                + "    echo.\r\n"
                + "    echo [3/3] La instalacion termino con error: %INSTALLER_PID%\r\n"
                + "    echo El launcher NO se ha relanzado. Revisa el instalador.\r\n"
                + "    > \"" + log + "\" echo Instalacion fallida. Codigo: %INSTALLER_PID%\r\n"
                + "    timeout /t 30 /nobreak > nul\r\n"
                + "    goto :fin\r\n"
                + ")\r\n"
                + "echo [3/3] Instalacion correcta. Relanzando MeaCore Launcher...\r\n"
                + "if exist \"" + currentExe + "\" (\r\n"
                + "    start \"\" \"" + currentExe + "\"\r\n"
                + ") else (\r\n"
                + "    echo No se encontro el launcher en: \"" + currentExe + "\"\r\n"
                + "    > \"" + log + "\" echo Instalacion OK pero no se encontro el launcher en " + currentExe + "\r\n"
                + "    timeout /t 30 /nobreak > nul\r\n"
                + ")\r\n"

                + ":fin\r\n"
                + "del \"%~f0\"\r\n"
                + "endlocal\r\n"
                + "exit /b 0\r\n";
    }

    /**
     * Escribe el script de actualización (bat en Windows, comando de paquete en Linux) y lo
     * lanza desacoplado. NO cierra el launcher: eso lo hace installFromPath() solo si el
     * proceso arrancó bien, para poder marcar la versión como instalada antes de morir.
 *
     * @return true si el instalador quedó en marcha.
     */
    private static boolean launchInstallerProcess(Path installerPath, boolean isWindows) {
        try {
            if (!Files.exists(installerPath)) {
                if (listener != null) {
                    listener.onDownloadError("El instalador ya no está en disco. Pulsa 'Buscar Actualizaciones' para descargarlo de nuevo.");
                }
                return false;
            }

            String absPath = installerPath.toAbsolutePath().toString();

            if (isWindows) {
                // Ruta del ejecutable actual, para relanzar exactamente la misma versión.
                String currentExe = ProcessHandle.current().info().command().orElse("MeaCore Launcher.exe");

                Path batFile = installerPath.getParent()
                        .resolve("meacore_updater_" + System.currentTimeMillis() + ".bat");

                Files.writeString(batFile, "\uFEFF" + windowsUpdaterScript(absPath, currentExe));

                // El script se abre en su propia ventana para que el usuario vea el progreso.
                // Se devuelve el proceso ya: el launcher cierra después de registrar la versión.
                new ProcessBuilder(
                        "cmd", "/c", "start", "\"MeaCore Updater\"",
                        "\"" + batFile.toAbsolutePath() + "\"")
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

            return true;

        } catch (Exception e) {
            // No cerramos el launcher si la elevación/instalación falló:
            // el usuario necesita feedback y poder reintentar.
            if (listener != null) {
                listener.onDownloadError("No se pudo lanzar la instalación: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Cierra el launcher dejando que el instalador se haga cargo del proceso.
     * 2000ms para que el proceso hijo se desacople completamente.
     */
    private static void exitAppSoon() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        Platform.exit();
        System.exit(0);
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
     * Elimina restos {@code .part} de descargas interrumpidas.
     * Solo se toca la carpeta de updates: nunca se borra un instalador completo.
     */
    private static void cleanStaleParts(Path dir, String ext) {
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("meacore-update-")
                           && p.getFileName().toString().endsWith(ext + ".part"))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                  });
        } catch (Exception ignored) {}
    }

    /**
     * Verifica el SHA-256 del instalador contra el digest que publica la API de GitHub.
     *
     * @param expected digest con o sin el prefijo {@code sha256:}.
     * @return el hash en hexadecimal si coincide, o null si no coincide o no se pudo calcular.
     *         null NUNCA debe interpretarse como "verificado".
     */
    private static String verifySha256(Path file, String expected) {
        try {
            String want = expected.trim();
            if (want.regionMatches(true, 0, "sha256:", 0, 7)) {
                want = want.substring(7);
            }
            want = want.trim().toLowerCase();

            String got;
            try (InputStream in = Files.newInputStream(file)) {
                got = Hashing.sha256Hex(in);
            }
            return want.equals(got) ? got : null;
        } catch (Exception e) {
            return null;
        }
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
        if (msg.contains("SHA1 mismatch") || msg.contains("SHA256 mismatch")) {
            return "El archivo descargado está corrupto. Intenta de nuevo.";
        }
        if (msg.contains("vacío")) {
            return "Se descargó un archivo vacío. Verifica tu conexión e intenta de nuevo.";
        }
        return "Error al descargar: " + msg;
    }

    /**
     * Convierte el estado HTTP de la API de GitHub en un mensaje accionable en español.
     * 403/429 son el límite de peticiones de la API sin autenticar: no es un error del usuario.
     */
    private static String checkErrorMessage(int status) {
        if (status <= 0) {
            return "Sin conexión con GitHub. Revisa tu red e inténtalo de nuevo.";
        }
        if (status == 403 || status == 429) {
            return "GitHub está limitando las comprobaciones por ahora. Se volverá a intentar automáticamente más tarde.";
        }
        if (status == 404) {
            return "No se encontró ninguna versión publicada todavía.";
        }
        if (status >= 500) {
            return "GitHub está teniendo problemas en este momento. Intenta más tarde.";
        }
        return "No se pudo comprobar si hay actualizaciones (código " + status + ").";
    }

    /** Respuesta de una consulta a la API de releases. */
    private record FetchResult(int status, JsonNode body) {}

    /**
     * Consulta una URL de release. Nunca lanza: cualquier fallo de red se traduce a un estado
     * para que el llamador pueda probar la otra fuente.
     */
    private static FetchResult fetchLatestRelease(HttpClient client, String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MeaCoreLauncher/" + LauncherMetadata.VERSION)
                // Timeout total del request — sin esto usa solo el connectTimeout
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                res.body().close();
                return new FetchResult(res.statusCode(), null);
            }
            try (InputStream body = res.body()) {
                return new FetchResult(200, M.readTree(body));
            }
        } catch (Exception e) {
            // Sin red: se distingue de un 200 con cuerpo ilegible devolviendo 0.
            return new FetchResult(0, null);
        }
    }

    /** true si aún no ha pasado la hora desde la última comprobación. */
    private static boolean isOnCooldown(Instant lastCheck) {
        return Duration.between(lastCheck, Instant.now()).compareTo(CHECK_COOLDOWN) < 0;
    }

    /** Traduce fallos de red a mensajes claros, sin trazas ni rutas técnicas. */
    private static String humanizeCheckError(Exception ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        if (msg.contains("UnknownHost")) {
            return "Sin conexión a internet. Conéctate e inténtalo de nuevo.";
        }
        if (msg.contains("ConnectException") || msg.contains("Connection refused")) {
            return "No se pudo conectar con GitHub. Revisa tu conexión e inténtalo de nuevo.";
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return "GitHub tardó demasiado en responder. Inténtalo de nuevo.";
        }
        return "No se pudo comprobar si hay actualizaciones. Inténtalo de nuevo.";
    }

    /**
     * Directorio donde se guardan los instaladores descargados y el estado de actualización.
     * Windows: %LOCALAPPDATA%/MeaCore/updates — Linux: ~/.cache/meacore/updates
     */
    private static Path updatesDir() {
        return isWindows()
                ? Path.of(System.getenv().getOrDefault("LOCALAPPDATA",
                          System.getProperty("java.io.tmpdir")), "MeaCore", "updates")
                : Path.of(System.getProperty("user.home"), ".cache", "meacore", "updates");
    }

    /** Estado persistente de actualizaciones (best-effort: nunca lanza). */
    private static UpdateState updateState() {
        return UpdateState.load(updatesDir());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
