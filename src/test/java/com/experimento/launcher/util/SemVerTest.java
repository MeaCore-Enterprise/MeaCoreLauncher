package com.experimento.launcher.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemVerTest {

    // ── Tabla principal: [a, b, esperado] donde esperado = Integer.signum(a.compareTo(b)) ──

    @ParameterizedTest(name = "{0} vs {1} -> {2}")
    @CsvSource({
        // Misma versión: el caso que provocaba el bucle (tag "v1.6.0" vs local "1.6.0").
        "1.6.0,   1.6.0,    0",
        "v1.6.0,  1.6.0,    0",
        "bat-1.6.0, v1.6.0, 0",
        "bat-1.6.0, 1.6.0,  0",
        "V1.6.0,   1.6.0,    0",
        "'  v1.6.0 ', 1.6.0, 0",

        //minor / patch
        "1.6.1,   1.6.0,    1",
        "1.6.0,   1.6.1,   -1",
        "1.7.0,   1.6.9,    1",
        "1.6.0,   1.7.0,   -1",

        // numérico, no lexicográfico
        "1.10.0,  1.9.9,    1",
        "1.9.9,   1.10.0,  -1",
        "1.6.10,  1.6.9,    1",
        "2.0.0,   1.99.99,  1",

        // estable > su pre-release
        "1.6.0,   1.6.0-alpha,  1",
        "1.6.0-alpha, 1.6.0,  -1",
        "1.6.1,   1.6.0-rc1,   1",

        // canales de pre-release
        "1.6.0-beta,  1.6.0-alpha, 1",
        "1.6.0-rc,    1.6.0-beta,  1",
        "1.6.0-alpha, 1.6.0-beta, -1",
        "1.6.0-rc1,   1.6.0-rc0,   1",
        "1.6.0-rc2,   1.6.0-rc10, 1",   // ASCII: "rc10" < "rc2" (SemVer 2.0.0 §11.4.2)
        "1.6.0-alpha, 1.6.0-alpha, 0",
        "1.6.0-rc.1,  1.6.0-rc.2, -1",
        "1.6.0-rc.2,  1.6.0-rc.10,-1",
        "1.6.0-rc1,   1.6.0-rc.1, 1",  // "rc1" != "rc.1": no es el mismo identificador

        // "alfa"/"alpha" y "pre"/"preview" son alias: no son versiones distintas
        "1.6.0-alfa,  1.6.0-alpha, 0",
        "1.6.0,       1.6.0-pre,   1",

        // base numérica siempre manda sobre el canal
        "1.7.0-alpha, 1.6.1,        1",
        "1.6.2-alpha, 1.6.1,        1",

        // componentes faltantes
        "1.6,      1.6.0,   0",
        "1.6.1,    1.6,     1",
        "1,        1.0.0,   0",
        "1.1,      1,       1",

        // basura tolerada en el sufijo
        "1.6.0,    1.6.0-SNAPSHOT, 1",
        "1.6.0-rc1, v1.6.0-rc1,     0",
    })
    void comparisonTable(String a, String b, int expectedSign) {
        assertEquals(expectedSign, Integer.signum(SemVer.parse(a).compareTo(SemVer.parse(b))),
                a + " vs " + b);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.6.1", "v1.6.1", "V1.6.1", "bat-1.6.1", " 1.6.1 "})
    void equivalentFormsParseToTheSameValue(String raw) {
        assertEquals(SemVer.parse("1.6.1"), SemVer.parse(raw), raw + " debe ser igual a 1.6.1");
    }

    @Test
    void prereleaseSuffixIsKeptAndRecognized() {
        SemVer v = SemVer.parse("v1.6.1-alpha");
        assertTrue(v.isValid());
        assertTrue(v.isPrerelease());
        assertEquals("1.6.1-alpha", v.normalized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "unknown", "UNKNOWN", "latest", "-", "bat-", "v", "abc", "x.y.z", "release-1"})
    void invalidInputNeverThrowsAndIsNeverNewer(String raw) {
        SemVer v = SemVer.parse(raw);
        assertFalse(v.isValid(), "'" + raw + "' no debería ser válida");
        assertEquals("", v.normalized());
        assertFalse(v.isNewerThan(SemVer.parse("1.6.0")), "'" + raw + "' no debe ofrecer actualización");
    }

    @Test
    void nullIsInvalidAndNotNewer() {
        assertFalse(SemVer.parse(null).isValid());
        assertFalse(SemVer.parse(null).isNewerThan(SemVer.parse("1.6.0")));
        assertFalse(SemVer.parse("1.6.0").isNewerThan(SemVer.parse(null)));
    }

    @Test
    void unknownNeverWinsAgainstAnything() {
        SemVer unknown = SemVer.parse("basura");
        assertTrue(unknown.compareTo(SemVer.parse("1.6.0")) < 0);
        assertTrue(unknown.compareTo(SemVer.parse("0.0.1")) < 0);
        assertEquals(0, unknown.compareTo(SemVer.parse("otra basura")));
    }

    @Test
    void isNewerThanMirrorsCompareTo() {
        assertTrue(SemVer.parse("1.6.1").isNewerThan(SemVer.parse("1.6.0")));
        assertFalse(SemVer.parse("1.6.0").isNewerThan(SemVer.parse("1.6.0")));
        assertFalse(SemVer.parse("1.6.0").isNewerThan(SemVer.parse("1.6.1")));
        assertTrue(SemVer.parse("1.6.0").isNewerThan(SemVer.parse("1.6.0-rc")));
        // Ante una versión desconocida no se ofrece nada: falso por diseño.
        assertFalse(SemVer.parse("1.0.0").isNewerThan(null));
    }

    @Test
    void normalizedIsPrefixAndSuffixFree() {
        assertEquals("1.6.1", SemVer.parse("v1.6.1").normalized());
        assertEquals("1.6.1", SemVer.parse("bat-1.6.1").normalized());
        assertEquals("1.6.0", SemVer.parse("1.6.0-alpha").normalized().replace("-alpha", ""));
        assertEquals("1.6.1-alpha", SemVer.parse("v1.6.1-ALFA").normalized());
        assertEquals("1.6.1-preview", SemVer.parse("1.6.1-pre").normalized());
        assertEquals("1.6.0", SemVer.parse("1.6").normalized());
    }

    @Test
    void missingComponentsDefaultToZero() {
        assertEquals(SemVer.parse("1.6.0"), SemVer.parse("1.6"));
        assertEquals(SemVer.parse("1.0.0"), SemVer.parse("1"));
        assertEquals(SemVer.parse("2.0.0"), SemVer.parse("2"));
    }

    @Test
    void prereleaseFlagAndToString() {
        assertFalse(SemVer.parse("1.6.1").isPrerelease());
        assertTrue(SemVer.parse("1.6.1-rc1").isPrerelease());
        assertEquals("1.6.1", SemVer.parse("v1.6.1").toString());
        assertEquals("unknown", SemVer.parse("nonsense").toString());
    }

    @Test
    void equalityAndHashUseNormalizedForm() {
        assertEquals(SemVer.parse("v1.6.1"), SemVer.parse("1.6.1"));
        assertEquals(SemVer.parse("v1.6.1").hashCode(), SemVer.parse("bat-1.6.1").hashCode());
        assertEquals(SemVer.parse("1.6.0-alpha"), SemVer.parse("bat-1.6.0-alfa"));
        assertFalse(SemVer.parse("1.6.1").equals(SemVer.parse("1.6.2")));
        //compareTo == 0 implica equals en este caso
        assertEquals(0, SemVer.parse("1.6.0-alfa").compareTo(SemVer.parse("v1.6.0-alpha")));
        assertEquals(SemVer.parse("1.6.0-alfa"), SemVer.parse("v1.6.0-alpha"));
    }

    @Test
    void sortingFollowsPrecedence() {
        var list = new java.util.ArrayList<>(java.util.List.of(
                SemVer.parse("1.6.0"),
                SemVer.parse("1.6.0-rc1"),
                SemVer.parse("1.10.0"),
                SemVer.parse("1.6.0-alpha"),
                SemVer.parse("1.9.9")));
        java.util.Collections.sort(list);
        assertEquals(java.util.List.of("1.6.0-alpha", "1.6.0-rc1", "1.6.0", "1.9.9", "1.10.0"),
                list.stream().map(SemVer::toString).toList());
    }

    @Test
    void hugeNumbersDoNotOverflowToInvalid() {
        SemVer v = SemVer.parse("99999999999.1.2");
        assertTrue(v.isValid());
        assertTrue(v.isNewerThan(SemVer.parse("1.0.0")));
    }
}