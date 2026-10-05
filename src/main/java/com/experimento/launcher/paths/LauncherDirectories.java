package com.experimento.launcher.paths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Rutas del launcher.
 *
 * <p>Los datos del usuario (perfiles, versiones, librerías, assets) NO viven junto al
 * ejecutable: viven en un directorio por usuario, escribible y estable frente a
 * actualizaciones. Si no, una actualización que cambia el directorio de instalación
 * "perdería" los perfiles.
 *
 * <p>Instalaciones antiguas guardaban los datos en {@code <directorio de instalación>/launcher-data}.
 * Al detectar ese directorio se copia su contenido al nuevo destino: la copia NUNCA borra
 * el origen ni pisa un archivo que ya existe en el destino.
 */
public final class LauncherDirectories {

    private final Path root;
    private final MigrationReport migration;

    public LauncherDirectories(Path root) {
        this(root, MigrationReport.none());
    }

    public LauncherDirectories(Path root, MigrationReport migration) {
        this.root = root.toAbsolutePath().normalize();
        this.migration = migration == null ? MigrationReport.none() : migration;
    }

    public static LauncherDirectories fromDefault() {
        String override = System.getProperty("launcher.root");
        if (override != null && !override.isBlank()) {
            // Modo explícito (portable, tests): no se migra nada, la raíz manda.
            return new LauncherDirectories(Paths.get(override));
        }

        Path target = defaultRoot();
        List<Path> legacyCandidates = legacyRoots(target);

        MigrationReport report = MigrationReport.none();
        for (Path legacy : legacyCandidates) {
            report = report.merge(migrate(legacy.resolve("launcher-data"), target.resolve("launcher-data")));
        }

        LauncherDirectories dirs = new LauncherDirectories(target, report);
        dirs.writeMigrationLog();
        return dirs;
    }

    /** Resultado de la migración de datos: qué se copió, qué se saltó y qué falló. */
    public record MigrationReport(int copied, int skipped, int failed, List<String> notes) {

        static MigrationReport none() {
            return new MigrationReport(0, 0, 0, new ArrayList<>());
        }

        MigrationReport merge(MigrationReport other) {
            List<String> all = new ArrayList<>(notes);
            all.addAll(other.notes());
            return new MigrationReport(copied + other.copied(), skipped + other.skipped(),
                    failed + other.failed(), all);
        }

        public boolean migratedAnything() {
            return copied > 0;
        }
    }

    /**
     * Copia {@code source} en {@code target} sin destruir nada:
     * <ul>
     *   <li>Si el origen no existe, no hace nada.</li>
     *   <li>Si el destino ya tiene el archivo, se conserva el del destino.</li>
     *   <li>Cada copia se verifica por tamaño; si no cuadra, se borra la copia parcial.</li>
     *   <li>El origen se deja intacto siempre.</li>
     * </ul>
     */
    static MigrationReport migrate(Path source, Path target) {
        List<String> notes = new ArrayList<>();
        int copied = 0, skipped = 0, failed = 0;

        if (source == null || target == null) return MigrationReport.none();
        if (source.equals(target)) return MigrationReport.none();
        if (!Files.isDirectory(source)) return MigrationReport.none();

        // Destino con contenido: es una instalación ya migrada. No se toca.
        if (hasContent(target)) {
            notes.add("El destino ya tiene datos (" + target + "): no se copia nada.");
            return new MigrationReport(0, 0, 0, notes);
        }

        final int[] counters = {0, 0, 0};
        try {
            Files.createDirectories(target);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(source)) {
                        Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isDirectory()) return FileVisitResult.CONTINUE;

                    Path rel = source.relativize(file);
                    Path dest = target.resolve(rel.toString());
                    try {
                        if (Files.exists(dest)) {
                            counters[1]++;                       // el destino gana
                            return FileVisitResult.CONTINUE;
                        }
                        Files.createDirectories(dest.getParent());
                        long expected = Files.size(file);
                        Files.copy(file, dest);

                        // Verificación: el archivo copiado debe tener el tamaño del original.
                        long actual = Files.size(dest);
                        if (actual != expected) {
                            Files.deleteIfExists(dest);
                            counters[2]++;
                            notes.add("Copia incompleta de " + rel + " (" + actual + " de " + expected + "): se descartó.");
                            return FileVisitResult.CONTINUE;
                        }
                        counters[0]++;
                    } catch (IOException e) {
                        counters[2]++;
                        notes.add("No se pudo copiar " + rel + ": " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            notes.add("Migración interrumpida: " + e.getMessage());
            return new MigrationReport(counters[0], counters[1], Math.max(1, counters[2]), notes);
        }

        if (counters[0] > 0 || counters[2] > 0) {
            notes.add("Migrado desde " + source + ": " + counters[0] + " archivos copiados, "
                    + counters[1] + " ya existían, " + counters[2] + " con problemas. El origen se conserva.");
        }
        return new MigrationReport(counters[0], counters[1], counters[2], notes);
    }

    private static boolean hasContent(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var s = Files.list(dir)) {
            return s.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Raíz por defecto: un directorio por usuario, escribible y que no depende de dónde
     * se haya instalado o desde dónde se lance el ejecutable.
     */
    static Path defaultRoot() {
        return defaultRoot(System.getProperty("os.name", ""), System::getenv,
                System.getProperty("user.home", ""));
    }

    /** Variante con SO, entorno y home inyectados (el entorno no se puede cambiar en tests). */
    static Path defaultRoot(String osName, Function<String, String> env, String userHome) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = env.apply("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData, "MeaCoreLauncher").toAbsolutePath().normalize();
            }
        }
        String xdg = env.apply("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Paths.get(xdg, "MeaCoreLauncher").toAbsolutePath().normalize();
        }
        return Paths.get(userHome, ".local", "share", "MeaCoreLauncher")
                .toAbsolutePath().normalize();
    }

/**
 * Candidatos a directorio de datos antiguo, por sistema.
 *
 * <p>Se mira el directorio de trabajo (donde vivían los datos según la versión anterior) y las
 * rutas donde realmente instala cada sistema:
 * <ul>
 *   <li><b>Windows</b>: jpackage con {@code --win-per-user-install} deja el launcher en
 *       {@code %LOCALAPPDATA%\Programs\MeaCore Launcher}. Si el usuario lo lanzaba con un
 *       doble clic, {@code user.dir} es esa carpeta y el perfil se quedaba dentro del
 *       directorio de instalación, que es justo lo que una actualización puede reemplazar.</li>
 *   <li><b>Linux</b>: el .deb instala en {@code /opt/meacore-launcher}.</li>
 * </ul>
 *
 * <p>Se recorren todos los que existan y no se descarta nada: cada migración es copia y el
 * destino con contenido gana.
 */
private static List<Path> legacyRoots(Path target) {
        List<Path> out = new ArrayList<>();
        try {
            Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            addIfDistinct(out, cwd, target);
        } catch (Exception ignored) {
            // user.dir puede ser inválido: se ignora.
        }

        for (Path known : knownInstallDirs()) {
            addIfDistinct(out, known, target);
        }
        return out;
    }

