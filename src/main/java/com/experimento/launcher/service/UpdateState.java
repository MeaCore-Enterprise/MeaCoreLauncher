package com.experimento.launcher.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

/**
 * Estado persistente del ciclo de actualización del launcher.
 *
 * <p>Se guarda en {@code update-state.json} (junto a los instaladores descargados):
 * <ul>
 *   <li>{@code installedVersion} — versión cuyo instalador ya se lanzó. Si el instalador falla
 *       silenciosamente y {@code version.properties} no cambia, el launcher no vuelve a
 *       ofrecer la misma versión en loop.</li>
 *   <li>{@code dismissedVersion} — versión cuyo banner el usuario cerró con la ✕.</li>
 *   <li>{@code lastCheck} — última comprobación contra la API. Persistirlo evita que cada
 *       arranque del launcher, gasta una petición del limitado presupuesto de GitHub.</li>
 * </ul>
 *
 * <p>Si encuentra un {@code update-state.properties} de una versión anterior del launcher, lo
 * lee y lo convierte a JSON: así nadie pierde sus descartes al actualizar.
 *
 * <p>Es best-effort: cualquier fallo de disco se ignora y el flujo de actualización sigue
 * funcionando solo con la comparación de versiones.
 */
public final class UpdateState {

    private static final String FILE_NAME = "update-state.json";
    private static final String LEGACY_FILE_NAME = "update-state.properties";
    private static final String KEY_INSTALLED = "installed.version";
    private static final String KEY_DISMISSED = "dismissed.version";

    private static final ObjectMapper M = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Campos serializados. Los nombres se mantienen en camelCase a propósito. */
    static final class Data {
        public String installedVersion = "";
        public String dismissedVersion = "";
        /** Epoch millis del último check; 0 = nunca. */
        public long lastCheckMillis;
        /**
         * Versión destino de una instalación cuyo instalador se lanzó pero cuyo resultado
         * aún no se sabe. Se borra al arrancar comparando con la versión real.
         */
        public String pendingInstall = "";
    }

    private final Path file;
    private String installedVersion = "";
    private String dismissedVersion = "";
    private Instant lastCheck = Instant.EPOCH;
    private String pendingInstall = "";

    private UpdateState(Path file) {
        this.file = file;
    }

    /** Carga el estado desde {@code dir}; si no existe, devuelve uno vacío (nunca lanza). */
    public static UpdateState load(Path dir) {
        UpdateState state = new UpdateState(dir.resolve(FILE_NAME));

        try (InputStream in = Files.newInputStream(state.file)) {
            Data data = M.readValue(in, Data.class);
            state.installedVersion = clean(data.installedVersion);
            state.dismissedVersion = clean(data.dismissedVersion);
            state.pendingInstall = clean(data.pendingInstall);
            state.lastCheck = data.lastCheckMillis > 0
                    ? Instant.ofEpochMilli(data.lastCheckMillis)
                    : Instant.EPOCH;
            return state;
        } catch (Exception ignored) {
            // Sin archivo previo o ilegible.
        }

        // Formato anterior (.properties): se lee y se convierte sin perder nada.
        state.loadLegacy(dir.resolve(LEGACY_FILE_NAME));
        return state;
    }

    private void loadLegacy(Path legacy) {
        try (InputStream in = Files.newInputStream(legacy)) {
            Properties p = new Properties();
            p.load(in);
            installedVersion = clean(p.getProperty(KEY_INSTALLED));
            dismissedVersion = clean(p.getProperty(KEY_DISMISSED));
        } catch (Exception ignored) {
            // No existía: estado vacío.
        }
    }

    private static String clean(String v) {
        return v == null ? "" : v.trim();
    }

    /** Versión cuyo instalador ya se lanzó, o vacío. */
    public String installedVersion() {
        return installedVersion;
    }

    /** Versión que el usuario descartó, o vacío. */
    public String dismissedVersion() {
        return dismissedVersion;
    }

    /** Cuándo se comprobó la API por última vez, si alguna vez. */
    public Optional<Instant> lastCheck() {
        return lastCheck.equals(Instant.EPOCH) ? Optional.empty() : Optional.of(lastCheck);
    }

