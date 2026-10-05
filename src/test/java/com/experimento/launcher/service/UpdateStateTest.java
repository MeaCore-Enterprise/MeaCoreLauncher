package com.experimento.launcher.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateStateTest {

    @Test
    void missingFileLoadsEmptyState(@TempDir Path dir) {
        UpdateState state = UpdateState.load(dir);
        assertEquals("", state.installedVersion());
        assertEquals("", state.dismissedVersion());
        assertFalse(state.isInstalled("1.6.2"));
        assertFalse(state.isDismissed("1.6.2"));
    }

    @Test
    void installedVersionSurvivesReload(@TempDir Path dir) {
        UpdateState.load(dir).markInstalled("1.6.2");
        assertTrue(UpdateState.load(dir).isInstalled("1.6.2"));
    }

    @Test
    void dismissedVersionSurvivesReload(@TempDir Path dir) {
        UpdateState.load(dir).markDismissed("1.6.2");
        assertTrue(UpdateState.load(dir).isDismissed("1.6.2"));
    }

    @Test
    void onlyTheSameVersionIsBlocked(@TempDir Path dir) {
        UpdateState state = UpdateState.load(dir);
        state.markInstalled("1.6.2");
        state.markDismissed("1.6.3");

        assertTrue(state.isInstalled("1.6.2"));
        assertTrue(state.isDismissed("1.6.3"));
        // Una versión posterior sí se sigue ofreciendo.
        assertFalse(state.isInstalled("1.6.4"));
        assertFalse(state.isDismissed("1.6.4"));
    }

    @Test
    void installedAndDismissedAreTrackedSeparately(@TempDir Path dir) {
        UpdateState state = UpdateState.load(dir);
        state.markInstalled("1.6.1");
        state.markDismissed("1.6.2");

        assertEquals("1.6.1", state.installedVersion());
        assertEquals("1.6.2", state.dismissedVersion());
        assertTrue(state.isInstalled("1.6.1"));
        assertFalse(state.isDismissed("1.6.1"));
    }

    @Test
    void blankInputNeverMatches(@TempDir Path dir) {
        UpdateState state = UpdateState.load(dir);
        state.markInstalled("1.6.2");
        assertFalse(state.isInstalled(""));
        assertFalse(state.isInstalled("   "));
        assertFalse(state.isInstalled(null));
        assertFalse(state.isDismissed(null));
    }

    @Test
    void clearPendingOnlyClearsTheDismissal(@TempDir Path dir) {
        UpdateState state = UpdateState.load(dir);
        state.markInstalled("1.6.1");
        state.markDismissed("1.6.2");

        state.clearPending("1.6.1");
        assertEquals("1.6.2", state.dismissedVersion(), "no debe tocar el descarte de otra versión");

        state.clearPending("1.6.2");
        assertEquals("", state.dismissedVersion());
        assertEquals("1.6.1", UpdateState.load(dir).installedVersion(), "la instalación se conserva");
    }

    @Test
    void unreadableDirectoryDoesNotThrow(@TempDir Path dir) {
        // dir/archivo es un archivo, no un directorio: guardar debe fallar en silencio.
        UpdateState state = UpdateState.load(dir.resolve("sub").resolve("file").resolve("deep"));
        state.markInstalled("1.6.2");
        assertEquals("1.6.2", state.installedVersion(), "el estado en memoria se mantiene");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2.1: JSON + migración desde el formato .properties anterior
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void stateIsStoredAsJson(@TempDir Path dir) throws Exception {
        UpdateState.load(dir).markInstalled("1.6.2");

        Path json = dir.resolve("update-state.json");
        assertTrue(Files.exists(json), "el estado debe vivir en update-state.json");

        String content = Files.readString(json);
        assertTrue(content.contains("\"installedVersion\""), content);
        assertTrue(content.contains("1.6.2"), content);
        // Nada de archivos .properties nuevos.
        assertFalse(Files.exists(dir.resolve("update-state.properties")));
    }

    @Test
    void noTemporaryFileIsLeftBehind(@TempDir Path dir) {
        UpdateState.load(dir).markDismissed("1.6.2");
        assertFalse(Files.exists(dir.resolve("update-state.json.tmp")),
                "la escritura atómica no debe dejar el temporal");
    }

    /** Quien(actualiza el launcher no debe perder sus descartes al pasar de .properties a JSON. */
    @Test
    void legacyPropertiesAreStillHonoured(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("update-state.properties"),
                "installed.version=1.6.1\ndismissed.version=1.6.2\n");

        UpdateState state = UpdateState.load(dir);

        assertEquals("1.6.1", state.installedVersion());
        assertEquals("1.6.2", state.dismissedVersion());
        assertTrue(state.isInstalled("1.6.1"));
        assertTrue(state.isDismissed("1.6.2"));
    }

    /** Al cargar el formato antiguo y volver a guardar, ya se escribe en JSON. */
    @Test
    void legacyPropertiesAreConvertedToJson(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("update-state.properties"),
                "installed.version=1.6.1\ndismissed.version=1.6.2\n");

        UpdateState state = UpdateState.load(dir);
        state.markChecked(Instant.parse("2026-01-02T03:04:05Z"));

        String json = Files.readString(dir.resolve("update-state.json"));
        assertTrue(json.contains("1.6.1"), json);
        assertTrue(json.contains("1.6.2"), json);

        // El archivo antiguo se conserva: no se borra nada del usuario.
        assertTrue(Files.exists(dir.resolve("update-state.properties")));
        assertTrue(UpdateState.load(dir).isDismissed("1.6.2"));
    }

    /** Si ya hay JSON, gana sobre el .properties antiguo. */
    @Test
    void jsonWinsOverLegacyProperties(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("update-state.properties"), "installed.version=0.0.1\n");
        UpdateState.load(dir).markInstalled("1.6.9");

        assertEquals("1.6.9", UpdateState.load(dir).installedVersion());
    }

    @Test
    void lastCheckSurvivesReload(@TempDir Path dir) {
        Instant when = Instant.parse("2026-05-05T10:00:00Z");
        UpdateState.load(dir).markChecked(when);

        assertEquals(Optional.of(when), UpdateState.load(dir).lastCheck());
    }

    @Test
    void neverCheckedIsEmpty(@TempDir Path dir) {
        assertTrue(UpdateState.load(dir).lastCheck().isEmpty());

        UpdateState state = UpdateState.load(dir);
        state.markInstalled("1.6.2");
        assertTrue(state.lastCheck().isEmpty(), "instalar no cuenta como comprobar");
    }

    @Test
    void corruptJsonFallsBackToEmptyState(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("update-state.json"), "{ esto no es json");

        UpdateState state = UpdateState.load(dir);

        assertEquals("", state.installedVersion());
        assertFalse(state.isInstalled("1.6.2"));
    }
}