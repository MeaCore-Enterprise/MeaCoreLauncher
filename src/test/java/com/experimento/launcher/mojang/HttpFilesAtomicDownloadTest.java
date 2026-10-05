package com.experimento.launcher.mojang;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integridad de la descarga: el archivo definitivo solo aparece si la descarga terminó
 * completa y verificada (fase 0.5).
 *
 * <p>Se usa un servidor HTTP local para no depender de la red.
 */
class HttpFilesAtomicDownloadTest {

    private HttpServer server;
    private String base;

    /** Bytes que el servidor declara en Content-Length y entrega realmente. */
    private byte[] payload = new byte[0];

    /** Si es true, el servidor anuncia más bytes de los que envía (descarga truncada). */
    private boolean truncate = false;

    /** Si es true, el servidor atiende Range con 206 y solo envía el resto. */
    private boolean supportRange = false;

    /** Cabecera Range recibida en la última petición (para comprobar que se reanudó). */
    private volatile String lastRangeHeader = null;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/installer", exchange -> {
            lastRangeHeader = exchange.getRequestHeaders().getFirst("Range");

            long start = 0;
            boolean partial = false;
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (supportRange && range != null && range.startsWith("bytes=")) {
                start = Long.parseLong(range.substring("bytes=".length()).replace("-", ""));
                partial = true;
            }

            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            if (partial) {
                exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-"
                        + (payload.length - 1) + "/" + payload.length);
                exchange.sendResponseHeaders(206, payload.length - start);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload, (int) start, payload.length - (int) start);
                }
                return;
            }

            long declared = truncate ? payload.length + 100L : payload.length;
            exchange.sendResponseHeaders(200, declared);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/installer";
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void writesToPartAndOnlyPublishesTheFinalFile(@TempDir Path dir) throws Exception {
        payload = "contenido del instalador".getBytes();
        Path dest = dir.resolve("updates").resolve("meacore-update-1.exe");

        HttpFiles.downloadAtomically(base, dest, null, null, null);

        assertArrayEquals(payload, Files.readAllBytes(dest));
        // El temporal desaparece al publicar el definitivo.
        assertFalse(Files.exists(HttpFiles.partPathFor(dest)), "el .part no debe sobrevivir");
    }

    @Test
    void truncatedDownloadDoesNotPublishTheFileAndLeavesNoPart(@TempDir Path dir) throws Exception {
        payload = "contenido del instalador".getBytes();
        truncate = true;
        Path dest = dir.resolve("updates").resolve("meacore-update-2.exe");

        assertThrows(Exception.class, () -> HttpFiles.downloadAtomically(base, dest, null, null, null));

        assertFalse(Files.exists(dest), "una descarga truncada no debe crear el instalador");
        assertFalse(Files.exists(HttpFiles.partPathFor(dest)), "no debe quedar el .part huérfano");
    }

    @Test
    void unexpectedSizeIsRejected(@TempDir Path dir) throws Exception {
        payload = "0123456789".getBytes();
        Path dest = dir.resolve("updates").resolve("meacore-update-3.exe");

        // Tamaño esperado distinto del real (p. ej. el "size" de la API de GitHub).
        assertThrows(Exception.class, () -> HttpFiles.downloadAtomically(base, dest, null, 999L, null));

        assertFalse(Files.exists(dest));
        assertFalse(Files.exists(HttpFiles.partPathFor(dest)));
    }

    @Test
    void stalePartFromAnInterruptedRunIsCleaned(@TempDir Path dir) throws Exception {
        payload = "instalador nuevo".getBytes();
        Path dest = dir.resolve("updates").resolve("meacore-update-4.exe");
        Files.createDirectories(dest.getParent());

        // Resto de una descarga anterior, interrumpida a mitad.
        Path stale = HttpFiles.partPathFor(dest);
        Files.write(stale, "basura de una descarga cortada".getBytes());

        HttpFiles.downloadAtomically(base, dest, null, null, null);

        assertArrayEquals(payload, Files.readAllBytes(dest));
        assertFalse(Files.exists(stale), "el .part viejo debe borrarse antes de empezar");
    }

    @Test
    void cleanStalePartRemovesOnlyThePart(@TempDir Path dir) throws Exception {
        Path dest = dir.resolve("updates").resolve("meacore-update-5.exe");
        Files.createDirectories(dest.getParent());
        Files.write(dest, "instalador ya completo".getBytes());
        Files.write(HttpFiles.partPathFor(dest), "parcial".getBytes());

        HttpFiles.cleanStalePart(dest);

        assertTrue(Files.exists(dest), "un instalador completo nunca se borra al limpiar .part");
        assertFalse(Files.exists(HttpFiles.partPathFor(dest)));
    }

    @Test
    void partPathSitsNextToTheDestination(@TempDir Path dir) {
        Path dest = dir.resolve("meacore-update-6.deb");
        assertEquals("meacore-update-6.deb.part", HttpFiles.partPathFor(dest).getFileName().toString());
    }

    @Test
    void progressReachesOne(@TempDir Path dir) throws Exception {
        payload = "x".repeat(2048).getBytes();
        Path dest = dir.resolve("updates").resolve("meacore-update-7.exe");
        double[] last = {-99.0};

        HttpFiles.downloadAtomically(base, dest, null, null, f -> last[0] = f);

        assertEquals(1.0, last[0]);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2.2: reanudación con Range
    // ─────────────────────────────────────────────────────────────────────────

    /** Un .part a medias se continúa con Range en vez de volver a bajar el instalador entero. */
    @Test
    void resumesFromExistingPart(@TempDir Path dir) throws Exception {
        supportRange = true;
        payload = "abcdefghij".getBytes();                 // 10 bytes
        Path dest = dir.resolve("updates").resolve("meacore-update-8.exe");
        Files.createDirectories(dest.getParent());

        // Ya hay 4 bytes del principio.
        Files.write(HttpFiles.partPathFor(dest), "abcd".getBytes());

        HttpFiles.downloadAtomically(base, dest, null, (long) payload.length, null);

        assertEquals("bytes=4-", lastRangeHeader, "debe pedir solo el resto");
        assertArrayEquals(payload, Files.readAllBytes(dest), "el archivo unido debe ser idéntico al original");
    }

    /** Si el servidor ignora el Range, se empieza de cero: anexar lo duplicaría. */
    @Test
    void restartsFromScratchWhenServerIgnoresRange(@TempDir Path dir) throws Exception {
        supportRange = false;
        payload = "abcdefghij".getBytes();
        Path dest = dir.resolve("updates").resolve("meacore-update-9.exe");
        Files.createDirectories(dest.getParent());
        Files.write(HttpFiles.partPathFor(dest), "abcd".getBytes());

        HttpFiles.downloadAtomically(base, dest, null, (long) payload.length, null);

        assertArrayEquals(payload, Files.readAllBytes(dest),
                "un 200 tras el Range significa reinicio, no concatenación");
    }

    /**
     * Sin tamaño esperado no se reanuda: sin él no hay forma de saber si el .part pertenece a
     * este archivo, y anexar bytes ajenos daría un instalador corrupto.
     */
    @Test
    void neverResumesWithoutKnownSize(@TempDir Path dir) throws Exception {
        supportRange = true;
        payload = "abcdefghij".getBytes();
        Path dest = dir.resolve("updates").resolve("meacore-update-10.exe");
        Files.createDirectories(dest.getParent());
        Files.write(HttpFiles.partPathFor(dest), "abcd".getBytes());

        HttpFiles.downloadAtomically(base, dest, null, null, null);

        assertEquals(null, lastRangeHeader, "sin tamaño conocido no se debe pedir Range");
        assertArrayEquals(payload, Files.readAllBytes(dest));
    }

    /** Un .part ya completo (del tamaño esperado) no se reanuda: se publica tal cual. */
    @Test
    void doesNotResumeACompletePart(@TempDir Path dir) throws Exception {
        supportRange = true;
        payload = "abcdefghij".getBytes();
        Path dest = dir.resolve("updates").resolve("meacore-update-11.exe");
        Files.createDirectories(dest.getParent());
        Files.write(HttpFiles.partPathFor(dest), payload);

        HttpFiles.downloadAtomically(base, dest, null, (long) payload.length, null);

        assertEquals(null, lastRangeHeader);
        assertArrayEquals(payload, Files.readAllBytes(dest));
    }

    /** El progreso durante una reanudación va desde lo ya descargado, no desde cero. */
    @Test
    void progressAccountsForAlreadyDownloadedBytes(@TempDir Path dir) throws Exception {
        supportRange = true;
        payload = "abcdefghij".getBytes();
        Path dest = dir.resolve("updates").resolve("meacore-update-12.exe");
        Files.createDirectories(dest.getParent());
        Files.write(HttpFiles.partPathFor(dest), "abcd".getBytes());

        List<Double> seen = new ArrayList<>();
        HttpFiles.downloadAtomically(base, dest, null, (long) payload.length, seen::add);

        assertFalse(seen.isEmpty());
        for (double f : seen) {
            assertTrue(f >= 0.4 && f <= 1.0, "la primera fracción ya debería incluir el 40% previo: " + f);
        }
    }

    /** Un 206 incompleto (el servidor corta) no publica el archivo. */
    @Test
    void incompleteResumeIsRejected(@TempDir Path dir) throws Exception {
        supportRange = true;
        truncate = true;                        // content-length mentiroso
        payload = "abcdefghij".getBytes();
        Path dest = dir.resolve("updates").resolve("meacore-update-13.exe");
        Files.createDirectories(dest.getParent());

        assertThrows(Exception.class,
                () -> HttpFiles.downloadAtomically(base, dest, null, (long) payload.length, null));

        assertFalse(Files.exists(dest), "una reanudación incompleta no debe publicar el instalador");
        assertFalse(Files.exists(HttpFiles.partPathFor(dest)));
    }
}