package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.proto.network.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerStateValidatorTest {
    @Test
    void flagsRequireTheirValueBodyAndAcceptDisabledFalse() {
        var flags = PlayerFlagsProto.newBuilder().setEntityId(1);
        assertFalse(PlayerStateValidator.validFlags(flags.build()));
        flags.setState(PlayerFlagsStateProto.newBuilder().setDisabled(false).build());
        assertTrue(PlayerStateValidator.validFlags(flags.build()));
        assertFalse(PlayerStateValidator.validFlags(flags.setEntityId(-1).build()));
    }

    @Test
    void numericBatchIsValidatedBeforeNarrowingAndAsAWhole() {
        for (int id : new int[] {-1, 64, 65536})
            assertThrows(IllegalArgumentException.class,
                () -> SelectionCodec.read(RoamingStateProto.newBuilder().addIds(id).addValues(1F).build()));
        assertThrows(IllegalArgumentException.class,
            () -> SelectionCodec.read(RoamingStateProto.newBuilder().addIds(0).build()));
        assertThrows(IllegalArgumentException.class,
            () -> SelectionCodec.read(RoamingStateProto.newBuilder().addIds(0, 0).addValues(1F, 2F).build()));
        assertFalse(
            PlayerStateValidator.validRoaming(RoamingStateProto.newBuilder().addIds(0).addValues(Float.NaN).build()));
    }

    @Test
    void emptySyncIsAnEventAndFiniteArgumentBudgetIsEnforced() {
        var p = SelectionStateSyncProto.newBuilder().setEntityId(1).setIntentId(1);
        assertTrue(PlayerStateValidator.validSync(p.setSync(SyncArgumentsProto.newBuilder().build()).build()));
        assertFalse(PlayerStateValidator
            .validSync(p.setSync(SyncArgumentsProto.newBuilder().addArguments(new float[17]).build()).build()));
        assertFalse(PlayerStateValidator.validSync(
            p.setSync(SyncArgumentsProto.newBuilder().addArguments(Float.POSITIVE_INFINITY).build()).build()));
        assertFalse(
            PlayerStateValidator.validSync(p.setIntentId(0).setSync(SyncArgumentsProto.newBuilder().build()).build()));
        assertTrue(PlayerStateValidator
            .validSync(p.setState(SelectionStateProto.newBuilder().setTextureId("").build()).build()));
    }

    @Test
    void fullSelectionRequiresAllSectionsButDeltaDoesNot() {
        var state = SelectionStateProto.newBuilder().setTextureId("unknown-local-texture")
            .setAnimation(SelectionCodec.animation("unavailable")).setRoaming(RoamingStateProto.newBuilder().build())
            .build();
        assertTrue(PlayerStateValidator.validSelectionState(state, true));
        assertFalse(PlayerStateValidator.validSelectionState(state.toBuilder().clearRoaming().build(), true));
        assertTrue(
            PlayerStateValidator.validSelectionState(SelectionStateProto.newBuilder().setTextureId("").build(), false));
    }

    @Test
    void fullEntityStateAndEffectReplacementAreSeparateFromSelection() {
        assertFalse(PlayerStateValidator.validEntityState(EntityStateProto.newBuilder().build(), false));
        assertTrue(PlayerStateValidator.validEntityState(
            EntityStateProto.newBuilder().setEffects(EffectStateSetProto.newBuilder().build()).build(), false));
        assertFalse(PlayerStateValidator.validEntityState(
            EntityStateProto.newBuilder().setGameplay(GameplayStateProto.newBuilder().setFoodLevel(21).build()).build(),
            false));
    }
}
