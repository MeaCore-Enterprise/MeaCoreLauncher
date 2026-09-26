package com.experimento.launcher.model;

public enum JvmPresetKind {
    AUTO,
    LOW,
    BALANCED,
    HIGH,
    /** Máximo rendimiento: ZGC agresivo, heap 8-16 GB, JIT ultrarrápido. ≥32 GB RAM recomendado. */
    ULTRA
}
