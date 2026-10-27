package com.elfmcys.ysm.mock.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ArtifactInventory {
    private final Path root;
    private final Map<String, Entry> entries = new HashMap<>();
    private final HashSet<Path> paths = new HashSet<>();

    public ArtifactInventory(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public synchronized Entry register(String name, Path artifact) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Artifact name must not be blank");
        }
        Objects.requireNonNull(artifact, "artifact");
        var absolute = artifact.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || !Files.isRegularFile(absolute)) {
            throw new IllegalArgumentException("Artifact must be a regular file under " + root);
        }
        if (entries.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate artifact name: " + name);
        }
        if (!paths.add(absolute)) {
            throw new IllegalArgumentException("Duplicate artifact path: " + absolute);
        }

        var entry =
            new Entry(name, normalize(root.relativize(absolute)), EvidenceJson.sha256(Files.readAllBytes(absolute)));
        entries.put(name, entry);
        return entry;
    }

    public synchronized List<Entry> entries() {
        var result = new ArrayList<>(entries.values());
        result.sort(Comparator.comparing(Entry::name));
        return List.copyOf(result);
    }

    public synchronized void write(Path path) throws IOException {
        var absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            throw new IllegalArgumentException("Inventory must be written under " + root);
        }
        EvidenceJson.writeJsonNew(absolute, new Snapshot(entries()));
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    public record Entry(String name, String path, String sha256) {
    }

    private record Snapshot(List<Entry> artifacts) {
    }
}
