package com.experimento.launcher.mojang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merges Mojang version JSON chains ({@code inheritsFrom}).
 */
public final class VersionMerge {

    private static final ObjectMapper M = new ObjectMapper();

    private VersionMerge() {}

    public static JsonNode merge(JsonNode parent, JsonNode child) {
        if (parent == null || parent.isNull()) {
            return child.deepCopy();
        }
        ObjectNode out = parent.deepCopy();
        child.fields().forEachRemaining(e -> {
            String k = e.getKey();
            JsonNode v = e.getValue();
            if ("libraries".equals(k) && v.isArray()) {
                out.set(k, mergeLibraries(out.get("libraries"), (ArrayNode) v));
            } else if ("arguments".equals(k) && v.isObject()) {
                JsonNode existing = out.get("arguments");
                ObjectNode p = existing != null && existing.isObject() ? (ObjectNode) existing : null;
                out.set(k, mergeArguments(p, (ObjectNode) v));
            } else if ("downloads".equals(k) && v.isObject()) {
                JsonNode existing = out.get("downloads");
                ObjectNode p = existing != null && existing.isObject() ? (ObjectNode) existing : null;
                out.set(k, mergeDownloads(p, (ObjectNode) v));
            } else {
                out.set(k, v);
            }
        });
        return out;
    }

    private static ObjectNode mergeDownloads(ObjectNode parentDl, ObjectNode childDl) {
        if (parentDl == null || parentDl.isNull()) return childDl.deepCopy();
        if (childDl == null || childDl.isNull()) return parentDl.deepCopy();
        ObjectNode out = parentDl.deepCopy();
        childDl.fields().forEachRemaining(e -> out.set(e.getKey(), e.getValue()));
        return out;
    }

    private static ArrayNode mergeLibraries(JsonNode parentLibs, ArrayNode childLibs) {
        // En Minecraft pueden existir multiples librerias con el mismo nombre pero distintas reglas.
        // Fabric sobreescribe algunas, pero no queremos perder los natives (classifiers).
        // Usaremos una lista para mantener el orden de Mojang y luego agregar las de Fabric.
        ArrayNode arr = M.createArrayNode();

        // 1. Map de las librerías del hijo
        Map<String, JsonNode> childByName = new LinkedHashMap<>();
        for (JsonNode lib : childLibs) {
            String n = lib.path("name").asText("");
            if (!n.isBlank()) {
                childByName.put(n, lib);
            } else {
                arr.add(lib); // anon
            }
        }

        // 2. Procesar las del padre. Si el hijo la redefine, la fusionamos.
        if (parentLibs != null && parentLibs.isArray()) {
            for (JsonNode lib : parentLibs) {
                String n = lib.path("name").asText("");
                if (!n.isBlank()) {
                    if (childByName.containsKey(n)) {
                        // Mezclar y consumir el hijo
                        JsonNode merged = merge(lib, childByName.get(n));
                        arr.add(merged);
                        childByName.remove(n);
                    } else {
                        arr.add(lib);
                    }
                } else {
                    arr.add(lib);
                }
            }
        }

        // 3. Agregar cualquier librería del hijo que no estuviera en el padre
        childByName.values().forEach(arr::add);

        return arr;
    }

    private static ObjectNode mergeArguments(ObjectNode parentArgs, ObjectNode childArgs) {
        ObjectNode out = M.createObjectNode();
        ObjectNode p =
                parentArgs == null || parentArgs.isNull() ? M.createObjectNode() : parentArgs.deepCopy();
        if (childArgs == null || childArgs.isNull()) {
            return p;
        }
        for (String key : new String[] {"jvm", "game"}) {
            ArrayNode merged = M.createArrayNode();
            if (p.has(key) && p.get(key).isArray()) {
                merged.addAll((ArrayNode) p.get(key));
            }
            if (childArgs.has(key) && childArgs.get(key).isArray()) {
                merged.addAll((ArrayNode) childArgs.get(key));
            }
            if (merged.size() > 0) {
                out.set(key, merged);
            }
        }
        return out;
    }
}
