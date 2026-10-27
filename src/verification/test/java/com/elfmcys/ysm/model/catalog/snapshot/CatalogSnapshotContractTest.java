package com.elfmcys.ysm.model.catalog.snapshot;

import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogSnapshotContractTest {
    @Test
    void buildsBothIndexesFromTheOneCurrentContentMap() {
        var first = record(1, "pack/first");
        var second = record(2, "pack/second");
        var snapshot = snapshot(first, second);

        assertSame(first.binding().content(), snapshot.binding(hash(1)).orElseThrow().content());
        assertEquals(hash(2), snapshot.resolve(new HierarchyPath("local:custom/pack/second")).orElseThrow());
        assertEquals(hash(1), snapshot.resolve(first.location()).orElseThrow());
        assertEquals(Set.of(hash(1), hash(2)), snapshot.byModelId().keySet());
    }

    @Test
    void rejectsPathConflictsWithoutChangingTheCurrentSnapshot() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(record(1, "same"), record(2, "same")));

        var current = snapshot(record(1, "old"), record(2, "keep"));
        assertEquals(Set.of(hash(1), hash(2)), current.byModelId().keySet());
    }

    @Test
    void remoteMergeRetainsOnlyTheIntrinsicLocalDefault() {
        var intrinsic = record(1, "default", CatalogRootKind.BUILTIN);
        var optional = record(2, "optional", CatalogRootKind.BUILTIN);
        var local = record(3, "local", CatalogRootKind.CUSTOM);
        var remote = record(4, "remote", CatalogRootKind.CUSTOM);

        var result = snapshot(remote).withIntrinsicDefaultFrom(snapshot(intrinsic, optional, local));

        assertEquals(Set.of(hash(1), hash(4)), result.byModelId().keySet());
        assertSame(intrinsic, result.byModelId().get(hash(1)));
        assertSame(remote, result.byModelId().get(hash(4)));
    }

    private static CatalogSnapshot snapshot(CatalogRecord... records) {
        var map = new LinkedHashMap<Hash256, CatalogRecord>();
        for (var record : records) {
            map.put(record.entry().modelId(), record);
        }
        return new CatalogSnapshot(map, List.of(), ModelScanReport.empty());
    }

    private static CatalogRecord record(int seed, String path) {
        return record(seed, path, CatalogRootKind.CUSTOM);
    }

    private static CatalogRecord record(int seed, String path, CatalogRootKind root) {
        var id = hash(seed);
        var content = new StubContent(id);
        return new CatalogRecord(
            new CatalogEntry(id, new HierarchyPath(new CatalogLocation(root, new ModelPath(path)).value()),
                CatalogAccess.PUBLIC, CatalogPresentation.empty(path)),
            new CatalogLocation(root, new ModelPath(path)), new CatalogContentBinding(id, content));
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)seed;
        return new Hash256(bytes);
    }

    private record StubContent(Hash256 modelId) implements ModelContent {
        @Override
        public ModelRepresentation representation() {
            return null;
        }

        @Override
        public ModelFileView modelFile() {
            return null;
        }

        @Override
        public ChunkDataSource chunks() {
            return null;
        }
    }
}
