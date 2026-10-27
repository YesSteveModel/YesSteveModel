package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.io.LittleEndianDataOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Atomic whole-file index for converted raw sources. */
public final class ConvertedSourceIndexStore {
    private static final byte[] MAGIC = "YSMCIDX2".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final String INDEX_FILE = "index.bin";

    private final ConvertedScope scope;
    private final AtomicSharedCache cache;
    private final String fullModVersion;

    public ConvertedSourceIndexStore(Path cacheRoot, String version) {
        this(ConvertedScope.of(cacheRoot.toAbsolutePath().normalize().getParent(), cacheRoot, version),
            new AtomicSharedCache(cacheRoot));
    }

    public ConvertedSourceIndexStore(ConvertedScope scope, AtomicSharedCache cache) {
        this.scope = Objects.requireNonNull(scope);
        this.cache = Objects.requireNonNull(cache);
        fullModVersion = scope.version();
    }

    public ConvertedScope scope() {
        return scope;
    }

    public Optional<ConvertedSourceIndex> find(String rawRelativePath) throws IOException {
        return Optional.ofNullable(read().get(ConvertedSourceIndex.normalizeRawRelativePath(rawRelativePath)));
    }

    public Map<String, ConvertedSourceIndex> read() throws IOException {
        var index = path();
        try {
            return readFile(index);
        } catch (java.nio.file.NoSuchFileException absent) {
            return Map.of();
        } catch (MalformedIndexException | IllegalArgumentException invalid) {
            return Map.of();
        }
    }

    public void replace(Collection<ConvertedSourceIndex> entries) throws IOException {
        var transaction = cache.observe(path(), true);
        cache.withKeyLock("converted-index-transaction", scope.dataIdentity() + "\0" + fullModVersion, () -> {
            transaction.verify();
            replaceLocked(entries, transaction);
            return null;
        });
    }

    private void replaceLocked(Collection<ConvertedSourceIndex> entries, CacheDirectoryGuard.Observation transaction)
        throws IOException {
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many converted-source index entries");
        }
        var unique = new LinkedHashMap<String, ConvertedSourceIndex>();
        for (var entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (!entry.fullModVersion().equals(fullModVersion)) {
                throw new IllegalArgumentException("Index entry uses a different mod version");
            }
            if (unique.putIfAbsent(entry.rawRelativePath(), entry) != null) {
                throw new IllegalArgumentException("Duplicate raw relative path: " + entry.rawRelativePath());
            }
        }

        cache.replaceValidated("converted-index", scope.dataIdentity() + "\0" + fullModVersion, path(),
            file -> readFile(file).equals(unique), file -> writeFile(file, unique.values()), replacement -> {
                transaction.verify();
                replacement.run();
            });
    }

    public void replaceScopes(Set<String> rootNamespaces, Collection<ConvertedSourceIndex> entries) throws IOException {
        Objects.requireNonNull(rootNamespaces, "rootNamespaces");
        var transaction = cache.observe(path(), true);
        cache.withKeyLock("converted-index-transaction", scope.dataIdentity() + "\0" + fullModVersion, () -> {
            transaction.verify();
            var merged = new LinkedHashMap<>(read());
            for (var namespace : rootNamespaces) {
                if (!Set.of("custom", "auth", "builtin").contains(namespace))
                    throw new IllegalArgumentException("Invalid converted-source namespace");
                merged.keySet().removeIf(path -> path.startsWith(namespace + "/"));
            }
            for (var entry : entries) {
                var namespace = entry.rawRelativePath().substring(0, entry.rawRelativePath().indexOf('/'));
                if (!rootNamespaces.contains(namespace))
                    throw new IllegalArgumentException("Index entry is outside the replaced namespaces");
                if (merged.putIfAbsent(entry.rawRelativePath(), entry) != null)
                    throw new IllegalArgumentException("Duplicate raw relative path: " + entry.rawRelativePath());
            }
            transaction.verify();
            replaceLocked(merged.values(), transaction);
            return null;
        });
    }

    public Path path() {
        return scope.root().resolve(INDEX_FILE);
    }

    public String fullModVersion() {
        return fullModVersion;
    }

    private Map<String, ConvertedSourceIndex> readFile(Path path) throws IOException {
        try (var channel = cache.openRead(path)) {
            return readFile(channel);
        }
    }

    private Map<String, ConvertedSourceIndex> readFile(java.nio.channels.SeekableByteChannel channel)
        throws IOException {
        channel.position(0);
        try (var input = new LittleEndianDataInputStream(
            new BufferedInputStream(java.nio.channels.Channels.newInputStream(channel)))) {
            var magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new MalformedIndexException("Invalid converted-source index magic");
            }
            if (!readString(input).equals(scope.dataIdentity()) || !readString(input).equals(fullModVersion))
                throw new MalformedIndexException("Converted-source scope mismatch");
            var count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw new MalformedIndexException("Invalid converted-source index entry count");
            }
            var result = new LinkedHashMap<String, ConvertedSourceIndex>();
            for (var index = 0; index < count; index++) {
                var modelId = new Hash256(readHash(input));
                var containerId = new Hash256(readHash(input));
                var rawRelativePath = readString(input);
                var entryVersion = readString(input);
                var entry = new ConvertedSourceIndex(new ModelFileIdentity(modelId, containerId), rawRelativePath,
                    entryVersion);
                if (!fullModVersion.equals(entryVersion))
                    throw new MalformedIndexException("Converted-source version mismatch");
                if (result.putIfAbsent(rawRelativePath, entry) != null) {
                    throw new MalformedIndexException("Duplicate converted-source path: " + rawRelativePath);
                }
            }
            if (input.read() != -1) {
                throw new MalformedIndexException("Trailing converted-source index data");
            }
            return Map.copyOf(result);
        } catch (EOFException truncated) {
            throw new MalformedIndexException("Truncated converted-source index", truncated);
        }
    }

    private void writeFile(CacheFile file, Collection<ConvertedSourceIndex> entries) throws IOException {
        var sorted = new ArrayList<>(entries);
        sorted
            .sort(Comparator.comparing(ConvertedSourceIndex::rawRelativePath, ConvertedSourceIndexStore::compareUtf8));
        try (var output = new LittleEndianDataOutputStream(new BufferedOutputStream(file.outputStream()))) {
            output.write(MAGIC);
            writeString(output, scope.dataIdentity());
            writeString(output, fullModVersion);
            output.writeInt(sorted.size());
            for (var entry : sorted) {
                output.write(entry.modelId().bytes());
                output.write(entry.containerId().bytes());
                writeString(output, entry.rawRelativePath());
                writeString(output, entry.fullModVersion());
            }
        }
    }

    private static byte[] readHash(LittleEndianDataInputStream input) throws IOException {
        var hash = new byte[Hash256.SIZE];
        input.readFully(hash);
        return hash;
    }

    private static String readString(LittleEndianDataInputStream input) throws IOException {
        var length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new MalformedIndexException("Invalid converted-source index string length");
        }
        var bytes = new byte[length];
        input.readFully(bytes);
        var value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(value.getBytes(StandardCharsets.UTF_8), bytes)) {
            throw new MalformedIndexException("Invalid UTF-8 in converted-source index");
        }
        return value;
    }

    private static void writeString(LittleEndianDataOutputStream output, String value) throws IOException {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("Converted-source index string is too long");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static final class MalformedIndexException extends IOException {
        private MalformedIndexException(String message) {
            super(message);
        }

        private MalformedIndexException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
