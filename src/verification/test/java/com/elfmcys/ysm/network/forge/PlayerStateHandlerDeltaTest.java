package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.network.AnimationStateProto;
import com.elfmcys.ysm.proto.network.EntityRefProto;
import com.elfmcys.ysm.proto.network.ModelReferenceProto;
import com.elfmcys.ysm.proto.network.ModelSelectionStateProto;
import com.elfmcys.ysm.proto.network.PlayerStateUpdateProto;
import com.elfmcys.ysm.proto.network.RoamingStateProto;
import com.elfmcys.ysm.proto.network.StateWriteMode;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStateHandlerDeltaTest {
    @Test
    void deltaFactoryBuildsAContentDrivenUpdate() {
        var update = PlayerStateHandler.newDelta(42)
            .setAnimation(AnimationStateProto.newBuilder().setStopped(true).build()).build();

        assertEquals(42, update.subject().entityId());
        assertEquals(StateWriteMode.DELTA, update.mode());
    }

    @Test
    void roamingAbsenceResetsFullAndLeavesDeltaUnchanged() {
        var full = PlayerStateUpdateProto.newBuilder().setSubject(EntityRefProto.newBuilder().setEntityId(42).build())
            .setMode(StateWriteMode.FULL).setModel(ModelSelectionStateProto.newBuilder()
                .setModel(ModelReferenceProto.newBuilder().setBuiltinDefault(true).build()).build())
            .build();

        var reset = PlayerStateHandler.roamingApplication(full, null);

        assertNotNull(reset);
        assertTrue(reset.full());
        assertNull(reset.modelKey());
        assertTrue(reset.values().isEmpty());
        assertNull(PlayerStateHandler.roamingApplication(PlayerStateHandler.newDelta(42).build(), null));
    }

    @Test
    void selfFullBindsTheRoamingNamespaceOfItsSectionOrItsOwnModel() {
        var hash = hash(9);
        var full = PlayerStateUpdateProto.newBuilder().setSubject(EntityRefProto.newBuilder().setEntityId(42).build())
            .setMode(StateWriteMode.FULL)
            .setModel(ModelSelectionStateProto.newBuilder()
                .setModel(ModelReferenceProto.newBuilder().setModelHash(ByteBuffer.wrap(hash.bytes())).build()).build())
            .build();

        var bound = PlayerStateHandler.selfFullRoamingKey(full, hash);

        assertNotNull(bound);
        assertEquals(hash.roamingHash(), bound.intValue());

        var withSection = full.toBuilder().setRoaming(RoamingStateProto.newBuilder().setModelKey(77).build()).build();
        assertEquals(77, PlayerStateHandler.selfFullRoamingKey(withSection, hash).intValue());

        var builtinDefault = PlayerStateHandler.selfFullRoamingKey(full, null);

        assertNull(builtinDefault);
    }

    @Test
    void builtinDefaultAppliesAsTheConcreteLocalModelIdentity() {
        var selected = hash(10);
        var builtinDefault = hash(11);

        assertEquals(selected, PlayerStateHandler.appliedPlayerModelHash(selected, builtinDefault));
        assertEquals(builtinDefault, PlayerStateHandler.appliedPlayerModelHash(null, builtinDefault));
    }

    @Test
    void omittedRoamingClearsTheAcceptedDefaultNamespaceBeforeItIsInstalled() {
        var builtinDefault = hash(11);
        var full = PlayerStateUpdateProto.newBuilder().setSubject(EntityRefProto.newBuilder().setEntityId(42).build())
            .setMode(StateWriteMode.FULL).setModel(ModelSelectionStateProto.newBuilder()
                .setModel(ModelReferenceProto.newBuilder().setBuiltinDefault(true).build()).build())
            .build();
        var acceptedModel = PlayerStateHandler.appliedPlayerModelHash(null, builtinDefault);

        var reset = PlayerStateHandler.roamingApplication(full, acceptedModel);

        assertNotNull(reset);
        assertEquals(builtinDefault.roamingHash(), reset.modelKey().intValue());
        assertTrue(reset.values().isEmpty());
        assertEquals(reset.modelKey(), PlayerStateHandler.selfFullRoamingKey(full, acceptedModel));
    }

    @Test
    void explicitRoamingNamespaceDoesNotDependOnTheAcceptedModelsInstallation() {
        var update = PlayerStateHandler.newDelta(42)
            .setRoaming(RoamingStateProto.newBuilder().setModelKey(77).build()).build();

        var patch = PlayerStateHandler.roamingApplication(update, hash(11));

        assertNotNull(patch);
        assertEquals(77, patch.modelKey().intValue());
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)seed;
        return new Hash256(bytes);
    }
}
