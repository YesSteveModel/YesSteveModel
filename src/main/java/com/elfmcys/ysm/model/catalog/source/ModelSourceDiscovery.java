package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.legacy.LegacyContainerHeader;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanError;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Logical source names and bounded, scan-local observations of their readable targets. */
public final class ModelSourceDiscovery {
    private static final Set<String> FILE_EXTENSIONS = Set.of(".mxc", ".ysm", ".zip", ".7z");

    private ModelSourceDiscovery() {}

    public static RootInventoryState inventory(ModelCatalogSource source) {
        var configured = source.path().toAbsolutePath().normalize();
        var identity = source.identity();
        var models = new LinkedHashMap<ModelSourceKey, SourceObservation>();
        var packs = new LinkedHashMap<ModelPackSourceKey, PackObservation>();
        var errors = new ArrayList<ModelScanError>();
        try {
            try {
                Files.readAttributes(configured, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException absent) {
                if (source.createIfMissing()) {
                    Files.createDirectories(configured);
                } else if (source.rootKind() == CatalogRootKind.AUTH) {
                    return new RootInventoryState.Complete(identity, models, packs);
                } else {
                    throw absent;
                }
            }
            var discovered = discover(configured);
            for (var failure : discovered.failures()) {
                errors.add(inventoryError(source, failure.path(), failure.error()));
            }
            for (var path : discovered.models()) {
                checkInterrupted();
                try {
                    var relative = ModelPath.relativeTo(configured, path);
                    var target = path.toRealPath();
                    var kind = sourceKind(path, target);
                    var key = new ModelSourceKey(identity, relative, kind);
                    models.put(key, observe(key, path));
                } catch (IOException | RuntimeException failure) {
                    errors.add(inventoryError(source, path, failure));
                }
            }
            for (var directory : discovered.packDirectories()) {
                checkInterrupted();
                try {
                    if (Files.notExists(directory.resolve("ysm-pack.json"), LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    var hierarchy =
                        directory.equals(configured) ? "" : ModelPath.relativeTo(configured, directory).value() + "/";
                    var key = new ModelPackSourceKey(identity, hierarchy);
                    packs.put(key, observePack(key, directory));
                } catch (IOException | RuntimeException failure) {
                    errors.add(inventoryError(source, directory, failure));
                }
            }
        } catch (IOException | RuntimeException failure) {
            errors.add(inventoryError(source, configured, failure));
        }
        return errors.isEmpty() ? new RootInventoryState.Complete(identity, models, packs)
            : new RootInventoryState.Incomplete(identity, models, packs, errors);
    }

    private static ModelScanError inventoryError(ModelCatalogSource source, Path path, Throwable failure) {
        var relative = new java.util.StringJoiner("/");
        var code = "ROOT_INVENTORY_INCOMPLETE";
        for (var segment : source.path().toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())) {
            if (segment.toString().isEmpty())
                continue;
            try {
                relative.add(new ModelPath(segment.toString()).value());
            } catch (IllegalArgumentException invalid) {
                code = "INVALID_SOURCE_LOCATION";
                break;
            }
        }
        var entry = relative.toString();
        return ModelScanError.infrastructure(source.rootKind(), new CatalogLocation(source.publicRoot(), entry).value(),
            code, new IOException("Failed to observe source entry: " + path, failure));
    }

    public static SourceObservation observe(ModelSourceKey expectedKey, Path path) throws IOException {
        checkInterrupted();
        var logical = path.toAbsolutePath().normalize();
        var target = logical.toRealPath();
        if (sourceKind(logical, target) != expectedKey.sourceKind()) {
            throw new IOException("Model source kind changed while loading: " + logical);
        }
        var stamp = expectedKey.sourceKind() == ModelSourceKind.CURRENT_RAW_DIRECTORY
            ? SourceStamp.captureDirectory(target) : SourceStamp.captureFile(target);
        var size =
            stamp instanceof SourceStamp.File file ? file.size() : ((SourceStamp.RawDirectory)stamp).totalBytes();
        return new SourceObservation(expectedKey, logical, target, stamp, size);
    }

    public static PackObservation observePack(ModelPackSourceKey key, Path directory) throws IOException {
        checkInterrupted();
        var manifest = observePackFile(directory.resolve("ysm-pack.json"));
        var cover = directory.resolve("ysm-pack.png");
        return new PackObservation(key, directory, manifest,
            Files.notExists(cover, LinkOption.NOFOLLOW_LINKS) ? null : observePackFile(cover));
    }

    private static PackObservation.File observePackFile(Path logical) throws IOException {
        var target = logical.toRealPath();
        return new PackObservation.File(target, SourceStamp.captureFile(target));
    }

    public static Result discover(Path root) throws IOException {
        root = root.toAbsolutePath().normalize();
        var models = new ArrayList<Path>();
        var packDirectories = new ArrayList<Path>();
        var failures = new ArrayList<Failure>();
        var stack = new ArrayDeque<Frame>();
        Path next = root;
        while (next != null) {
            checkInterrupted();
            try {
                var target = next.toRealPath();
                var attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) {
                    var identity = new TargetIdentity(target, attributes.fileKey());
                    for (var ancestor : stack) {
                        if (identity.sameTarget(ancestor.identity())) {
                            throw new IOException("Ancestor directory cycle at " + next);
                        }
                    }
                    if (!next.equals(root) && isRawDirectory(next)) {
                        models.add(next);
                    } else {
                        packDirectories.add(next);
                        var children = new ArrayList<Path>();
                        try (var stream = Files.newDirectoryStream(target)) {
                            for (var child : stream) {
                                checkInterrupted();
                                children.add(next.resolve(child.getFileName().toString()));
                            }
                        } catch (IOException | RuntimeException failure) {
                            failures.add(
                                new Failure(next, failure instanceof IOException io ? io : new IOException(failure)));
                        }
                        stack.push(new Frame(identity, children.iterator()));
                    }
                } else if (attributes.isRegularFile()) {
                    if (next.equals(root)) {
                        throw new IOException("Model root is not a directory: " + root);
                    }
                    if (isArchive(next)) {
                        models.add(next);
                    }
                } else {
                    throw new IOException("Unsupported model source entry: " + next);
                }
            } catch (IOException | RuntimeException failure) {
                failures.add(new Failure(next, failure instanceof IOException io ? io : new IOException(failure)));
            }
            next = null;
            while (!stack.isEmpty()) {
                if (stack.peek().children().hasNext()) {
                    next = stack.peek().children().next();
                    break;
                }
                stack.pop();
            }
        }
        var logicalRoot = root;
        Comparator<Path> byRelativePath = Comparator.comparing(path -> normalizedRelative(logicalRoot, path));
        models.sort(byRelativePath);
        packDirectories.sort(byRelativePath);
        failures.sort(Comparator.comparing(failure -> normalizedRelative(logicalRoot, failure.path())));
        return new Result(models, packDirectories, failures);
    }

    private static boolean isRawDirectory(Path directory) throws IOException {
        // A linked manifest still marks a raw boundary, where capture must reject it.
        for (var name : List.of("ysm.json", "main.json")) {
            var manifest = directory.resolve(name);
            if (!Files.notExists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                Files.readAttributes(manifest, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return true;
            }
        }
        return false;
    }

    private static boolean isArchive(Path file) {
        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return FILE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static ModelSourceKind sourceKind(Path logical, Path target) throws IOException {
        var attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isDirectory()) {
            return ModelSourceKind.CURRENT_RAW_DIRECTORY;
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("Model source is not a regular file: " + logical);
        }
        var name = logical.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mxc")) {
            return ModelSourceKind.DIRECT_CONTAINER;
        }
        if (name.endsWith(".ysm")) {
            if (hasCurrentContainerMagic(target)) {
                return ModelSourceKind.DIRECT_CONTAINER;
            }
            return switch (LegacyContainerHeader.probe(target)) {
                case V1_RAW, V2_RAW -> ModelSourceKind.CURRENT_RAW_ARCHIVE;
                case V3_ENCRYPTED -> ModelSourceKind.LEGACY_ARCHIVE;
                case UNSUPPORTED -> ModelSourceKind.UNSUPPORTED_YSM;
            };
        }
        if (!isArchive(logical)) {
            throw new IOException("Unsupported model source name: " + logical);
        }
        return ModelSourceKind.CURRENT_RAW_ARCHIVE;
    }

    private static boolean hasCurrentContainerMagic(Path source) throws IOException {
        try (var input = Files.newInputStream(source)) {
            return Arrays.equals(input.readNBytes(AssetContainerConstant.HEAD.length), AssetContainerConstant.HEAD);
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Model source observation cancelled");
        }
    }

    private static String normalizedRelative(Path root, Path path) {
        return root.relativize(path).toString();
    }

    private record Frame(TargetIdentity identity, Iterator<Path> children) {
    }

    private record TargetIdentity(Path target, Object fileKey) {
        boolean sameTarget(TargetIdentity other) throws IOException {
            if (target.equals(other.target)) {
                return true;
            }
            if (fileKey != null && other.fileKey != null && target.getFileSystem() == other.target.getFileSystem()) {
                return fileKey.equals(other.fileKey);
            }
            return Files.isSameFile(target, other.target);
        }
    }

    public record Result(List<Path> models, List<Path> packDirectories, List<Failure> failures) {
        public Result {
            models = List.copyOf(models);
            packDirectories = List.copyOf(packDirectories);
            failures = List.copyOf(failures);
        }
    }

    public record Failure(Path path, IOException error) {
    }
}
