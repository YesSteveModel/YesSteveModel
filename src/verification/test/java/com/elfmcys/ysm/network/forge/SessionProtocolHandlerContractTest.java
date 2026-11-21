package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.*;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.domain.*;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.network.protocol.*;
import com.elfmcys.ysm.proto.network.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionProtocolHandlerContractTest {
    private static CommittedSelection selected(ServerModelSession producer, long id, PublishedModelId model,
        float value) {
        return new CommittedSelection(model, id, producer,
            new RoamingDictionary(new String[] {"unknown-server-field", "missing"}),
            new RoamingValues(new short[] {0}, new float[] {value}), "unknown-texture", "unknown-animation");
    }

    @Test
    void structurallyValidBusinessRejectionAdvancesSeenOrderAndPreservesCommittedAggregate() {
        var session = new ServerModelSession(CatalogSnapshot::empty, true);
        assertTrue(session.activate());
        session.commitCatalog(CatalogSnapshot.empty());
        var cap = new ModelInfoCapability();
        session.bindSelection(
            () -> cap.selection() == null ? new com.elfmcys.ysm.model.session.server.state.Selection.IntrinsicDefault()
                : cap.selection().selection());
        assertTrue(session.observeSelectionRequest(1));
        var first = selected(session, 1, PublishedModelId.INTRINSIC_DEFAULT, 1);
        assertEquals(ServerModelSession.SelectionResult.ACCEPTED,
            PlayerStateHandler.commitSelection(session, cap, first, false));
        assertTrue(session.observeSelectionRequest(2));
        var absent = selected(session, 2, PublishedModelId.model(ModelId.parse("fixture/absent")), 2);
        assertEquals(ServerModelSession.SelectionResult.NOT_FOUND,
            PlayerStateHandler.commitSelection(session, cap, absent, true));
        assertSame(first, cap.selection());
        assertFalse(session.observeSelectionRequest(1));
        assertFalse(session.observeSelectionRequest(2));
        assertTrue(first.accepts(session, 1));
        assertFalse(first.accepts(new Object(), 1));
    }

    @Test
    void positiveCounterExhaustionCannotWrapOrReplay() {
        var session = new ServerModelSession(CatalogSnapshot::empty, false);
        session.activate();
        assertFalse(session.observeSelectionRequest(0));
        assertFalse(session.observeSelectionRequest(Long.MIN_VALUE));
        assertTrue(session.observeSelectionRequest(Long.MAX_VALUE));
        assertFalse(session.observeSelectionRequest(1));
        session.close();
        assertFalse(session.observeSelectionRequest(Long.MAX_VALUE));
    }

    @Test
    void invalidNumericBatchCannotPartiallyMutateTextureAnimationOrValues() {
        var session = new ServerModelSession(CatalogSnapshot::empty, true);
        var current = selected(session, 1, PublishedModelId.INTRINSIC_DEFAULT, 7);
        for (var invalid : new RoamingStateProto[] {
            RoamingStateProto.newBuilder().addIds(0, 2).addValues(9F, 10F).build(),
            RoamingStateProto.newBuilder().addIds(0, 1).addValues(9F, Float.NaN).build()}) {
            var delta = SelectionStateProto.newBuilder().setTextureId("changed")
                .setAnimation(SelectionCodec.animation("changed")).setRoaming(invalid).build();
            assertTrue(PlayerStateValidator.validSelectionStateShape(delta, false));
            assertThrows(IllegalArgumentException.class, () -> SelectionCodec.apply(current, delta));
            assertEquals("unknown-texture", current.texture());
            assertEquals("unknown-animation", current.animation());
            assertArrayEquals(new float[] {7, 0}, current.roaming().snapshot().values());
        }
    }

    @Test
    void producerRetirementRetainsAppearanceAndDropsDictionaryValuesAndAnimation() {
        var session = new ServerModelSession(CatalogSnapshot::empty, true);
        var cap = new ModelInfoCapability();
        cap.commitSelection(selected(session, 1, PublishedModelId.INTRINSIC_DEFAULT, 7));
        cap.retireProducer(new Object());
        assertEquals(1, cap.selection().intentId());
        cap.retireProducer(session);
        assertEquals(0, cap.selection().intentId());
        assertEquals(0, cap.selection().dictionary().size());
        assertEquals("unknown-texture", cap.selection().texture());
        assertEquals("", cap.selection().animation());
    }
}
