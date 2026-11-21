package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.network.protocol.PlayerStateValidator;
import com.elfmcys.ysm.proto.network.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PlayerStateHandlerDeltaTest {

    @Test
    void explicitEmptyEffectsAreAValidReplacementAndDoNotContainSelection() {
        var message = PlayerStateHandler.newDelta(7)
            .setState(EntityStateProto.newBuilder().setEffects(EffectStateSetProto.newBuilder().build()).build())
            .build();
        assertEquals(7, message.entityId());
        assertTrue(PlayerStateValidator.validEntityState(message.stateUnsafe(), false));
        assertFalse(message.stateUnsafe().hasGameplay());
        assertTrue(message.stateUnsafe().effectsUnsafe().effects().isEmpty());
    }

    @Test
    void controlFlagsAndDirectiveDoNotFabricateASelectionCommit() {
        var directive =
            PlayerStateHandler.directive(com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT, "");
        assertTrue(directive.hasModel());
        assertTrue(directive.modelUnsafe().builtinDefault());
    }

}
