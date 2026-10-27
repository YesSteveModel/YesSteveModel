package com.elfmcys.ysm.model.catalog.builtin;

import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/** Process-stable builtin catalog and its generated default-model backing. */
public final class BuiltinModelCatalog implements AutoCloseable {
    private final BuiltinModelIndex contract;
    private final ManagedContainer defaultHandle;

    private BuiltinModelCatalog(BuiltinModelIndex contract, Path builtinRoot) {
        this.contract = Objects.requireNonNull(contract, "contract");
        try {
            defaultHandle = generateDefault(builtinRoot, contract);
        } catch (IOException error) {
            YesSteveModel.LOGGER.error("Failed to load the builtin default model", error);
            throw new UncheckedIOException("Failed to build the builtin default model", error);
        }
        YesSteveModel.LOGGER.info("Loaded resident builtin default model hash={}",
            defaultHandle.representation().modelId());
    }

    public static BuiltinModelCatalog open(BuiltinModelIndex contract, Path builtinRoot) {
        return new BuiltinModelCatalog(contract, builtinRoot);
    }

    public static BuiltinModelCatalog open(BuiltinModelIndex contract, Path builtinRoot,
        ContentStorageBinding binding) {
        return new BuiltinModelCatalog(contract, builtinRoot, binding);
    }

    private BuiltinModelCatalog(BuiltinModelIndex contract, Path builtinRoot, ContentStorageBinding binding) {
        this.contract = Objects.requireNonNull(contract);
        try {
            defaultHandle = generateDefault(builtinRoot, contract).withStorageBinding(binding);
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to build the builtin default model", failure);
        }
    }

    public BuiltinModelIndex contract() {
        return contract;
    }

    public ManagedContainer defaultContent() {
        return defaultHandle;
    }

    private static ManagedContainer generateDefault(Path builtinRoot, BuiltinModelIndex index) throws IOException {
        var source = builtinRoot.resolve("default");
        final ModelParser.MemoryContainer generated;
        try (var vfs = new Directory(source)) {
            generated = ModelParser.parseBuiltinDefaultMemory(vfs);
        }
        try (generated) {
            var location = new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default"));
            var expected = index.require(location.path());
            if (!expected.equals(generated.modelHash())) {
                throw new IOException(
                    "Builtin default model hash mismatch: index=" + expected + ", generated=" + generated.modelHash());
            }
            try (var bytes = generated.bytes()) {
                return ManagedContainer.openResidentDefault(bytes, location);
            }
        }
    }

    @Override
    public void close() {
        defaultHandle.representation().close();
    }
}
