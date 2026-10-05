package com.experimento.launcher.service;

import com.experimento.launcher.LauncherMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresión del loop de auto-actualización a nivel del servicio real.
 *
 * <p>El bug original comparaba el {@code tag_name} crudo de GitHub (que siempre lleva prefijo
 * "v") contra {@code version.properties} (que nunca lo lleva), así que una instalación ya
 * actualizada se detectaba a sí misma como actualización nueva en cada check.
 */
class AutoUpdateServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Nombre de asset que el servicio busca en la plataforma actual. */
    private static String assetName() {
        return isWindows() ? "MeaCoreLauncher-1.6.2.exe" : "meacore-launcher_1.6.2_amd64.deb";
    }

    /**
     * Invoca la decisión real del servicio con un tag y un asset fabricados, aislando el
     * directorio de estado en un temporal vía {@code user.home}.
     *
     * @return eventos emitidos hacia el listener, en orden.
     */
    private static List<String> evaluate(String tagName, Path fakeHome, boolean force) throws Exception {
        return evaluate(tagName, fakeHome, force, null);
    }

    /**
     * Igual que {@link #evaluate(String, Path, boolean)} pero permitiendo declarar el
     * {@code digest} y el {@code size} del asset, como los publica la API de GitHub.
     */
    private static List<String> evaluate(String tagName, Path fakeHome, boolean force,
                                         String digest) throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        try {
            Method m = AutoUpdateService.class
                    .getDeclaredMethod("evaluateAndNotify", String.class, JsonNode.class, boolean.class);
            m.setAccessible(true);

            // El servicio guarda el digest/tamaño en campos estáticos: se limpian para que
            // cada test sea independiente del anterior.
            setPendingIntegrity(null, null);

            StringBuilder asset = new StringBuilder("{\"name\":\"" + assetName()
                    + "\",\"browser_download_url\":\"https://example.invalid/installer\"");
            if (digest != null) {
                asset.append(",\"digest\":\"").append(digest).append("\"");
            }
            asset.append("}");
            JsonNode assets = M.readTree("[" + asset + "]");

            List<String> events = new ArrayList<>();
            AutoUpdateService.setListener(new AutoUpdateService.UpdateListener() {
                @Override public void onUpdateFound(String version, String url) {
                    events.add("found:" + version);
                }
                @Override public void onDownloadProgress(double fraction) {}
                @Override public void onDownloadComplete(Path installerPath) {}
                @Override public void onDownloadError(String message) { events.add("error"); }
                @Override public void onUpToDate() { events.add("uptodate"); }
            });

            m.invoke(null, tagName, assets, force);
            return events;
        } finally {
            AutoUpdateService.setListener(null);
            System.setProperty("user.home", originalHome);
        }
    }

    private static void setPending(String field, Object value) throws Exception {
        Field f = AutoUpdateService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static Object getPending(String field) throws Exception {
        Field f = AutoUpdateService.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setPendingIntegrity(String digest, Long size) throws Exception {
        setPending("pendingDigest", digest);
        setPending("pendingSize", size);
    }

    private static String callPrivate(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = AutoUpdateService.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return (String) m.invoke(null, args);
    }

    /** Espejo de AutoUpdateService.updatesDir() para un user.home falso (solo Linux). */
    private static Path stateDir(Path fakeHome) {
        return fakeHome.resolve(".cache").resolve("meacore").resolve("updates");
    }

    /** Ya instalado y con el tag "v" delante (como hace GitHub): NO debe ofrecerse. */
    @Test
    void sameVersionWithTagPrefixIsNotOffered(@TempDir Path dir) throws Exception {
        List<String> events = evaluate("v" + LauncherMetadata.VERSION, dir, false);
        assertFalse(events.stream().anyMatch(e -> e.startsWith("found:")),
                "no debe ofrecer actualización siendo la misma versión: " + events);
        assertEquals(List.of("uptodate"), events);
    }

    /** Mismo caso con el prefijo "bat-" de los tags de build de Windows. */
    @Test
    void sameVersionWithBatPrefixIsNotOffered(@TempDir Path dir) throws Exception {
        List<String> events = evaluate("bat-" + LauncherMetadata.VERSION, dir, false);
        assertFalse(events.stream().anyMatch(e -> e.startsWith("found:")),
                "no debe ofrecer actualización siendo la misma versión: " + events);
    }

    @Test
    void olderRemoteVersionIsNotOffered(@TempDir Path dir) throws Exception {
        List<String> events = evaluate("v0.0.1", dir, false);
        assertFalse(events.stream().anyMatch(e -> e.startsWith("found:")),
                "una versión remota más antigua no es actualización: " + events);
    }

    @Test
    void newerRemoteVersionIsOffered(@TempDir Path dir) throws Exception {
        List<String> events = evaluate("v999.0.0", dir, false);
        assertEquals(List.of("found:999.0.0"), events);
    }

    @Test
    void malformedTagIsIgnored(@TempDir Path dir) throws Exception {
        List<String> events = evaluate("", dir, false);
        assertEquals(List.of(), events);
    }

    /**
     * Si el instalador se lanzó pero version.properties no se actualizó, la versión no debe
     * volver a ofrecerse en loop. Requiere user.home, que en Windows no es el directorio usado.
     */
    @Test
    void installedVersionIsNeverOfferedAgain(@TempDir Path dir) throws Exception {
        Assumptions.assumeFalse(isWindows(), "el estado se aísla por user.home (solo Linux)");
        UpdateState.load(stateDir(dir.resolve("home"))).markInstalled("999.0.0");

        assertEquals(List.of(), evaluate("v999.0.0", dir.resolve("home"), false));
        // Tampoco en un check manual: ya se instaló.
        assertEquals(List.of(), evaluate("v999.0.0", dir.resolve("home"), true));
    }

    /** Un descarte con la ✕ silencia los checks automáticos, pero no un check manual. */
    @Test
    void dismissedVersionOnlySilencesAutomaticChecks(@TempDir Path dir) throws Exception {
        Assumptions.assumeFalse(isWindows(), "el estado se aísla por user.home (solo Linux)");
        UpdateState.load(stateDir(dir.resolve("home"))).markDismissed("999.0.0");

        assertEquals(List.of(), evaluate("v999.0.0", dir.resolve("home"), false));
        assertEquals(List.of("found:999.0.0"), evaluate("v999.0.0", dir.resolve("home"), true));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 0.2 / 0.5: errores de check separados de los de descarga, e integridad
    // ─────────────────────────────────────────────────────────────────────────

    /** El digest "sha256:..." del asset se guarda para verificar la descarga. */
    @Test
    void assetDigestIsCapturedForVerification(@TempDir Path dir) throws Exception {
        String digest = "sha256:2b6cd692ef4a762fa949cddd6fc8a50b9d969647d69accb8b98b518fdabf95aa";
        assertEquals(List.of("found:999.0.0"), evaluate("v999.0.0", dir, false, digest));
        assertEquals(digest, getPending("pendingDigest"));
    }

    /**
     * Si el asset no trae digest, la descarga no podrá verificarse: debe quedar en null para
     * que el servicio ofrezca la descarga manual en vez de instalar a ciegas.
     */
    @Test
    void missingDigestIsNotGuessed(@TempDir Path dir) throws Exception {
        assertEquals(List.of("found:999.0.0"), evaluate("v999.0.0", dir, false));
        assertEquals(null, getPending("pendingDigest"));
    }

    @Test
    void rateLimitHasItsOwnMessage() throws Exception {
        String forbidden = callPrivate("checkErrorMessage", new Class<?>[]{int.class}, 403);
        String tooMany   = callPrivate("checkErrorMessage", new Class<?>[]{int.class}, 429);

        assertTrue(forbidden.contains("limitando"), forbidden);
        assertTrue(tooMany.contains("limitando"), tooMany);
        assertFalse(forbidden.contains("Error al descargar"), "no debe usar el mensaje de descarga");
    }

    @Test
    void checkErrorsAreDistinguishableByStatus() throws Exception {
        assertTrue(callPrivate("checkErrorMessage", new Class<?>[]{int.class}, 404).contains("versión publicada"));
        assertTrue(callPrivate("checkErrorMessage", new Class<?>[]{int.class}, 503).contains("problemas"));
        assertTrue(callPrivate("checkErrorMessage", new Class<?>[]{int.class}, 500).contains("problemas"));
        assertTrue(callPrivate("checkErrorMessage", new Class<?>[]{int.class}, 418).contains("418"));
    }

    /** Sin red no se muestra un "código 0" al usuario. */
    @Test
    void networkFailureHasNoStatusCodeInTheMessage() throws Exception {
        String msg = callPrivate("checkErrorMessage", new Class<?>[]{int.class}, 0);
        assertFalse(msg.contains("código 0"), msg);
        assertTrue(msg.contains("conexión"), msg);
    }

    /** El respaldo del mirror está configurado: sin él, un 403 deja al usuario sin actualizaciones. */
    @Test
    void mirrorEndpointIsConfigured() throws Exception {
        java.lang.reflect.Field f = AutoUpdateService.class.getDeclaredField("MIRROR_API_LATEST");
        f.setAccessible(true);
        String mirror = (String) f.get(null);

        assertTrue(mirror.startsWith("https://"), mirror);
        assertTrue(mirror.endsWith("/api/latest-release"), mirror);
    }

    @Test
    void networkCheckErrorsAreHumanized() throws Exception {
        assertTrue(callPrivate("humanizeCheckError", new Class<?>[]{Exception.class},
                new Exception("java.net.UnknownHostException: github.com")).contains("conexión"));
        assertTrue(callPrivate("humanizeCheckError", new Class<?>[]{Exception.class},
                new Exception("request timed out")).contains("demasiado"));
        // Sin información útil: mensaje genérico, sin filtrar la excepción cruda.
        String generic = callPrivate("humanizeCheckError", new Class<?>[]{Exception.class}, new Exception("zzz"));
        assertFalse(generic.contains("zzz"), generic);
    }

    /** verifySha256 devuelve el hash solo si coincide; null significa "no verificado". */
    @Test
    void sha256VerificationAcceptsOnlyTheRightDigest(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("installer.exe");
        byte[] content = "contenido".getBytes();
        Files.write(file, content);

        String real = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));

        assertEquals(real, callPrivate("verifySha256", new Class<?>[]{Path.class, String.class},
                file, "sha256:" + real));
        assertEquals(real, callPrivate("verifySha256", new Class<?>[]{Path.class, String.class},
                file, real.toUpperCase()));
        assertEquals(null, callPrivate("verifySha256", new Class<?>[]{Path.class, String.class},
                file, "sha256:" + "0".repeat(64)));
        assertEquals(null, callPrivate("verifySha256", new Class<?>[]{Path.class, String.class},
                dir.resolve("no-existe.exe"), real));
    }
}