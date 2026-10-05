package com.experimento.launcher.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El override de pruebas: {@code -Dmeacore.update.manifest} / {@code MEACORE_UPDATE_MANIFEST}.
 *
 * <p>Existe para poder ensayar el ciclo N -> N+1 completo sin publicar una release estable
 * que llegue a los usuarios reales. Al ser un mecanismo pensado para desarrollo, lo que más
 * importa no es que funcione, sino que un valor mal escrito <em>no rompa el launcher</em>:
 * por eso se comprueba que se ignoran las rutas locales y los typos.</p>
 */
class ManifestOverrideTest {

    private static final String PROPERTY = "meacore.update.manifest";

    private String previous;

    /** El servicio guarda el listener en estático: se restaura al terminar cada test. */
    private final AtomicReference<AutoUpdateService.UpdateListener> listenerRef = new AtomicReference<>();

    @BeforeEach
    void save() {
        previous = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
    }

    @AfterEach
    void restore() {
        if (previous == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, previous);
        }
    }

    @Test
    @DisplayName("Sin override no hay ninguno: el launcher usa la API y el Worker")
    void noOverrideByDefault() {
        assertNull(AutoUpdateService.manifestOverride());
        assertFalse(AutoUpdateService.hasManifestOverride());
    }

    @Test
    @DisplayName("La propiedad de sistema se respeta")
    void systemPropertyIsUsed() {
        System.setProperty(PROPERTY, "http://127.0.0.1:8099/latest.json");

        assertEquals("http://127.0.0.1:8099/latest.json", AutoUpdateService.manifestOverride());
        assertTrue(AutoUpdateService.hasManifestOverride());
    }

    @Test
    @DisplayName("Se recortan espacios: copiar/pegar en una terminal mete spaces de más")
    void surroundingWhitespaceIsTrimmed() {
        System.setProperty(PROPERTY, "  https://example.test/latest.json \n");

        assertEquals("https://example.test/latest.json", AutoUpdateService.manifestOverride());
    }

    @Test
    @DisplayName("Un valor vacío o en blanco cuenta como no configurado")
    void blankIsTreatedAsAbsent() {
        System.setProperty(PROPERTY, "   ");
        assertNull(AutoUpdateService.manifestOverride());

        System.setProperty(PROPERTY, "");
        assertNull(AutoUpdateService.manifestOverride());
    }

    @Test
    @DisplayName("Un valor que no es http/https se IGNORA en vez de romper el check")
    void nonHttpOverrideIsIgnored() {
        // El caso peligroso: apuntar a un fichero local o escribir la ruta equivocada. Si se
        // aceptara, el launcher haria fetch a un sitio invalido y el check fallaria siempre.
        for (String bad : new String[]{
                "/tmp/latest.json",
                "file:///tmp/latest.json",
                "latest.json",
                "ftp://example.test/latest.json",
                "C:\\temp\\latest.json"}) {
            System.setProperty(PROPERTY, bad);
            assertNull(AutoUpdateService.manifestOverride(), "debería ignorar: " + bad);
            assertFalse(AutoUpdateService.hasManifestOverride(), bad);
        }
    }

    @Test
    @DisplayName("El override gana a la API de GitHub: es la única fuente que se consulta")
    void overrideShortCircuitsTheOtherSources() throws Exception {
        // Comprobación real, no por inspección: con el override puesto, el check tiene que
        // servirse del manifest local SIN tocar api.github.com ni el Worker. Antes de este
        // test, la implementación consultaba la API primero y luego miraba el override, así
        // que gastaba cuota y dependía de GitHub para una prueba que debía ser offline.
        var hits = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest.json", exchange -> {
            hits.add(exchange.getRequestURI().getPath());
            // Ambos formatos: el launcher elige por SO, y un manifest con solo .exe se
            // responde con "no hay instalador para tu sistema" en lugar de ofrecer la
            // actualización. Un manifest de pruebas debe traer los dos.
            String digest = "sha256:" + "0".repeat(64);
            String body = """
                    {"tag_name":"v99.0.0","assets":[
                      {"name":"meacore-launcher-99.0.0.exe",
                       "browser_download_url":"http://127.0.0.1/instalador.exe",
                       "size":10,"digest":"%s"},
                      {"name":"meacore-launcher_99.0.0_amd64.deb",
                       "browser_download_url":"http://127.0.0.1/instalador.deb",
                       "size":10,"digest":"%s"}]}
                    """.formatted(digest, digest);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            try (var os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        server.start();
        AutoUpdateService.UpdateListener previousListener = currentListener();
        try {
            System.setProperty(PROPERTY, "http://127.0.0.1:" + server.getAddress().getPort() + "/latest.json");

            var done = new java.util.concurrent.CountDownLatch(1);
            var offered = new AtomicReference<String>();
            listenerRef.set(new AutoUpdateService.UpdateListener() {
                @Override public void onUpdateFound(String version, String url) { offered.set(version); }
                @Override public void onDownloadProgress(double f) {}
                @Override public void onDownloadComplete(java.nio.file.Path p) {}
                @Override public void onDownloadError(String m) {}
                @Override public void onCheckFinished() { done.countDown(); }
            });
            AutoUpdateService.setListener(listenerRef.get());

            AutoUpdateService.checkForUpdatesAsync(true);
            assertTrue(done.await(20, java.util.concurrent.TimeUnit.SECONDS),
                    "el check con override no terminó: " + offered.get());

            // Lo que demuestra: el manifest local se usó y se ofreció la actualización.
            assertEquals("99.0.0", offered.get(), "debería ofrecer lo que dice el manifest local");
            assertEquals(List.of("/latest.json"), hits, "solo debe pedir el manifest del override");
        } finally {
            AutoUpdateService.setListener(previousListener);
            server.stop(0);
        }
    }

    @Test
    @DisplayName("El estado de descarga no queda pegado entre pruebas")
    void downloadGuardIsReleased() throws Exception {
        // Guarda contra tests futuros: si un test deja downloading=true, los siguientes
        // descartan su descarga en silencio y fallan "sin motivo".
        Field f = AutoUpdateService.class.getDeclaredField("downloading");
        f.setAccessible(true);
        var guard = (AtomicBoolean) f.get(null);
        assertFalse(guard.get(), "la guarda de descarga debería estar libre");
    }

    /** El listener vive en un campo estático sin getter: se lee por reflexión. */
    private static AutoUpdateService.UpdateListener currentListener() throws Exception {
        Field f = AutoUpdateService.class.getDeclaredField("listener");
        f.setAccessible(true);
        return (AutoUpdateService.UpdateListener) f.get(null);
    }

    // ── Estado tras lanzar el instalador ──────────────────────────────────────────

    /**
     * installFromPath() llama a System.exit, así que no se puede probar en proceso. Se prueba
     * el seam que ejecuta justo antes: si alguien lo borrara, esta prueba falla.
     */
    @Test
    @DisplayName("Al lanzar el instalador queda pendiente de verificar Y marcada como instalada")
    void recordInstallLaunchedMarksBothPendingAndInstalled(@TempDir Path dir) {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", dir.toString());
        try {
            AutoUpdateService.recordInstallLaunched("1.6.2");

            UpdateState state = UpdateState.load(dir.resolve(".cache").resolve("meacore").resolve("updates"));
            assertTrue(state.hasPendingInstall(), "debe quedar pendiente de verificar");
            assertEquals("1.6.2", state.pendingInstall());
            assertTrue(state.isInstalled("1.6.2"),
                    "marcada como instalada es lo que evita que se vuelva a ofrecer sola");
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    @DisplayName("Al volver a la versión antigua se avisa de que la instalación no se completó")
    void returningToTheOldVersionFiresTheIncompleteCallback(@TempDir Path dir) {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", dir.toString());
        try {
            AutoUpdateService.recordInstallLaunched("1.6.2");

            var fired = new AtomicBoolean();
            AutoUpdateService.UpdateListener listener = new AutoUpdateService.UpdateListener() {
                @Override public void onUpdateFound(String v, String u) {}
                @Override public void onDownloadProgress(double f) {}
                @Override public void onDownloadComplete(java.nio.file.Path p) {}
                @Override public void onDownloadError(String m) {}
                @Override public void onInstallIncomplete() { fired.set(true); }
            };
            AutoUpdateService.setListener(listener);
            try {
                // La UI pasa la versión con prefijo "v": si no se normalizara, compararía
                // "v1.6.1" contra "1.6.2" y siempre daría FAILED, incluso siendo un éxito.
                UpdateState.InstallOutcome ok =
                        AutoUpdateService.resolvePendingInstallOnStartup("v1.6.2");
                assertEquals(UpdateState.InstallOutcome.SUCCEEDED, ok);
                assertFalse(fired.get(), "una instalación correcta no debe avisar de fallo");

                AutoUpdateService.recordInstallLaunched("1.6.2");
                UpdateState.InstallOutcome ko =
                        AutoUpdateService.resolvePendingInstallOnStartup("v1.6.1");
                assertEquals(UpdateState.InstallOutcome.FAILED, ko);
                assertTrue(fired.get(), "debe avisar de que la actualización no se completó");
            } finally {
                AutoUpdateService.setListener(null);
            }
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }
}