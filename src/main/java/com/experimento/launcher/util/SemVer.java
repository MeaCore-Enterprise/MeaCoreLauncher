package com.experimento.launcher.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versión semántica del launcher, consciente del formato real de los tags de GitHub.
 *
 * <p>El {@code tag_name} de una release siempre trae prefijo ({@code v1.6.1}, {@code bat-1.6.1})
 * mientras que {@code version.properties} nunca lo trae ({@code 1.6.1}). Comparar esos dos
 * strings crudos da siempre "distinto", que fue lo que hizo que el launcher se auto-detectara
 * como actualización en loop. Aquí ambos lados se parsean al mismo tipo y la comparación sigue
 * las reglas de precedencia de SemVer 2.0.0.
 *
 * <p>Casos límite que tolera sin lanzar excepciones:
 * <ul>
 *   <li>Prefijos {@code bat-}, {@code v}, {@code V} y espacios.</li>
 *   <li>Componentes faltantes: {@code 1.6} → {@code 1.6.0}, {@code 1} → {@code 1.0.0}.</li>
 *   <li>Basura sin dígitos ({@code ""}, {@code null}, {@code "unknown"}, {@code "latest"}):
 *       devuelve {@link #UNKNOWN}, que nunca es más nueva que una versión válida.</li>
 * </ul>
 *
 * <p>Precedencia (SemVer 2.0.0 §11):
 * <ol>
 *   <li>Se comparan major, minor y patch numéricamente.</li>
 *   <li>Si ambos tienen pre-release, se comparan los identificadores de izquierda a derecha:
 *       los numéricos se comparan numéricamente y son menores que los alfanuméricos; si todos
 *       los anteriores son iguales, gana el que tiene más identificadores.</li>
 *   <li>Si solo uno tiene pre-release, {@code 1.6.0 > 1.6.0-alpha}: la estable es mayor.</li>
 * </ol>
 */
public record SemVer(int major, int minor, int patch, String prerelease) implements Comparable<SemVer> {

    /** Centinela para entradas ilegibles. Nunca es más nueva que una versión válida. */
    public static final SemVer UNKNOWN = new SemVer(-1, 0, 0, "");

    private static final Pattern PREFIX = Pattern.compile("^(?:bat-|v|V)");
    private static final Pattern CORE = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    /**
     * Parsea una versión sin lanzar excepciones nunca.
     *
     * @return la versión parseada, o {@link #UNKNOWN} si no contiene dígitos.
     */
    public static SemVer parse(String raw) {
        if (raw == null) return UNKNOWN;
        String s = PREFIX.matcher(raw.trim()).replaceFirst("").trim();
        if (s.isEmpty() || s.equalsIgnoreCase("unknown")) return UNKNOWN;

        // El núcleo termina en el primer '-' (separador de pre-release).
        String core = s;
        String prerelease = "";
        int dash = s.indexOf('-');
        if (dash >= 0) {
            core = s.substring(0, dash);
            prerelease = canonicalPrerelease(s.substring(dash + 1));
        }

        Matcher m = CORE.matcher(core);
        if (!m.find()) return UNKNOWN;

        int major = parseInt(m.group(1));
        int minor = m.group(2) == null ? 0 : parseInt(m.group(2));
        int patch = m.group(3) == null ? 0 : parseInt(m.group(3));
        return new SemVer(major, minor, patch, prerelease);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            // Solo alcanzable con dígitos que no caben en un int (ej: "99999999999").
            // Saturar arriba: una versión enorme SÍ es más nueva que cualquier otra.
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Normaliza el sufijo de pre-release. El proyecto usa indistintamente "alfa" y "alpha"
     * (y "pre"), así que se unifican para que dos escrituras de la misma build no se detecten
     * como versiones distintas.
     */
    private static String canonicalPrerelease(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        return switch (s) {
            case "alfa" -> "alpha";
            case "pre" -> "preview";
            default -> s;
        };
    }

    /** false si la entrada no contenía una versión usable ({@link #UNKNOWN}). */
    public boolean isValid() {
        return major >= 0;
    }

    public boolean isPrerelease() {
        return !prerelease.isEmpty();
    }

    /**
     * Forma canónica comparable, sin prefijos: {@code "1.6.1"} o {@code "1.6.1-alpha"}.
     * Es la clave que se persiste en el estado de actualización.
     */
    public String normalized() {
        if (!isValid()) return "";
        String core = major + "." + minor + "." + patch;
        return prerelease.isEmpty() ? core : core + "-" + prerelease;
    }

/**
     * true si esta versión es estrictamente más nueva que {@code other}.
     *
     * <p>Si cualquiera de las dos no es válida (incluido un {@code null}) devuelve false: ante
     * una versión ilegible es preferible no ofrecer una actualización.
     */
    public boolean isNewerThan(SemVer other) {
        return other != null && other.isValid() && isValid() && compareTo(other) > 0;
    }

    @Override
    public int compareTo(SemVer o) {
        if (o == null) return isValid() ? 1 : 0;
        // UNKNOWN nunca es más nueva que una versión válida; entre dos UNKNOWN, empate.
        if (!isValid() || !o.isValid()) {
            return Boolean.compare(isValid(), o.isValid());
        }

        int c = Integer.compare(major, o.major);
        if (c != 0) return c;
        c = Integer.compare(minor, o.minor);
        if (c != 0) return c;
        c = Integer.compare(patch, o.patch);
        if (c != 0) return c;

        boolean aPre = isPrerelease();
        boolean bPre = o.isPrerelease();
        if (!aPre && !bPre) return 0;
        // Una release estable es mayor que cualquiera de sus pre-releases.
        if (!aPre) return 1;
        if (!bPre) return -1;

        return comparePrerelease(prerelease, o.prerelease);
    }

    /** Compara los identificadores del pre-release según SemVer 2.0.0 §11.4. */
    private static int comparePrerelease(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int max = Math.max(pa.length, pb.length);
        for (int i = 0; i < max; i++) {
            // "Un identificador con más campos cuando todos los anteriores son iguales
            //  tiene mayor precedencia."
            if (i >= pa.length) return -1;
            if (i >= pb.length) return 1;

            String ia = pa[i];
            String ib = pb[i];
            boolean na = isNumeric(ia);
            boolean nb = isNumeric(ib);

            if (na && nb) {
                int c = Long.compare(Long.parseLong(ia), Long.parseLong(ib));
                if (c != 0) return c;
            } else if (na != nb) {
                // "los identificadores numéricos siempre tienen menor precedencia que los
                //  alfanuméricos".
                return na ? -1 : 1;
            } else {
                int c = ia.compareTo(ib);
                if (c != 0) return c;
            }
        }
        return 0;
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    /** Identidad de valor: dos tags equivalentes ("v1.6.1", "bat-1.6.1") son el mismo SemVer. */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof SemVer other && normalized().equals(other.normalized());
    }

    @Override
    public int hashCode() {
        return normalized().hashCode();
    }

    @Override
    public String toString() {
        return isValid() ? normalized() : "unknown";
    }
}