    /**
     * true si el instalador de esta versión ya se lanzó. Se respeta siempre: reintentar algo
     * que el usuario ya instaló solo genera el loop que acabamos de arreglar.
     */
    public boolean isInstalled(String normalizedVersion) {
        return !clean(normalizedVersion).isEmpty() && clean(normalizedVersion).equals(installedVersion);
    }

    /** true si el usuario cerró el banner de esta versión. */
    public boolean isDismissed(String normalizedVersion) {
        return !clean(normalizedVersion).isEmpty() && clean(normalizedVersion).equals(dismissedVersion);
    }

    /** Marca que el instalador de {@code version} ya se lanzó. */
    public void markInstalled(String version) {
        installedVersion = clean(version);
        save();
    }

    /** Marca que el usuario descartó la actualización a {@code version}. */
    public void markDismissed(String version) {
        dismissedVersion = clean(version);
        save();
    }

    // ── Instalación en curso: se resuelve al siguiente arranque ────────────────────

    /**
     * Registra que se lanzó el instalador de {@code version} y que aún no se sabe si
     * terminó bien. El launcher se cierra justo después, así que esta es la única manera
     * de que el siguiente arranque sepa que hay una instalación sin verificar.
     */
    public void markInstallPending(String version) {
        pendingInstall = clean(version);
        save();
    }

    /** Versión destino de la instalación sin verificar, o vacío. */
    public String pendingInstall() {
        return pendingInstall;
    }

    /** ¿Queda alguna instalación lanzada cuyo resultado sigue sin comprobarse? */
    public boolean hasPendingInstall() {
        return !pendingInstall.isEmpty();
    }

    /**
     * Resuelve la instalación pendiente al arrancar y dice qué pasó.
     *
     * @param currentVersion versión real del launcher ahora mismo.
     * @return qué pasó con esa instalación.
     */
    public InstallOutcome resolvePendingInstall(String currentVersion) {
        String target = pendingInstall;
        if (target.isEmpty()) {
            return InstallOutcome.NONE;
        }

        String current = clean(currentVersion);
        // Se limpia siempre: si no se limpia, el aviso se repite en cada arranque.
        pendingInstall = "";
        save();

        return target.equals(current) ? InstallOutcome.SUCCEEDED : InstallOutcome.FAILED;
    }

    /** Borra la instalación pendiente sin informar (p. ej. el usuario la canceló). */
    public void clearPendingInstall() {
        if (pendingInstall.isEmpty()) {
            return;
        }
        pendingInstall = "";
        save();
    }

    /** Resultado de comparar la instalación pendiente con la versión real. */
    public enum InstallOutcome {
        /** No había ninguna instalación pendiente. */
        NONE,
        /** El launcher ya está en la versión destino: la instalación funcionó. */
        SUCCEEDED,
        /** Se lanzó un instalador pero la versión no cambió: falló o se canceló. */
        FAILED
    }

    /** Registra que se consultó la API, para no repetir el check en cada arranque. */
    public void markChecked(Instant when) {
        lastCheck = when == null ? Instant.EPOCH : when;
        save();
    }

    /** Limpia el estado de una versión al terminar la instalación (deja de estar pendiente). */
    public void clearPending(String version) {
        String v = clean(version);
        if (v.equals(dismissedVersion)) {
            dismissedVersion = "";
            save();
        }
    }

    /**
     * Guarda de forma atómica: primero en {@code .tmp} y luego move. Si el launcher se cierra
     * a mitad de la escritura, el archivo anterior sigue siendo válido.
     */
    private void save() {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());

            Data data = new Data();
            data.installedVersion = installedVersion;
            data.dismissedVersion = dismissedVersion;
            data.pendingInstall = pendingInstall;
            data.lastCheckMillis = lastCheck.equals(Instant.EPOCH) ? 0L : lastCheck.toEpochMilli();

            Files.writeString(tmp, M.writeValueAsString(data), StandardCharsets.UTF_8);

            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // Sin persistencia no se rompe nada: el check sigue comparando versiones.
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored2) {
                // Nada que hacer.
            }
        }
    }
}