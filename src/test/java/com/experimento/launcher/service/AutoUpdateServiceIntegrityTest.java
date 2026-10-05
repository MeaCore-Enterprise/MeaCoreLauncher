package com.experimento.launcher.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integridad de extremo a extremo: lo que el launcher hace con un instalador real servido por
 * HTTP, no la funcion {@code verifySha256} aislada (eso lo cubre {@code AutoUpdateServiceTest}).
 *
 * <p>El escenario que comprueba: el binario que llega al disco tiene que ser exactamente el que
 * el publicador endorso. Si alguien sirve otro binario -un CDN comprometido, un asset
 * sustituido, un mirror malicioso, un {@code .exe} truncado con el nombre correcto- el launcher
 * no debe llegar a {@link AutoUpdateService.UpdateListener#onDownloadComplete}, porque de ese callback cuelga el
 * boton "Instalar".</p>
 */
class AutoUpdateServiceIntegrityTest {

    private static final byte[] PAYLOAD = "MeaCore-Installer-Contenido-De-Prueba".getBytes();

    private HttpServer server;
    private String baseUrl;
    private String realSha256;
    private String wrongSha256;

    /** user.home se redirige al temporal: updatesDir() cuelga de ahi en Linux/macOS. */
    private final String originalUserHome = System.getProperty("user.home");

    @TempDir
    Path tmpHome;

    @BeforeEach
    void startServer() throws Exception {
        realSha256 = "sha256:" + sha256Hex(PAYLOAD);
        // Mismo formato que devuelve la API/latest.json, pero el digest de otro binario.
        wrongSha256 = "sha256:" + sha256Hex("Otro instalador malicioso".getBytes());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/installer.exe", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, PAYLOAD.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(PAYLOAD);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/installer.exe";

        System.setProperty("user.home", tmpHome.toString());
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        System.setProperty("user.home", originalUserHome);
    }

    // ── El caso que importa ────────────────────────────────────────────────────────

    @Test
    @DisplayName("SHA-256 que no coincide con el publicado: no se completa ni queda instalador")
    void mismatchedDigestIsRejectedAndNothingInstallableIsLeft() throws Exception {
        Recorder rec = new Recorder();
        AutoUpdateService.setListener(rec);
        try {
            AutoUpdateService.downloadAndInstallAsync(baseUrl, wrongSha256, (long) PAYLOAD.length);

            assertTrue(rec.awaitTerminal(15_000), "no llego ningun evento terminal");

            // La asercion clave: nunca se ofrece instalar. Si esto pasa, el SHA-256 es decorativo.
            assertFalse(rec.completed.get(), "onDownloadComplete no debe firing con digest incorrecto");
            assertFalse(rec.installsAllowed.get(), "no debe quedar ninguna ruta de instalacion");

            assertEquals(1, rec.errors.size(), "debe informar del error: " + rec.errors);
            String msg = rec.errors.getFirst();
            assertTrue(msg.contains("corrupto"),
                    "el mensaje debe explicar que el archivo esta corrupto, no un error generico: " + msg);

            // Y no debe quedar nada ejecutable en disco: ni el destino, ni el .part.
            Path updatesDir = Path.of(tmpHome.toString(), ".cache", "meacore", "updates");
            assertTrue(Files.isDirectory(updatesDir), "deberia existir el directorio de descargas");
            try (var files = Files.list(updatesDir)) {
                List<Path> leftovers = files.toList();
                assertTrue(leftovers.stream().noneMatch(p -> p.getFileName().toString().endsWith(".exe")),
                        "no debe quedar ningun .exe (ni completo ni .part): " + leftovers);
            }
        } finally {
            AutoUpdateService.setListener(null);
            awaitDownloadGuardFree();
        }
    }

    @Test
    @DisplayName("SHA-256 correcto: se completa y el archivo en disco es el publicado")
    void correctDigestIsAccepted() throws Exception {
        Recorder rec = new Recorder();
        AutoUpdateService.setListener(rec);
        try {
            AutoUpdateService.downloadAndInstallAsync(baseUrl, realSha256, (long) PAYLOAD.length);

            assertTrue(rec.awaitTerminal(15_000), "no llego ningun evento terminal");
            assertTrue(rec.completed.get(), "con digest correcto debe completarse: errores=" + rec.errors);
            assertTrue(rec.errors.isEmpty(), "no debe haber errores: " + rec.errors);
            assertTrue(rec.installsAllowed.get(), "debe habilitarse la instalacion");

            Path out = rec.downloaded.get();
            assertNotNull(out);
            assertTrue(Arrays.equals(PAYLOAD, Files.readAllBytes(out)),
                    "el archivo publicado debe ser byte a byte el que sirvio el servidor");
        } finally {
            AutoUpdateService.setListener(null);
            awaitDownloadGuardFree();
        }
    }

    @Test
    @DisplayName("latest.json sin digest: no se instala automaticamente, se ofrece descarga manual")
    void missingDigestNeverInstallsAutomatically() throws Exception {
        Recorder rec = new Recorder();
        AutoUpdateService.setListener(rec);
        try {
            // Asi es como el Worker sirve el manifest si un proxy borrase el campo digest:
            // el launcher NO puede verificar, y por tanto no puede afirmar que sea integro.
            AutoUpdateService.downloadAndInstallAsync(baseUrl, null, (long) PAYLOAD.length);

            assertTrue(rec.awaitTerminal(15_000), "no llego ningun evento terminal");
            assertTrue(rec.unverified.get(), "debe ofrecerse la descarga manual");
            assertFalse(rec.completed.get(), "sin digest NUNCA debe completar la instalacion");
            assertFalse(rec.installsAllowed.get(), "sin digest no debe quedar ruta de instalacion automatica");
            assertEquals(realSha256, sha256Of(rec.unverifiedFile.get()),
                    "el archivo senalado al usuario debe ser el realmente descargado, para que "
                            + "pueda compararlo o descargarlo de otro sitio");
        } finally {
            AutoUpdateService.setListener(null);
            awaitDownloadGuardFree();
        }
    }

    @Test
    @DisplayName("Digest sin prefijo sha256: se acepta igualmente (normalizacion)")
    void bareHexDigestIsAccepted() throws Exception {
        Recorder rec = new Recorder();
        AutoUpdateService.setListener(rec);
        try {
            String bare = realSha256.substring("sha256:".length());
            AutoUpdateService.downloadAndInstallAsync(baseUrl, bare, (long) PAYLOAD.length);

            assertTrue(rec.awaitTerminal(15_000), "no llego ningun evento terminal");
            assertTrue(rec.completed.get(), "un hex sin prefijo es valido: errores=" + rec.errors);
        } finally {
            AutoUpdateService.setListener(null);
            awaitDownloadGuardFree();
        }
    }

    @Test
    @DisplayName("Tamano declarado menor que el real: se rechaza (no se instala un asset truncado)")
    void declaredSizeBelowRealSizeIsRejected() throws Exception {
        Recorder rec = new Recorder();
        AutoUpdateService.setListener(rec);
        try {
            AutoUpdateService.downloadAndInstallAsync(baseUrl, null, (long) PAYLOAD.length - 1);

            assertTrue(rec.awaitTerminal(20_000), "no llego ningun evento terminal");
            assertFalse(rec.completed.get(), "un instalador mas corto de lo declarado no se instala");
            assertFalse(rec.errors.isEmpty() || rec.unverified.get(),
                    "debe informar del error o marcarlo como no verificado");
        } finally {
            AutoUpdateService.setListener(null);
            awaitDownloadGuardFree();
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────────

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String sha256Of(Path p) throws Exception {
        if (p == null) return null;
        return "sha256:" + sha256Hex(Files.readAllBytes(p));
    }

    /**
     * downloadAndInstallAsync() es asincrono y usa un AtomicBoolean de guarda. El evento terminal
     * se emite antes del finally, asi que sin esta espera el test siguiente podria encontrarse
     * con la guarda todavia activa y su descarga descartada en silencio.
     */
    private static void awaitDownloadGuardFree() throws Exception {
        Field f = AutoUpdateService.class.getDeclaredField("downloading");
        f.setAccessible(true);
        var guard = (AtomicBoolean) f.get(null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (guard.get() && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(guard.get(), "la guarda de descarga quedo bloqueada");
    }

    /** Escucha los eventos y recuerda cual fue el ultimo terminal. */
    private static final class Recorder implements AutoUpdateService.UpdateListener {
        final CountDownLatch terminal = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean unverified = new AtomicBoolean();
        final AtomicBoolean installsAllowed = new AtomicBoolean();
        final AtomicReference<Path> downloaded = new AtomicReference<>();
        final AtomicReference<Path> unverifiedFile = new AtomicReference<>();
        final List<String> errors = new CopyOnWriteArrayList<>();

        boolean awaitTerminal(long ms) throws InterruptedException {
            return terminal.await(ms, TimeUnit.MILLISECONDS);
        }

        private void done() {
            terminal.countDown();
        }

        @Override public void onUpdateFound(String version, String url) {}
        @Override public void onUpToDate() { done(); }
        @Override public void onCheckError(String message) { errors.add(message); done(); }

        @Override
        public void onDownloadComplete(Path installer) {
            downloaded.set(installer);
            installsAllowed.set(true);
            completed.set(true);
            done();
        }

        @Override
        public void onDownloadError(String message) { errors.add(message); done(); }

        @Override
        public void onUnverifiedDownload(Path installer) {
            unverifiedFile.set(installer);
            unverified.set(true);
            done();
        }

        @Override public void onCooldownSkipped() { done(); }
        @Override public void onCheckFinished() {}
        @Override public void onDownloadProgress(double fraction) {}
    }
}