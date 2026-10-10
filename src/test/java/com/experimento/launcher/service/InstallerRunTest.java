package com.experimento.launcher.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La instalación no debe poder dejar el launcher en "Instalando..." para siempre. Con un
 * {@link AutoUpdateService.ProcessLauncher} falso se simulan los tres fallos del instalador
 * elevado de Linux: que no arranque, que se cuelgue (timeout) y que termine con error.
 *
 * <p>En los tres casos el launcher NO se cierra, la UI recibe {@code onInstallFailed}, la
 * guarda de instalación queda libre y {@code update.log} guarda el comando, el resultado y la
 * salida del instalador.</p>
 */
class InstallerRunTest {

    private final String originalUserHome = System.getProperty("user.home");

    @TempDir
    Path tmpHome;

    @BeforeEach
    void setUp() {
        System.setProperty("user.home", tmpHome.toString());
    }

    @AfterEach
    void tearDown() {
        // Se restaura el launcher real por si un test quedó a medias.
        AutoUpdateService.setProcessLauncher(command -> new ProcessBuilder(command).start());
        AutoUpdateService.setListener(null);
        System.setProperty("user.home", originalUserHome);
    }

    private static Path installerFile(Path dir) throws IOException {
        Path f = dir.resolve("meacore-update-1234.deb");
        Files.writeString(f, "installer");
        return f;
    }

    // ── Proceso que no arranca ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Proceso que no arranca: onInstallFailed, la guarda se libera y hay log")
    void processThatDoesNotStart() throws Exception {
        AutoUpdateService.setProcessLauncher(cmd -> {
            throw new IOException("pkexec no disponible");
        });
        Recorder rec = recordListener();

        AutoUpdateService.installFromPath(installerFile(tmpHome), 300);

        assertTrue(rec.awaitTerminal(15_000), "no llegó el fallo");
        assertEquals(1, rec.failures.size(), "debe avisar de que no se pudo lanzar: " + rec.failures);
        assertInstallingFree();

        String log = updateLog();
        assertTrue(log.contains("comando:"), "debe loguear el comando exacto: " + log);
        assertTrue(log.contains("NOT_STARTED"), "debe loguear el resultado: " + log);
    }

    // ── Proceso que se cuelga ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Proceso que se cuelga: timeout, se fuerza el cierre y la UI recibe el fallo")
    void processThatHangsTimesOut() throws Exception {
        AutoUpdateService.setProcessLauncher(cmd -> new FakeResultProcess(new byte[0], new byte[0], -1, true));
        Recorder rec = recordListener();

        AutoUpdateService.installFromPath(installerFile(tmpHome), 2);

        assertTrue(rec.awaitTerminal(15_000), "el timeout debe disparar el fallo");
        assertEquals(1, rec.failures.size(), "debe avisarse de que no terminó a tiempo: " + rec.failures);
        assertTrue(rec.failures.getFirst().contains("no terminó a tiempo"), rec.failures.getFirst());
        assertInstallingFree();

        String log = updateLog();
        assertTrue(log.contains("timeout"), "debe loguear el timeout: " + log);
        assertTrue(log.contains("resultado: TIMEOUT"), log);
    }

    // ── Proceso que termina con error ───────────────────────────────────────────────

    @Test
    @DisplayName("Instalador que sale con código != 0: error con el código y la salida en el log")
    void processExitsWithError() throws Exception {
        byte[] output = "E: no se pudo resolver las dependencias\nFallo de dpkg".getBytes();
        AutoUpdateService.setProcessLauncher(cmd -> new FakeResultProcess(output, new byte[0], 1, false));
        Recorder rec = recordListener();

        AutoUpdateService.installFromPath(installerFile(tmpHome), 300);

        assertTrue(rec.awaitTerminal(15_000), "no llegó el fallo");
        assertEquals(1, rec.failures.size(), rec.failures.toString());
        assertTrue(rec.failures.getFirst().contains("código 1"), rec.failures.getFirst());
        assertInstallingFree();

        String log = updateLog();
        assertTrue(log.contains("resultado: FAILED (exit 1)"), log);
        assertTrue(log.contains("dependencias"), "el log debe tener la salida del instalador: " + log);
    }

