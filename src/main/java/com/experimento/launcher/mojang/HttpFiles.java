package com.experimento.launcher.mojang;

import com.experimento.launcher.util.DownloadConstants;
import com.experimento.launcher.util.Hashing;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.function.Consumer;

public final class HttpFiles {

    private static final HttpClient HTTP =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

    private static final int MAX_RETRIES = 3;

    private HttpFiles() {}

    public static byte[] getBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " for " + url);
        }
        return res.body();
    }

    public static void downloadIfHashMismatch(String url, Path dest, String expectedSha1) throws Exception {
        downloadIfHashMismatch(url, dest, expectedSha1, null);
    }

    /**
     * Descarga a un archivo temporal {@code <destino>.part} y solo sustituye al archivo
     * definitivo cuando la descarga termina ÍNTEGRA y verificada.
     *
     * <p>Así una descarga interrumpida nunca deja un instalador (o un JAR) corrupto en el destino:
     * si el proceso muere o falla, queda solo el {@code .part}.
     *
     * <p>Si ya hay un {@code .part} y se conoce el tamaño esperado, la descarga se REANUDA con
     * {@code Range: bytes=N-} en lugar de empezar de cero. La reanudación solo se permite con
     * tamaño conocido: sin él no hay forma de saber si el partial pertenece a este archivo, y
     * anexar bytes de otro fichero produciría un instalador corrupto.
     *
     * @param expectedSha1 hash SHA-1 esperado, o null para no verificar.
     * @param expectedSize tamaño esperado en bytes (p. ej. el del asset en la API), o null.
     *                     Si es null se usa el Content-Length de la respuesta cuando exista.
     */
    public static void downloadAtomically(String url, Path dest, String expectedSha1,
                                          Long expectedSize, Consumer<Double> onProgress) throws Exception {
        Files.createDirectories(dest.getParent());
        Path part = partPathFor(dest);

        try {
            downloadWithRetry(url, part, expectedSha1, onProgress, expectedSize, true);
            atomicMove(part, dest);
        } catch (Exception e) {
            deleteQuietly(part);
            throw e;
        }
    }

    /** Ruta del archivo temporal usado mientras se descarga {@code dest}. */
    public static Path partPathFor(Path dest) {
        return dest.resolveSibling(dest.getFileName() + ".part");
    }

    /** Borra los {@code .part} huérfanos de una descarga anterior. */
    public static void cleanStalePart(Path dest) {
        deleteQuietly(partPathFor(dest));
    }

    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    /**
     * Renombra de forma atómica cuando el sistema de archivos lo soporta (evita que un corte de
     * luz deje el destino a medias); si no, cae a REPLACE_EXISTING.
     */
    private static void atomicMove(Path from, Path to) throws Exception {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void downloadIfHashMismatch(String url, Path dest, String expectedSha1, Consumer<Double> onProgress) throws Exception {
        Files.createDirectories(dest.getParent());
        // Idempotencia: si el archivo ya está y su hash sirve, no se vuelve a descargar.
        // Necesario para instalación de juegos, mods y modloaders.
        if (Files.exists(dest) && expectedSha1 != null && !expectedSha1.isBlank()) {
            try (InputStream in = Files.newInputStream(dest)) {
                String got = Hashing.sha1Hex(in);
                if (expectedSha1.equalsIgnoreCase(got)) {
                    if (onProgress != null) onProgress.accept(1.0);
                    return;
                }
            }
        }

        downloadWithRetry(url, dest, expectedSha1, onProgress, null, false);
    }

    private static void downloadWithRetry(String url, Path dest, String expectedSha1,
                                          Consumer<Double> onProgress, Long expectedSize,
                                          boolean allowResume) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                // El offset se recalcula en cada intento: si el anterior falló a medias, el
                // reintento continúa desde donde se quedó en vez de repetir el fichero entero.
                long resumeFrom = allowResume ? resumableSize(dest, expectedSize) : 0L;
                downloadWithProgress(url, dest, expectedSha1, onProgress, expectedSize, resumeFrom);
                return;
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    long waitMs = (long) Math.pow(2, attempt - 1) * 1000;
                    Thread.sleep(Math.min(waitMs, 10000));
                }
            }
        }
        throw lastException;
    }

    /**
     * Cuántos bytes del {@code dest} se pueden reanudar: solo si el tamaño total es conocido y
     * lo que hay es un fragmento más pequeño. Cualquier otro caso devuelve 0 (empezar de cero).
     */
    private static long resumableSize(Path dest, Long expectedSize) {
        if (expectedSize == null || expectedSize <= 0) return 0L;
        try {
            if (!Files.isRegularFile(dest)) return 0L;
            long existing = Files.size(dest);
            if (existing <= 0) return 0L;
            // Completo o demasiado grande: no hay nada que reanudar.
            if (existing >= expectedSize) return 0L;
            return existing;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void downloadWithProgress(String url, Path dest, String expectedSha1,
                                             Consumer<Double> onProgress, Long expectedSize,
                                             long resumeFrom) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET();
        if (resumeFrom > 0) {
            builder = builder.header("Range", "bytes=" + resumeFrom + "-");
        }

        HttpResponse<InputStream> res = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = res.statusCode();

        boolean appending = false;
        if (resumeFrom > 0) {
            if (status == 206) {
                appending = true;                       // el servidor acepta reanudar
            } else if (status == 200) {
                // Ignoró el Range: hay que empezar de cero o el archivo quedaría duplicado.
                resumeFrom = 0;
            } else if (status / 100 != 2) {
                throw new IllegalStateException("HTTP " + status + " for " + url);
            }
        } else if (status / 100 != 2) {
            throw new IllegalStateException("HTTP " + status + " for " + url);
        }

        long contentLength = res.headers().firstValueAsLong("content-length").orElse(-1L);
        // En 206, content-length es solo lo que falta: el total sale del Content-Range.
        Long totalFromRange = parseContentRangeTotal(res.headers().firstValue("content-range").orElse(null));

        long total;
        if (contentLength > 0) {
            total = resumeFrom + contentLength;
        } else if (totalFromRange != null) {
            total = totalFromRange;
        } else {
            total = -1L;
        }

        StandardOpenOption[] options = appending
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};

        try (InputStream in = new BufferedInputStream(res.body(), DownloadConstants.BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest, options), DownloadConstants.BUFFER_SIZE)) {

            byte[] buffer = new byte[DownloadConstants.BUFFER_SIZE];
            long downloaded = resumeFrom;
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                if (onProgress != null) {
                    if (total > 0) {
                        onProgress.accept(Math.min(1.0, (double) downloaded / total));
                    } else {
                        onProgress.accept(-1.0);
                    }
                }
            }
            out.flush();
        }

        // Integridad: una descarga truncada (red cortada sin Content-Length fiable) no debe
        // pasar por buena. El tamaño se contrasta con Content-Length, Content-Range y el
        // tamaño esperado.
        long actual = Files.size(dest);
        if (expectedSize != null && expectedSize > 0 && actual != expectedSize) {
            throw new IllegalStateException("tamaño inesperado: " + actual + " de " + expectedSize + " bytes");
        }
        if (totalFromRange != null && actual != totalFromRange) {
            throw new IllegalStateException("descarga incompleta: " + actual + " de " + totalFromRange + " bytes");
        }
        if (!appending && contentLength > 0 && actual != contentLength) {
            throw new IllegalStateException("descarga incompleta: " + actual + " de " + contentLength + " bytes");
        }

        if (onProgress != null) {
            onProgress.accept(1.0);
        }

        if (expectedSha1 != null && !expectedSha1.isBlank()) {
            try (InputStream in = Files.newInputStream(dest)) {
                String got = Hashing.sha1Hex(in);
                if (!expectedSha1.equalsIgnoreCase(got)) {
                    Files.deleteIfExists(dest);
                    throw new IllegalStateException("SHA1 mismatch for " + url + " expected " + expectedSha1 + " got " + got);
                }
            }
        }
    }

    /** Extrae el total de un header {@code Content-Range: bytes 0-99/1234}. */
    private static Long parseContentRangeTotal(String contentRange) {
        if (contentRange == null) return null;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash == contentRange.length() - 1) return null;
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

