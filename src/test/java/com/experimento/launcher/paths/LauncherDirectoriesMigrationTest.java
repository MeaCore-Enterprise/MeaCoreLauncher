package com.experimento.launcher.paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La migración de datos de una instalación antigua al directorio por usuario es la parte
 * más delicada del arranque: si borra o pisa algo, el usuario pierde sus perfiles.
 *
 * <p>Reglas invariables: nunca se borra el origen, nunca se pisa el destino, y la copia se
 * verifica antes de darla por buena.
 */
class LauncherDirectoriesMigrationTest {

    @Test
    void noLegacyDataMeansNothingToDo(@TempDir Path tmp) throws Exception {
        Path legacy = tmp.resolve("instalacion-vieja").resolve("launcher-data");
        Path target = tmp.resolve("nuevo").resolve("launcher-data");

        LauncherDirectories.MigrationReport report = LauncherDirectories.migrate(legacy, target);

        assertEquals(0, report.copied());
        assertEquals(0, report.failed());
        assertFalse(Files.exists(target), "no debe crear el destino si no hay nada que migrar");
    }

    @Test
    void copiesLegacyDataAndKeepsTheSource(@TempDir Path tmp) throws Exception {
        Path legacyData = tmp.resolve("instalacion-vieja").resolve("launcher-data");
        Files.createDirectories(legacyData.resolve("profiles").resolve("default"));
        Files.writeString(legacyData.resolve("profiles").resolve("perfiles.json"), "{\"a\":1}");
        Files.write(legacyData.resolve("profiles").resolve("default").resolve("options.txt"), "datos".getBytes());

        Path target = tmp.resolve("nuevo").resolve("launcher-data");
        LauncherDirectories.MigrationReport report = LauncherDirectories.migrate(legacyData, target);

        assertEquals(2, report.copied());
        assertEquals(0, report.failed());
        assertEquals("{\"a\":1}", Files.readString(target.resolve("profiles").resolve("perfiles.json")));
        assertEquals("datos", Files.readString(target.resolve("profiles").resolve("default").resolve("options.txt")));

        // El origen se conserva íntegro: nada se mueve ni se borra.
        assertTrue(Files.exists(legacyData.resolve("profiles").resolve("perfiles.json")));
        assertTrue(Files.exists(legacyData.resolve("profiles").resolve("default").resolve("options.txt")));
    }

    /** El destino es sagrado: si ya tiene un archivo, se respeta y no se sobrescribe. */
    @Test
    void neverOverwritesExistingTargetFiles(@TempDir Path tmp) throws Exception {
        Path legacyData = tmp.resolve("viejo").resolve("launcher-data");
        Files.createDirectories(legacyData);
        Files.writeString(legacyData.resolve("perfiles.json"), "{\"viejo\":true}");

        Path target = tmp.resolve("nuevo").resolve("launcher-data");
        Files.createDirectories(target);
        Files.writeString(target.resolve("perfiles.json"), "{\"nuevo\":true}");

        LauncherDirectories.MigrationReport report = LauncherDirectories.migrate(legacyData, target);

        assertEquals(0, report.copied());
        assertEquals("{\"nuevo\":true}", Files.readString(target.resolve("perfiles.json")));
    }

    /** Si el destino ya tiene datos, no se copia nada (instalación ya migrada). */
    @Test
    void existingTargetIsLeftUntouched(@TempDir Path tmp) throws Exception {
        Path legacyData = tmp.resolve("viejo").resolve("launcher-data");
        Files.createDirectories(legacyData.resolve("versions"));
        Files.writeString(legacyData.resolve("versions").resolve("v1.json"), "{}");

        Path target = tmp.resolve("nuevo").resolve("launcher-data");
        Files.createDirectories(target);
        Files.writeString(target.resolve("otro-cosa.txt"), "x");

        LauncherDirectories.MigrationReport report = LauncherDirectories.migrate(legacyData, target);

        assertEquals(0, report.copied());
        assertFalse(Files.exists(target.resolve("versions").resolve("v1.json")));
        assertTrue(Files.exists(legacyData.resolve("versions").resolve("v1.json")));
    }