    // ── Instalador que termina bien (a nivel de comando, sin cerrar la JVM) ─────────

    @Test
    @DisplayName("runElevatedCommand: éxito captura la salida y devuelve SUCCESS")
    void elevatedCommandSuccessIsDetected() {
        AutoUpdateService.ProcessLauncher launcher =
                cmd -> new FakeResultProcess("Instalado correctamente\n".getBytes(), new byte[0], 0, false);
        StringBuilder out = new StringBuilder();

        AutoUpdateService.InstallToolOutcome o =
                AutoUpdateService.runElevatedCommand(List.of("/usr/bin/pkexec", "/usr/bin/apt", "-y", "/tmp/x.deb"),
                        launcher, 300, out);

        assertEquals(AutoUpdateService.InstallResult.SUCCESS, o.result());
        assertEquals(0, o.exitCode());
        assertTrue(out.toString().contains("Instalado correctamente"),
                "la salida del instalador se captura: " + out);
    }

    // ── Cierre del método contract ──────────────────────────────────────────────────

    private static final class Recorder implements AutoUpdateService.UpdateListener {
        final CountDownLatch terminal = new CountDownLatch(1);
        final List<String> failures = new CopyOnWriteArrayList<>();

        boolean awaitTerminal(long ms) throws InterruptedException {
            return terminal.await(ms, TimeUnit.MILLISECONDS);
        }

        @Override public void onInstallFailed(String message) {
            failures.add(message);
            terminal.countDown();
        }

        @Override public void onUpdateFound(String v, String u) {}
        @Override public void onDownloadProgress(double f) {}
        @Override public void onDownloadComplete(Path p) {}
        @Override public void onDownloadError(String m) {}
    }

    private static Recorder recordListener() {
        Recorder rec = new Recorder();
        AutoUpdateService.setListener(rec);
        return rec;
    }

    private static Path updateLogPath() {
        return Path.of(System.getProperty("user.home"), ".cache", "meacore", "updates", "update.log");
    }

    private static String updateLog() throws IOException {
        return Files.readString(updateLogPath());
    }

    /** La guarda de instalación se libera SIEMPRE: es lo que evita el "Instalando..." eterno. */
    private static void assertInstallingFree() throws Exception {
        Field f = AutoUpdateService.class.getDeclaredField("installing");
        f.setAccessible(true);
        AtomicBoolean guard = (AtomicBoolean) f.get(null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (guard.get() && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(guard.get(), "la guarda de instalación quedó bloqueada");
    }

    /**
     * Proceso pretendido para las pruebas: no lanza nada de verdad, solo reporta el
     * comportamiento que le programemos. {@code hang}=true hace que {@code waitFor} nunca
     * termine hasta que el servicio destruya el proceso.
     */
    private static final class FakeResultProcess extends Process {
        private final byte[] stdout;
        private final byte[] stderr;
        private final int exitCode;
        private final boolean hang;
        private volatile boolean destroyed;

        FakeResultProcess(byte[] stdout, byte[] stderr, int exitCode, boolean hang) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
            this.hang = hang;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(stdout); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(stderr); }

        @Override
        public int waitFor() throws InterruptedException {
            return waitFor(0, TimeUnit.SECONDS) ? exitCode : -1;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return !hang || destroyed;
        }

        @Override
        public int exitValue() {
            if (hang && !destroyed) throw new IllegalThreadStateException("process not exited");
            return exitCode;
        }

        @Override public void destroy() { destroyed = true; }
        @Override public Process destroyForcibly() { destroyed = true; return this; }
        @Override public boolean isAlive() { return hang && !destroyed; }
    }
}