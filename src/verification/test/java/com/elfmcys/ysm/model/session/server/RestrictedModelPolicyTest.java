package com.elfmcys.ysm.model.session.server;

import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.*;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.domain.*;
import com.elfmcys.ysm.model.session.server.state.Selection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RestrictedModelPolicyTest {
    @Test
    void independentPolicyTableCoversBothNamespacesAndUnicodeCodePoints() {
        var rows = new Object[][] {{"path:global:pack/*", "global:pack/a/b.ysm", "a/m@1.0.0", true},
            {"path:global:pack/?", "global:pack/猫", "a/m", true}, {"path:global:pack/?", "global:pack/😀", "a/m", true},
            {"path:global:pack/?", "global:pack/ab", "a/m", false},
            {"id:Author/*", "local:x.ysm", "Author/m@1.0.0+pc", true},
            {"id:Author/*", "local:x.ysm", "author/m@1.0.0+pc", false},
            {"id:a/m@1.0.0", "local:x.ysm", "a/m@1.0.0+pc", false}, {"path:local:[ab]", "local:a", "a/m", false},
            {"path:local:[ab]", "local:[ab]", "a/m", true}, {"path:local:e\u0301", "local:é", "a/m", false},
            {"path:local:***", "local:a/b", "a/m", true}};
        for (var row : rows)
            assertEquals(row[3],
                RestrictedModelPolicy.parse(List.of(row[0])).restricted((String)row[1], ModelId.parse((String)row[2])),
                List.of(row).toString());
        assertFalse(RestrictedModelPolicy.parse(List.of()).restricted("local:a", ModelId.parse("a/m")));
        assertTrue(RestrictedModelPolicy.parse(List.of("id:no/match", "path:local:*", "path:local:*"))
            .restricted("local:a", ModelId.parse("a/m")));
    }

    @Test
    void invalidMemberRejectsTheWholeCandidate() {
        for (var value : List.of("wrong", 7, List.of("path:"), List.of("id:"), List.of("a/*"), List.of("Path:*"),
            List.of("id:*", "path:\ud800"), List.of("id:*", 3)))
            assertThrows(IllegalArgumentException.class, () -> RestrictedModelPolicy.parse(value));
    }

    @Test
    void publicationUsesTheCurrentPolicyAndRevokesOrdinarySelection() {
        var user = record("local:pack/a.mxc", "Author/m@1.0.0+pc");
        var builtin = record("builtin:default", "builtin/model");
        var source = new CatalogSnapshot(Map.of(user.entry().modelId(), user, builtin.entry().modelId(), builtin),
            builtin, List.of(), ModelScanReport.empty());
        var sealed = RestrictedModelPolicy.classify(source, null);
        assertEquals(Set.of(builtin.entry().modelId()), sealed.byModelId().keySet());
        assertSame(builtin, sealed.intrinsicDefault());
        var publicCatalog = RestrictedModelPolicy.classify(source, RestrictedModelPolicy.parse(List.of()));
        var session = new ServerModelSession(() -> publicCatalog, false);
        assertTrue(session.activate());
        session.commitCatalog(publicCatalog);
        assertEquals(ServerModelSession.SelectionResult.ACCEPTED,
            session.validateSelection(new Selection.Model(user.entry().modelId())));
        var restrictive = RestrictedModelPolicy.parse(List.of("path:*", "id:*"));
        var current = RestrictedModelPolicy.classify(source, restrictive);
        session.commitCatalog(current);
        assertInstanceOf(Selection.IntrinsicDefault.class, session.selection());
        assertEquals(CatalogAccess.PUBLIC, current.byModelId().get(builtin.entry().modelId()).entry().access());
        assertEquals(CatalogAccess.AUTHORIZED, current.byModelId().get(user.entry().modelId()).entry().access());
        assertFalse(session.canRequest(user.entry().modelId(),
            com.elfmcys.ysm.model.catalog.content.AssetRef.Kind.CHUNK, true));
        session.setGrants(Set.of(user.entry().modelId()));
        assertEquals(ServerModelSession.SelectionResult.ACCEPTED,
            session.validateSelection(new Selection.Model(user.entry().modelId())));
        // A late scanner result is classified with the policy active at owner publication.
        var moved = record("global:new/a.mxc", "Author/m@1.0.0+pc");
        var movedSource =
            new CatalogSnapshot(Map.of(moved.entry().modelId(), moved), List.of(), ModelScanReport.empty());
        assertEquals(CatalogAccess.AUTHORIZED, RestrictedModelPolicy.classify(movedSource, restrictive).byModelId()
            .get(moved.entry().modelId()).entry().access());
        assertEquals(CatalogAccess.PUBLIC,
            RestrictedModelPolicy.classify(movedSource, RestrictedModelPolicy.parse(List.of("path:local:*")))
                .byModelId().get(moved.entry().modelId()).entry().access());
    }

    private static CatalogRecord record(String address, String text) {
        var id = ModelId.parse(text);
        var location = CatalogLocation.parse(address);
        return new CatalogRecord(
            new CatalogEntry(id, new HierarchyPath(address), CatalogAccess.PUBLIC, CatalogPresentation.empty(address)),
            location, new CatalogContentBinding(id, new StubContent(id)));
    }

    private record StubContent(ModelId modelId) implements ModelContent {
        @Override
        public ModelRepresentation representation() {
            return null;
        }

        @Override
        public com.elfmcys.ysm.format.schema.model.ModelFileView modelFile() {
            return null;
        }

        @Override
        public ChunkDataSource chunks() {
            return null;
        }
    }
}