    @Test
    void migratingOntoItselfIsANoop(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("launcher-data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("x.txt"), "x");

        LauncherDirectories.MigrationReport report = LauncherDirectories.migrate(data, data);

        assertEquals(0, report.copied());
        assertTrue(Files.exists(data.resolve("x.txt")));
    }

    @Test
    void emptyLegacyDirectoryDoesNotReportCopies(@TempDir Path tmp) throws Exception {
        Path legacyData = tmp.resolve("viejo").resolve("launcher-data");
        Files.createDirectories(legacyData);

        LauncherDirectories.MigrationReport report = LauncherDirectories.migrate(legacyData, tmp.resolve("nuevo"));

        assertEquals(0, report.copied());
        assertEquals(0, report.failed());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resolución de rutas
    // ─────────────────────────────────────────────────────────────────────────

    /** El destino por defecto es el HOME del usuario, no el directorio desde el que se lanzó. */
    @Test
    void defaultRootIsPerUserAndNotTheWorkingDirectory(@TempDir Path tmp) throws Exception {
        String originalUserDir = System.getProperty("user.dir");
        String originalUserHome = System.getProperty("user.home");
        try {
            Path fakeInstall = Files.createDirectories(tmp.resolve("instalacion"));
            Path fakeHome = Files.createDirectories(tmp.resolve("home"));
            System.setProperty("user.dir", fakeInstall.toString());
            System.setProperty("user.home", fakeHome.toString());

            Path root = LauncherDirectories.defaultRoot();

            String xdg = System.getenv("XDG_DATA_HOME");
            if (xdg == null || xdg.isBlank()) {
                // Sin XDG_DATA_HOME: cuelga del HOME.
                assertTrue(root.startsWith(fakeHome), root + " debería estar bajo " + fakeHome);
            }
            assertFalse(root.equals(fakeInstall),
                    "los datos no deben vivir en el directorio de instalación: " + root);
        } finally {
            System.setProperty("user.dir", originalUserDir);
            System.setProperty("user.home", originalUserHome);
        }
    }

    /**
     * Prueba de extremo a extremo: al arrancar con datos en el directorio de trabajo (la
     * instalación antigua), se copian al destino por usuario y el origen sigue ahí.
     */
    @Test
    void fromDefaultMigratesLegacyDataFoundInTheWorkingDirectory(@TempDir Path tmp) throws Exception {
        String originalRoot = System.getProperty("launcher.root");
        String originalUserDir = System.getProperty("user.dir");
        String originalUserHome = System.getProperty("user.home");
        try {
            System.clearProperty("launcher.root");

            Path install = Files.createDirectories(tmp.resolve("instalacion"));
            Path legacyData = Files.createDirectories(install.resolve("launcher-data").resolve("profiles"));
            Files.writeString(legacyData.resolve("perfiles.json"), "{\"mio\":true}");

            Path home = Files.createDirectories(tmp.resolve("home"));
            System.setProperty("user.dir", install.toString());
            System.setProperty("user.home", home.toString());

            LauncherDirectories dirs = LauncherDirectories.fromDefault();

            Path copied = dirs.profilesDir().resolve("perfiles.json");
            assertTrue(Files.exists(copied), "los datos del usuario deben estar en " + dirs.profilesDir());
            assertEquals("{\"mio\":true}", Files.readString(copied));

            // El origen sigue intacto: la migración copia, nunca mueve.
            assertTrue(Files.exists(legacyData.resolve("perfiles.json")));
        } finally {
            if (originalRoot == null) {
                System.clearProperty("launcher.root");
            } else {
                System.setProperty("launcher.root", originalRoot);
            }
            System.setProperty("user.dir", originalUserDir);
            System.setProperty("user.home", originalUserHome);
        }
    }

    /** -Dlauncher.root manda sobre todo: es el modo portable/tests. */
    @Test
    void explicitRootOverrideWins(@TempDir Path tmp) throws Exception {
        String original = System.getProperty("launcher.root");
        try {
            System.setProperty("launcher.root", tmp.toString());
            LauncherDirectories dirs = LauncherDirectories.fromDefault();

            assertEquals(tmp.toAbsolutePath().normalize(), dirs.root());
            assertFalse(dirs.migration().migratedAnything(),
                    "con raíz explícita no se migra nada");
        } finally {
            if (original == null) {
                System.clearProperty("launcher.root");
            } else {
                System.setProperty("launcher.root", original);
            }
        }
    }

    @Test
    void instanceIdCannotEscapeTheProfilesDirectory() {
        LauncherDirectories dirs = new LauncherDirectories(Path.of("/tmp/x"));
        assertThrows(IllegalArgumentException.class, () -> dirs.instanceGameDir("../../etc"));
        assertThrows(IllegalArgumentException.class, () -> dirs.instanceGameDir("a/b"));
        assertThrows(IllegalArgumentException.class, () -> dirs.instanceGameDir(" "));
    }

    @Test
    void baseDirsAreCreatedUnderTheRoot(@TempDir Path tmp) throws Exception {
        LauncherDirectories dirs = new LauncherDirectories(tmp);
        dirs.ensureBaseDirs();

        assertTrue(Files.isDirectory(dirs.profilesDir()));
        assertTrue(Files.isDirectory(dirs.versionsDir()));
        assertTrue(Files.isDirectory(dirs.librariesDir()));
        assertTrue(Files.isDirectory(dirs.assetsDir()));
        assertTrue(Files.isDirectory(dirs.launcherData().resolve("runtime")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rutas de instalación por sistema
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * En Windows, jpackage con --win-per-user-install deja el launcher en
     * %LOCALAPPDATA%\Programs\MeaCore Launcher. Si no se busca ahí, los perfiles de quien
     * lanzaba con doble clic se quedan en el directorio de instalación.
     */
    @Test
    void windowsInstallDirsAreSearched(@TempDir Path tmp) throws Exception {
        Path localAppData = tmp.resolve("AppData").resolve("Local");
        Path programFiles = tmp.resolve("Program Files");

        List<Path> dirs = LauncherDirectories.knownInstallDirs("Windows 11", name -> switch (name) {
            case "LOCALAPPDATA" -> localAppData.toString();
            case "ProgramFiles" -> programFiles.toString();
            default -> null;
        });

        assertTrue(dirs.contains(localAppData.resolve("Programs").resolve("MeaCore Launcher")),
                "debe buscar la instalación per-user de jpackage: " + dirs);
        assertTrue(dirs.contains(programFiles.resolve("MeaCore Launcher")),
                "debe buscar la instalación por máquina: " + dirs);
    }

    @Test
    void linuxLooksForTheDebInstallDir() {
        List<Path> dirs = LauncherDirectories.knownInstallDirs("Linux", name -> "/home/jugador");
        assertTrue(dirs.contains(Path.of("/opt/meacore-launcher")),
                "debe buscar donde instala el .deb: " + dirs);
        assertTrue(dirs.contains(Path.of("/home/jugador/MeaCoreLauncher")),
                "instalación manual en el home: " + dirs);
    }

    @Test
    void macOsUsesTheXdgConvention(@TempDir Path tmp) {
        Path xdg = tmp.resolve("xdg");
        Path root = LauncherDirectories.defaultRoot("Mac OS X", name ->
                "XDG_DATA_HOME".equals(name) ? xdg.toString() : null, tmp.toString());
        assertEquals(xdg.resolve("MeaCoreLauncher"), root);
    }

    @Test
    void windowsDefaultsToAppData(@TempDir Path tmp) {
        Path appData = tmp.resolve("Roaming");
        Path root = LauncherDirectories.defaultRoot("Windows 10", name ->
                "APPDATA".equals(name) ? appData.toString() : null, tmp.toString());
        assertEquals(appData.resolve("MeaCoreLauncher"), root);
    }

    /** Los candidatos repetidos se eliminan: migrar dos veces el mismo sitio no aporta nada. */
    @Test
    void candidatesAreDistinct(@TempDir Path tmp) {
        Path opt = Path.of("/opt/meacore-launcher");
        List<Path> dirs = LauncherDirectories.knownInstallDirs("Linux", name ->
                "HOME".equals(name) ? opt.toString() : null);
        assertEquals(dirs.stream().distinct().count(), dirs.size(), dirs.toString());
    }
}