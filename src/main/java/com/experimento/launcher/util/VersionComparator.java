package com.experimento.launcher.util;

/**
 * Utilidades para comparar versiones de Minecraft.
 * Resuelve el problema de String.contains() que falsamente detecta "1.20.10" como "1.20.1".
 */
public final class VersionComparator {

    private VersionComparator() {}

    /**
     * Convierte una versión de Minecraft (ej: "1.20.1", "1.21.3") a array de ints [major, minor, patch].
     */
    public static int[] parseMcVersion(String v) {
        String[] parts = v.replaceAll("[^0-9.]", "").split("\\.");
        return new int[] {
            parts.length > 0 ? Integer.parseInt(parts[0]) : 0,
            parts.length > 1 ? Integer.parseInt(parts[1]) : 0,
            parts.length > 2 ? Integer.parseInt(parts[2]) : 0
        };
    }

    /**
     * Verifica si la versión dada es al menos la versión especificada.
     * @param mcVersion Versión de Minecraft a comparar
     * @param major Versión mayor requerida
     * @param minor Versión menor requerida
     * @param patch Versión patch requerida
     * @return true si mcVersion >= [major.minor.patch]
     */
    public static boolean isVersionAtLeast(String mcVersion, int major, int minor, int patch) {
        int[] v = parseMcVersion(mcVersion);
        if (v[0] > major) return true;
        if (v[0] < major) return false;
        if (v[1] > minor) return true;
        if (v[1] < minor) return false;
        return v[2] >= patch;
    }

    /**
     * Verifica si la versión está en el rango [minMajor.minMinor.minPatch, maxMajor.maxMinor.maxPatch].
     */
    public static boolean isVersionInRange(String mcVersion, int minMajor, int minMinor, int minPatch,
                                            int maxMajor, int maxMinor, int maxPatch) {
        int[] v = parseMcVersion(mcVersion);
        int[] min = new int[]{minMajor, minMinor, minPatch};
        int[] max = new int[]{maxMajor, maxMinor, maxPatch};

        // Verificar mínimo
        if (v[0] < min[0]) return false;
        if (v[0] == min[0] && v[1] < min[1]) return false;
        if (v[0] == min[0] && v[1] == min[1] && v[2] < min[2]) return false;

        // Verificar máximo
        if (v[0] > max[0]) return false;
        if (v[0] == max[0] && v[1] > max[1]) return false;
        if (v[0] == max[0] && v[1] == max[1] && v[2] > max[2]) return false;

        return true;
    }

    /**
     * Compara dos versiones (ej: "9.10.1" vs "9.6").
     * @return >0 si v1 > v2, <0 si v1 < v2, 0 si iguales.
     */
    public static int compare(String v1, String v2) {
        String[] p1 = v1.replaceAll("[^0-9.]", "").split("\\.");
        String[] p2 = v2.replaceAll("[^0-9.]", "").split("\\.");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < p1.length && !p1[i].isEmpty() ? Integer.parseInt(p1[i]) : 0;
            int n2 = i < p2.length && !p2[i].isEmpty() ? Integer.parseInt(p2[i]) : 0;
            if (n1 != n2) {
                return Integer.compare(n1, n2);
            }
        }
        return 0;
    }
}
