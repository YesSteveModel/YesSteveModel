package com.elfmcys.ysm.model.catalog.builtin;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.model.catalog.content.DefaultAnimationKey;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict builtin identity and default-animation contract embedded in production JARs. */
public final class BuiltinModelIndex implements DefaultAnimationFilter {
    public static final int FORMAT_VERSION = 1;
    private static final Pattern HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ROOT_FIELDS =
        Set.of("formatVersion", "models", "defaultAnimations", "dedupProfileHash");
    private static final Set<String> MODEL_FIELDS = Set.of("path", "modelHash");
    private static final Set<String> ANIMATION_FIELDS =
        Set.of("domain", "name", "currentPayloadHash", "acceptedPayloadHashes");
    private static final Set<String> HISTORY_ROOT_FIELDS = Set.of("animations");
    private static final Set<String> HISTORY_FIELDS = Set.of("domain", "name", "payloadHashes");

    private final Map<ModelPath, Hash256> models;
    private final Map<DefaultAnimationKey, DefaultAnimationEntry> defaultAnimations;
    private final Hash256 dedupProfileHash;

    private BuiltinModelIndex(Map<ModelPath, Hash256> models,
        Map<DefaultAnimationKey, DefaultAnimationEntry> defaultAnimations, Hash256 dedupProfileHash) {
        this.models = Map.copyOf(models);
        this.defaultAnimations = Map.copyOf(defaultAnimations);
        this.dedupProfileHash = dedupProfileHash;
    }

    public static BuiltinModelIndex of(Map<ModelPath, Hash256> models) throws IOException {
        return of(models, Map.of(), Map.of());
    }

    public static BuiltinModelIndex of(Map<ModelPath, Hash256> models,
        Map<DefaultAnimationKey, Hash256> currentAnimations,
        Map<DefaultAnimationKey, Set<Hash256>> historicalAnimations) throws IOException {
        var sorted = new LinkedHashMap<ModelPath, Hash256>();
        var hashes = new HashSet<Hash256>();
        for (var entry : models.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (sorted.put(entry.getKey(), entry.getValue()) != null) {
                throw new IOException("Duplicate builtin model path: " + entry.getKey());
            }
            if (!hashes.add(entry.getValue())) {
                throw new IOException("Duplicate builtin model hash: " + entry.getValue());
            }
        }
        requireDefault(sorted.keySet());
        var unknownHistory =
            historicalAnimations.keySet().stream().filter(key -> !currentAnimations.containsKey(key)).sorted().toList();
        if (!unknownHistory.isEmpty()) {
            throw new IOException("Historical animation entries have no current animation: " + unknownHistory);
        }
        var animations = new LinkedHashMap<DefaultAnimationKey, DefaultAnimationEntry>();
        for (var entry : currentAnimations.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            var accepted = new HashSet<Hash256>();
            accepted.add(entry.getValue());
            accepted.addAll(historicalAnimations.getOrDefault(entry.getKey(), Set.of()));
            animations.put(entry.getKey(),
                new DefaultAnimationEntry(entry.getKey(), entry.getValue(), accepted.stream().sorted().toList()));
        }
        return new BuiltinModelIndex(sorted, animations, profileHash(animations.values()));
    }

