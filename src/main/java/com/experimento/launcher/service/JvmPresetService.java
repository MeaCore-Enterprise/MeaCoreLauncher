package com.experimento.launcher.service;

import com.experimento.launcher.model.JvmPresetKind;
import com.experimento.launcher.model.LauncherProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class JvmPresetService {

    private JvmPresetService() {}

    public static JvmPresetKind resolveAutoKind(long totalRamMiB) {
        if (totalRamMiB <= 4 * 1024L) {
            return JvmPresetKind.LOW;
        }
        if (totalRamMiB <= 8 * 1024L) {
            return JvmPresetKind.BALANCED;
        }
        if (totalRamMiB <= 24 * 1024L) {
            return JvmPresetKind.HIGH;
        }
        // ≥32 GB → preset ULTRA automático
        return JvmPresetKind.ULTRA;
    }

    public static List<String> argsFor(LauncherProfile p, long totalRamMiB) {
        return argsFor(p, totalRamMiB, HardwareProbe.physicalCores(), HardwareProbe.availableProcessors());
    }

    public static List<String> argsFor(LauncherProfile p, long totalRamMiB, int physCores, int logicCores) {
        JvmPresetKind kind = p.jvmPreset == JvmPresetKind.AUTO ? resolveAutoKind(totalRamMiB) : p.jvmPreset;
        List<String> base =
                switch (kind) {
                    case LOW      -> lowPreset(physCores, logicCores);
                    case BALANCED -> balancedPreset(totalRamMiB, physCores, logicCores);
                    case HIGH     -> highPreset(totalRamMiB, physCores, logicCores);
                    case ULTRA    -> ultraPreset(totalRamMiB, physCores, logicCores);
                    case AUTO     -> lowPreset(physCores, logicCores);
                };
        List<String> out = new ArrayList<>(base);
        if (p.customJvmArgs != null && !p.customJvmArgs.isBlank()) {
            String custom = p.customJvmArgs.trim();

            if (custom.contains("-XX:+UseG1GC") || custom.contains("-XX:+UseZGC") ||
                custom.contains("-XX:+UseShenandoahGC") || custom.contains("-XX:+UseParallelGC")) {
                out.removeIf(arg -> arg.contains("-XX:+UseG1GC") || arg.contains("-XX:+UseZGC"));
            }

            if (custom.contains("-Xmx")) {
                out.removeIf(arg -> arg.startsWith("-Xmx"));
            }
            if (custom.contains("-Xms")) {
                out.removeIf(arg -> arg.startsWith("-Xms"));
            }

            out.addAll(Arrays.asList(custom.split("\\s+")));
        }
        return out;
    }

    public static List<String> lowPreset(int physCores, int logicCores) {
        List<String> args = new ArrayList<>(List.of(
                "-Xms512M",
                "-Xmx2G",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseG1GC",
                "-XX:MaxGCPauseMillis=20",
                "-XX:G1NewSizePercent=15",
                "-XX:G1MaxNewSizePercent=25",
                "-XX:G1HeapRegionSize=4M",
                "-XX:G1ReservePercent=10",
                "-XX:G1HeapWastePercent=8",
                "-XX:G1MixedGCCountTarget=3",
                "-XX:InitiatingHeapOccupancyPercent=10",
                "-XX:+UseStringDeduplication",
                "-XX:+ParallelRefProcEnabled",
                "-XX:+DisableExplicitGC",
                "-XX:+PerfDisableSharedMem",
                "-XX:+UseLargePages",
                "-XX:+TieredCompilation",
                "-XX:CompileThreshold=1500",
                "-Dlog4j2.formatMsgNoLookups=true",
                "-Djdk.nio.maxCachedBufferSize=262144",
                "-Dfile.encoding=UTF-8",
                "-Dsplash=false",
                "-XX:StringDeduplicationSizeThreshold=4096"));

        applyCpuArgs(args, physCores, logicCores);
        return args;
    }

    public static List<String> balancedPreset(long totalRamMiB, int physCores, int logicCores) {
        String mx;
        if (totalRamMiB >= 16 * 1024L) mx = "6G";
        else if (totalRamMiB >= 12 * 1024L) mx = "5G";
        else if (totalRamMiB >= 8 * 1024L) mx = "4G";
        else mx = "3G";

        List<String> args = new ArrayList<>(List.of(
                "-Xms2G",
                "-Xmx" + mx,
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseG1GC",
                "-XX:MaxGCPauseMillis=200",         // Pausa realista para no sobrecargar CPU en GC
                "-XX:G1NewSizePercent=20",          // Aikar's Flag
                "-XX:G1MaxNewSizePercent=60",       // Aikar's Flag
                "-XX:G1HeapRegionSize=32M",         // Aikar's Flag
                "-XX:G1ReservePercent=20",          // Aikar's Flag
                "-XX:G1HeapWastePercent=5",         // Aikar's Flag
                "-XX:G1MixedGCCountTarget=4",       // Aikar's Flag
                "-XX:InitiatingHeapOccupancyPercent=15", // Aikar's Flag
                "-XX:G1MixedGCLiveThresholdPercent=90",  // Aikar's Flag
                "-XX:G1RSetUpdatingPauseTimePercent=5",  // Aikar's Flag
                "-XX:SurvivorRatio=32",             // Aikar's Flag
                "-XX:+UseStringDeduplication",
                "-XX:+ParallelRefProcEnabled",
                "-XX:+DisableExplicitGC",
                "-XX:+PerfDisableSharedMem",
                "-XX:MaxTenuringThreshold=1",
                "-XX:+UseNUMA",
                "-XX:+UseLargePages",
                "-XX:SoftRefLRUPolicyMSPerMB=1000",
                "-XX:+TieredCompilation",
                "-XX:CompileThreshold=1500",
                "-Dlog4j2.formatMsgNoLookups=true",
                "-Djdk.nio.maxCachedBufferSize=262144",
                "-Dfile.encoding=UTF-8",
                "-Dsplash=false"));

        applyCpuArgs(args, physCores, logicCores);
        return args;
    }

    public static List<String> highPreset(long totalRamMiB, int physCores, int logicCores) {
        String mx;
        if (totalRamMiB >= 32 * 1024L) mx = "12G";
        else if (totalRamMiB >= 16 * 1024L) mx = "8G";
        else mx = "6G";

        String ms;
        if (totalRamMiB >= 16 * 1024L) ms = "4G";
        else ms = "3G";

        boolean useZGC = totalRamMiB >= 16 * 1024L;

        List<String> args;

        if (useZGC) {
            args = new ArrayList<>(List.of(
                "-Xms" + ms,
                "-Xmx" + mx,
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseZGC",
                "-XX:ZCollectionInterval=8",        // Mejorado: 10→8s
                "-XX:ZMaxMappingCount=50000",
                "-XX:ZUncommitDelay=600",           // Libera memoria al OS tras 10min sin uso
                "-XX:SoftRefLRUPolicyMSPerMB=200",  // Más agresivo que antes (500→200)
                "-XX:+UseLargePages",
                "-XX:+UseNUMA",
                "-XX:+DisableExplicitGC",
                "-XX:+PerfDisableSharedMem",
                "-XX:+ParallelRefProcEnabled",
                "-XX:+TieredCompilation",
                "-XX:CompileThreshold=1500",
                "-Dlog4j2.formatMsgNoLookups=true",
                "-Djdk.nio.maxCachedBufferSize=262144",
                "-Dfile.encoding=UTF-8",
                "-Dsplash=false"));
        } else {
            args = new ArrayList<>(List.of(
                "-Xms" + ms,
                "-Xmx" + mx,
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseG1GC",
                "-XX:MaxGCPauseMillis=200",
                "-XX:G1NewSizePercent=20",
                "-XX:G1MaxNewSizePercent=60",
                "-XX:G1HeapRegionSize=32M",
                "-XX:G1ReservePercent=20",
                "-XX:G1HeapWastePercent=5",
                "-XX:G1MixedGCCountTarget=4",
                "-XX:InitiatingHeapOccupancyPercent=15",
                "-XX:G1MixedGCLiveThresholdPercent=90",
                "-XX:G1RSetUpdatingPauseTimePercent=5",
                "-XX:SurvivorRatio=32",
                "-XX:+UseStringDeduplication",
                "-XX:+ParallelRefProcEnabled",
                "-XX:+DisableExplicitGC",
                "-XX:+PerfDisableSharedMem",
                "-XX:+UseNUMA",
                "-XX:+UseLargePages",
                "-XX:MaxTenuringThreshold=1",
                "-XX:SoftRefLRUPolicyMSPerMB=500",
                "-XX:+TieredCompilation",
                "-XX:CompileThreshold=1500",
                "-Dlog4j2.formatMsgNoLookups=true",
                "-Djdk.nio.maxCachedBufferSize=262144",
                "-Dfile.encoding=UTF-8",
                "-Dsplash=false"));
        }

        applyCpuArgs(args, physCores, logicCores);
        return args;
    }

    /**
     * Preset ULTRA: Para máquinas gaming dedicadas con ≥32 GB RAM.
     * ZGC ultra-agresivo, heap 6-24 GB según RAM, JIT con warm-up rápido.
     * Stop-the-world esperado inferior a 1 ms en condiciones normales.
     */
    public static List<String> ultraPreset(long totalRamMiB, int physCores, int logicCores) {
        String mx;
        if (totalRamMiB >= 64 * 1024L) mx = "24G";
        else if (totalRamMiB >= 32 * 1024L) mx = "16G";
        else mx = "10G";

        String ms;
        if (totalRamMiB >= 32 * 1024L) ms = "6G";
        else ms = "4G";

        boolean isLinux = System.getProperty("os.name", "").toLowerCase().contains("linux");

        List<String> args = new ArrayList<>(List.of(
            "-Xms" + ms,
            "-Xmx" + mx,
            "-XX:+UnlockExperimentalVMOptions",
            // ZGC — GC de latencia mínima (<1 ms de pausa garantizada)
            "-XX:+UseZGC",
            "-XX:ZCollectionInterval=5",            // Recolecta proactivamente cada 5s
            "-XX:ZMaxMappingCount=100000",           // Más espacio para regiones de memoria
            "-XX:ZUncommitDelay=300",               // Devuelve RAM al OS si no se usa en 5 min
            "-XX:SoftRefLRUPolicyMSPerMB=50",       // Agresivo con soft refs — menos presión GC
            // Memoria avanzada
            "-XX:+UseLargePages",
            "-XX:+UseNUMA",
            "-XX:+DisableExplicitGC",
            "-XX:+PerfDisableSharedMem",
            "-XX:+ParallelRefProcEnabled",
            // JIT agresivo — warm-up 6x más rápido que el default (10000)
            "-XX:+TieredCompilation",
            "-XX:CompileThreshold=1500",
            "-XX:+OptimizeStringConcat",
            // Networking y encoding
            "-Dlog4j2.formatMsgNoLookups=true",
            "-Djdk.nio.maxCachedBufferSize=524288",  // 512 KB buffer NIO (doble que en HIGH)
            "-Dfile.encoding=UTF-8",
            "-Dsplash=false"
        ));

        // Transparent Huge Pages en Linux: reducen presión del TLB en escenas complejas
        if (isLinux) {
            args.add("-XX:+UseTransparentHugePages");
        }

        applyCpuArgs(args, physCores, logicCores);
        return args;
    }

    private static void applyCpuArgs(List<String> args, int physCores, int logicCores) {
        // Pool de GC: dinámico según hardware, cap en 16 para evitar contención
        int parallelGC = Math.max(2, Math.min(physCores, 16));
        int concGC     = Math.max(1, parallelGC / 4);
        // Compiladores JIT paralelos
        int ciCompiler = Math.max(2, Math.min(logicCores / 2, 8));

        args.add("-XX:ParallelGCThreads=" + parallelGC);
        args.add("-XX:ConcGCThreads=" + concGC);

        // G1ConcRefinementThreads es específico de G1GC — no añadir con ZGC
        boolean isZGC = args.stream().anyMatch(a -> a.contains("+UseZGC"));
        if (!isZGC) {
            args.add("-XX:G1ConcRefinementThreads=" + parallelGC);
        }
        args.add("-XX:CICompilerCount=" + ciCompiler);

        // Informar a la JVM del número REAL de CPUs disponibles
        args.add("-XX:ActiveProcessorCount=" + logicCores);

        args.add("-XX:+OptimizeStringConcat");
        args.add("-XX:+AlwaysPreTouch");
    }
}