    /** Rutas de instalación conocidas, específicas de cada sistema. */
    static List<Path> knownInstallDirs() {
        return knownInstallDirs(System.getProperty("os.name", ""), System::getenv);
    }

    /**
     * Variante con el SO y el entorno inyectados: el entorno no se puede cambiar desde Java,
     * así que es la única forma de probar las rutas de Windows en Linux.
     */
    static List<Path> knownInstallDirs(String osName, Function<String, String> env) {
        List<Path> out = new ArrayList<>();
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            // jpackage --win-per-user-install
            String localAppData = envOrEmpty(env, "LOCALAPPDATA");
            if (!localAppData.isEmpty()) {
                out.add(Paths.get(localAppData, "Programs", "MeaCore Launcher"));
            }
            // Instalación por máquina (jpackage sin --win-per-user-install) o manual.
            String programFiles = envOrEmpty(env, "ProgramFiles");
            if (!programFiles.isEmpty()) {
                out.add(Paths.get(programFiles, "MeaCore Launcher"));
            }
            String programFilesX86 = envOrEmpty(env, "ProgramFiles(x86)");
            if (!programFilesX86.isEmpty()) {
                out.add(Paths.get(programFilesX86, "MeaCore Launcher"));
            }
        } else {
            out.add(Path.of("/opt/meacore-launcher"));
            // Instalación manual o portable en el home.
            String home = envOrEmpty(env, "HOME");
            if (!home.isEmpty()) {
                out.add(Paths.get(home, "MeaCoreLauncher"));
                out.add(Paths.get(home, "meacore-launcher"));
            }
        }
        return out;
    }

    private static String envOrEmpty(Function<String, String> env, String name) {
        try {
            String v = env.apply(name);
            return v == null ? "" : v.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void addIfDistinct(List<Path> out, Path candidate, Path target) {
        try {
            Path c = candidate.toAbsolutePath().normalize();
            if (!c.equals(target) && !out.contains(c)) {
                out.add(c);
            }
        } catch (Exception ignored) {
            // Ruta inválida: se ignora.
        }
    }

    /** Registro de la migración, para que el usuario pueda saber qué pasó. */
    private void writeMigrationLog() {
        if (!migration.migratedAnything() && migration.failed() == 0) return;
        try {
            Files.createDirectories(launcherData());
            Path log = launcherData().resolve("migration.log");
            StringBuilder sb = new StringBuilder();
            sb.append("=== MeaCore Launcher ").append(LocalDateTime.now()).append(" ===\n");
            for (String note : migration.notes()) {
                sb.append("- ").append(note).append('\n');
            }
            sb.append("Tus datos anteriores NO se han borrado.\n");
            Files.writeString(log, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // El registro es informativo: si falla, la migración sigue siendo válida.
        }
    }

    public MigrationReport migration() {
        return migration;
    }

    public Path root() {
        return root;
    }

    public Path launcherData() {
        return root.resolve("launcher-data");
    }

    public Path profilesDir() {
        return launcherData().resolve("profiles");
    }

    public Path versionsDir() {
        return launcherData().resolve("versions");
    }

    public Path librariesDir() {
        return launcherData().resolve("libraries");
    }

    public Path assetsDir() {
        return launcherData().resolve("assets");
    }

    public Path instancesDir() {
        return profilesDir();
    }

    public Path instanceGameDir(String instanceId) {
        return instancesDir().resolve(safeSegment(instanceId));
    }

    public void ensureBaseDirs() throws Exception {
        Files.createDirectories(profilesDir());
        Files.createDirectories(versionsDir());
        Files.createDirectories(librariesDir());
        Files.createDirectories(assetsDir());
        Files.createDirectories(launcherData().resolve("natives"));
        Files.createDirectories(launcherData().resolve("runtime"));
    }

    private static String safeSegment(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("instance id required");
        }
        if (id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new IllegalArgumentException("invalid instance id");
        }
        return id;
    }
}