    public static BuiltinModelIndex read(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(reader);
        }
    }

    public static BuiltinModelIndex read(Reader reader) throws IOException {
        final JsonElement document;
        try {
            document = JsonParser.parseReader(reader);
        } catch (JsonParseException error) {
            throw new IOException("Invalid builtin model index JSON", error);
        }
        if (!document.isJsonObject()) {
            throw new IOException("Builtin model index root must be an object");
        }
        var root = document.getAsJsonObject();
        requireExactFields(root, ROOT_FIELDS, "builtin model index");
        var version = requirePrimitive(root, "formatVersion");
        if (!version.isNumber() || !Integer.toString(FORMAT_VERSION).equals(version.getAsString())) {
            throw new IOException("Unsupported builtin model index formatVersion: " + version);
        }
        var modelElement = root.get("models");
        if (modelElement == null || !modelElement.isJsonArray()) {
            throw new IOException("Builtin model index models must be an array");
        }

        var models = new LinkedHashMap<ModelPath, Hash256>();
        var hashes = new HashSet<Hash256>();
        ModelPath previous = null;
        for (var element : modelElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IOException("Builtin model index entry must be an object");
            }
            var model = element.getAsJsonObject();
            requireExactFields(model, MODEL_FIELDS, "builtin model index entry");
            var rawPath = requireString(model, "path");
            final ModelPath path;
            try {
                path = new ModelPath(rawPath);
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid builtin model path: " + rawPath, error);
            }
            if (!path.value().equals(rawPath)) {
                throw new IOException("Builtin model path is not canonical: " + rawPath);
            }
            if (previous != null && previous.compareTo(path) >= 0) {
                throw new IOException("Builtin model entries must be strictly sorted by path: " + rawPath);
            }
            previous = path;

            var rawHash = requireString(model, "modelHash");
            if (!HASH_PATTERN.matcher(rawHash).matches()) {
                throw new IOException("Invalid builtin model hash for " + path + ": " + rawHash);
            }
            final Hash256 hash;
            try {
                hash = Hash256.parse(rawHash);
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid builtin model hash for " + path, error);
            }
            if (models.put(path, hash) != null) {
                throw new IOException("Duplicate builtin model path: " + path);
            }
            if (!hashes.add(hash)) {
                throw new IOException("Duplicate builtin model hash: " + hash);
            }
        }
        requireDefault(models.keySet());

        var animationElement = root.get("defaultAnimations");
        if (animationElement == null || !animationElement.isJsonArray()) {
            throw new IOException("Builtin model index defaultAnimations must be an array");
        }
        var animations = readAnimations(animationElement);
        var rawProfileHash = requireString(root, "dedupProfileHash");
        var profileHash = parseHash(rawProfileHash, "dedup profile");
        var actualProfileHash = profileHash(animations.values());
        if (!profileHash.equals(actualProfileHash)) {
            throw new IOException("Builtin animation dedup profile hash mismatch: expected=" + profileHash + ", actual="
                + actualProfileHash);
        }
        return new BuiltinModelIndex(models, animations, profileHash);
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(writer);
        }
    }

    public void write(Writer output) throws IOException {
        var writer = new JsonWriter(output);
        writer.setIndent("  ");
        writer.beginObject();
        writer.name("formatVersion").value(FORMAT_VERSION);
        writer.name("models").beginArray();
        for (var entry : entries()) {
            writer.beginObject();
            writer.name("path").value(entry.path().value());
            writer.name("modelHash").value(entry.modelHash().toString());
            writer.endObject();
        }
        writer.endArray();
        writer.name("defaultAnimations").beginArray();
        for (var entry : animationEntries()) {
            writer.beginObject();
            writer.name("domain").value(entry.key().domain());
            writer.name("name").value(entry.key().name());
            writer.name("currentPayloadHash").value(entry.currentPayloadHash().toString());
            writer.name("acceptedPayloadHashes").beginArray();
            for (var hash : entry.acceptedPayloadHashes()) {
                writer.value(hash.toString());
            }
            writer.endArray();
            writer.endObject();
        }
        writer.endArray();
        writer.name("dedupProfileHash").value(dedupProfileHash.toString());
        writer.endObject();
        writer.flush();
        output.write('\n');
    }

    public List<Entry> entries() {
        var entries = new ArrayList<Entry>(models.size());
        models.forEach((path, hash) -> entries.add(new Entry(path, hash)));
        entries.sort(Entry::compareTo);
        return List.copyOf(entries);
    }

    public Hash256 require(ModelPath path) throws IOException {
        var hash = models.get(path);
        if (hash == null) {
            throw new IOException("Builtin model index has no entry for path: " + path);
        }
        return hash;
    }

    public List<DefaultAnimationEntry> animationEntries() {
        return defaultAnimations.values().stream().sorted((left, right) -> left.key().compareTo(right.key())).toList();
    }

    public boolean accepts(DefaultAnimationKey key, Hash256 payloadHash) {
        var entry = defaultAnimations.get(key);
        return entry != null && entry.acceptedPayloadHashes().contains(payloadHash);
    }

    public boolean isCurrent(DefaultAnimationKey key, Hash256 payloadHash) {
        var entry = defaultAnimations.get(key);
        return entry != null && entry.currentPayloadHash().equals(payloadHash);
    }

    @Override
    public AnimationFileProto apply(RenderTargetKind kind, Collection<String> matches, String animationSet,
        AnimationFileProto source) throws IOException {
        if (source.animations().isEmpty()) {
            return source;
        }
        var domain = DefaultAnimationKey.domain(kind, matches, animationSet);
        var result = AnimationFileProto.newBuilder();
        for (var animation : source.animations()) {
            var key = new DefaultAnimationKey(domain, animation.name());
            var hash = BuiltinModelMaterializer.payloadHash(animation);
            if (!accepts(key, hash)) {
                result.addAnimations(animation);
            }
        }
        return result.build();
    }

    public void requireCurrent(RenderTargetProto target, String animationSet, AnimationProto animation)
        throws IOException {
        var key = new DefaultAnimationKey(DefaultAnimationKey.domain(target, animationSet), animation.name());
        var hash = BuiltinModelMaterializer.payloadHash(animation);
        if (!isCurrent(key, hash)) {
            throw new IOException("Builtin default animation hash mismatch: " + key + " payloadHash=" + hash);
        }
    }

    public void validateCoverage(Collection<ModelPath> discovered) throws IOException {
        var actual = Set.copyOf(discovered);
        var missing = models.keySet().stream().filter(path -> !actual.contains(path)).sorted().toList();
        var unexpected = actual.stream().filter(path -> !models.containsKey(path)).sorted().toList();
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new IOException(
                "Builtin model index coverage mismatch: missing=" + missing + ", unexpected=" + unexpected);
        }
    }

    private static void requireDefault(Collection<ModelPath> paths) throws IOException {
        if (!paths.contains(new ModelPath("default"))) {
            throw new IOException("Builtin model index must contain the default model");
        }
    }

    private static void requireExactFields(JsonObject object, Set<String> expected, String context) throws IOException {
        var actual = object.keySet();
        if (!actual.equals(expected)) {
            throw new IOException("Unexpected fields in " + context + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static JsonPrimitive requirePrimitive(JsonObject object, String name) throws IOException {
        var element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IOException("Builtin model index field must be primitive: " + name);
        }
        return element.getAsJsonPrimitive();
    }

    private static String requireString(JsonObject object, String name) throws IOException {
        var primitive = requirePrimitive(object, name);
        if (!primitive.isString()) {
            throw new IOException("Builtin model index field must be a string: " + name);
        }
        return primitive.getAsString();
    }

    public static Map<DefaultAnimationKey, Set<Hash256>> readAnimationHistory(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Map.of();
        }
        final JsonElement document;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            document = JsonParser.parseReader(reader);
        } catch (JsonParseException error) {
            throw new IOException("Invalid default animation history JSON", error);
        }
        if (!document.isJsonObject()) {
            throw new IOException("Default animation history root must be an object");
        }
        var root = document.getAsJsonObject();
        requireExactFields(root, HISTORY_ROOT_FIELDS, "default animation history");
        var animations = root.get("animations");
        if (animations == null || !animations.isJsonArray()) {
            throw new IOException("Default animation history animations must be an array");
        }
        var result = new LinkedHashMap<DefaultAnimationKey, Set<Hash256>>();
        DefaultAnimationKey previous = null;
        for (var element : animations.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IOException("Default animation history entry must be an object");
            }
            var value = element.getAsJsonObject();
            requireExactFields(value, HISTORY_FIELDS, "default animation history entry");
            var key = new DefaultAnimationKey(requireString(value, "domain"), requireString(value, "name"));
            if (previous != null && previous.compareTo(key) >= 0) {
                throw new IOException("Default animation history entries must be strictly sorted: " + key);
            }
            previous = key;
            var hashes = value.get("payloadHashes");
            if (hashes == null || !hashes.isJsonArray()) {
                throw new IOException("Default animation history payloadHashes must be an array");
            }
            var accepted = new HashSet<Hash256>();
            for (var hash : hashes.getAsJsonArray()) {
                if (!hash.isJsonPrimitive() || !hash.getAsJsonPrimitive().isString()) {
                    throw new IOException("Default animation history hash must be a string");
                }
                if (!accepted.add(parseHash(hash.getAsString(), "historical animation"))) {
                    throw new IOException("Duplicate historical animation hash for " + key);
                }
            }
            result.put(key, Set.copyOf(accepted));
        }
        return Map.copyOf(result);
    }

    private static Map<DefaultAnimationKey, DefaultAnimationEntry> readAnimations(JsonElement array)
        throws IOException {
        var result = new LinkedHashMap<DefaultAnimationKey, DefaultAnimationEntry>();
        DefaultAnimationKey previous = null;
        for (var element : array.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IOException("Builtin default animation entry must be an object");
            }
            var value = element.getAsJsonObject();
            requireExactFields(value, ANIMATION_FIELDS, "builtin default animation entry");
            var key = new DefaultAnimationKey(requireString(value, "domain"), requireString(value, "name"));
            if (previous != null && previous.compareTo(key) >= 0) {
                throw new IOException("Builtin default animation entries must be strictly sorted: " + key);
            }
            previous = key;
            var current = parseHash(requireString(value, "currentPayloadHash"), "current animation " + key);
            var acceptedElement = value.get("acceptedPayloadHashes");
            if (acceptedElement == null || !acceptedElement.isJsonArray()) {
                throw new IOException("acceptedPayloadHashes must be an array for " + key);
            }
            var accepted = new ArrayList<Hash256>();
            for (var hash : acceptedElement.getAsJsonArray()) {
                if (!hash.isJsonPrimitive() || !hash.getAsJsonPrimitive().isString()) {
                    throw new IOException("Accepted animation hash must be a string for " + key);
                }
                accepted.add(parseHash(hash.getAsString(), "accepted animation " + key));
            }
            var sorted = accepted.stream().distinct().sorted().toList();
            if (!accepted.equals(sorted) || !sorted.contains(current)) {
                throw new IOException(
                    "Accepted animation hashes must be unique, sorted and contain current hash: " + key);
            }
            result.put(key, new DefaultAnimationEntry(key, current, sorted));
        }
        return result;
    }

    private static Hash256 parseHash(String value, String context) throws IOException {
        if (!HASH_PATTERN.matcher(value).matches()) {
            throw new IOException("Invalid " + context + " hash: " + value);
        }
        try {
            return Hash256.parse(value);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid " + context + " hash", error);
        }
    }

    private static Hash256 profileHash(Collection<DefaultAnimationEntry> entries) throws IOException {
        var output = new ByteArrayOutputStream();
        for (var entry : entries.stream().sorted((left, right) -> left.key().compareTo(right.key())).toList()) {
            output.write(entry.key().domain().getBytes(StandardCharsets.UTF_8));
            output.write(0);
            output.write(entry.key().name().getBytes(StandardCharsets.UTF_8));
            output.write(0);
            for (var hash : entry.acceptedPayloadHashes()) {
                output.write(hash.bytes());
            }
        }
        return ModelHashing.blake3(output.toByteArray());
    }

    public record Entry(ModelPath path, Hash256 modelHash) implements Comparable<Entry> {
        @Override
        public int compareTo(Entry other) {
            return path.compareTo(other.path);
        }
    }

    public record DefaultAnimationEntry(DefaultAnimationKey key, Hash256 currentPayloadHash,
        List<Hash256> acceptedPayloadHashes) {
        public DefaultAnimationEntry {
            acceptedPayloadHashes = List.copyOf(acceptedPayloadHashes);
        }
    }
}
