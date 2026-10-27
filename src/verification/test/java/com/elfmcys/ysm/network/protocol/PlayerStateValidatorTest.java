package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.proto.network.AnimationStateProto;
import com.elfmcys.ysm.proto.network.EffectStateProto;
import com.elfmcys.ysm.proto.network.EffectStateSetProto;
import com.elfmcys.ysm.proto.network.EntityRefProto;
import com.elfmcys.ysm.proto.network.GameplayStateProto;
import com.elfmcys.ysm.proto.network.ModelReferenceProto;
import com.elfmcys.ysm.proto.network.ModelSelectionStateProto;
import com.elfmcys.ysm.proto.network.MolangVariableProto;
import com.elfmcys.ysm.proto.network.PlayerStateReportProto;
import com.elfmcys.ysm.proto.network.PlayerStateUpdateProto;
import com.elfmcys.ysm.proto.network.RoamingStateProto;
import com.elfmcys.ysm.proto.network.StateWriteMode;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStateValidatorTest {
    @Test
    void acceptsBoundedFullReport() {
        var report = baseReport()
            .setGameplay(GameplayStateProto.newBuilder().setHealth(20).setMaxHealth(20).setFoodLevel(20).build())
            .setAnimation(AnimationStateProto.newBuilder().setStopped(true).build())
            .setRoaming(RoamingStateProto.newBuilder().setModelKey(0)
                .addVariables(MolangVariableProto.newBuilder().setName("speed").setValue(1.0f).build()).build())
            .build();

        assertTrue(PlayerStateValidator.validReport(report));
    }

    @Test
    void rejectsUnboundedOrSemanticallyInvalidValues() {
        assertFalse(PlayerStateValidator.validReport(
            baseReport().setGameplay(GameplayStateProto.newBuilder().setHealth(21).setMaxHealth(20).build()).build()));
        assertFalse(PlayerStateValidator.validReport(
            baseReport().setAnimation(AnimationStateProto.newBuilder().setAnimationId(" ").build()).build()));
        assertFalse(PlayerStateValidator.validReport(baseReport()
            .setRoaming(RoamingStateProto.newBuilder().setModelKey(0)
                .addVariables(MolangVariableProto.newBuilder().setName("value").setValue(Float.NaN).build()).build())
            .build()));

        var effects = EffectStateSetProto.newBuilder()
            .addEffects(EffectStateProto.newBuilder().setEffectId("minecraft:speed").setLevel(1).build())
            .addEffects(EffectStateProto.newBuilder().setEffectId("minecraft:speed").setLevel(2).build()).build();
        assertFalse(PlayerStateValidator.validReport(baseReport().setEffects(effects).build()));
    }

    @Test
    void fullUpdateRequiresModelAndNeverAcceptsPlayerIdFromWire() {
        var update = PlayerStateUpdateProto.newBuilder().setMode(StateWriteMode.FULL)
            .setSubject(EntityRefProto.newBuilder().setEntityId(1).build());
        assertFalse(PlayerStateValidator.validUpdate(update.build()));

        update.setModel(ModelSelectionStateProto.newBuilder()
            .setModel(ModelReferenceProto.newBuilder().setBuiltinDefault(true).build()).setTextureId("")
            .setDisabled(false).build());
        var valid = update.build();
        assertTrue(PlayerStateValidator.validUpdate(valid));
        var playerSubject = valid.subject().withPlayerId(ByteBuffer.wrap(new byte[PlayerId.SIZE]));
        assertFalse(PlayerStateValidator.validUpdate(valid.withSubject(playerSubject)));
    }

    private static PlayerStateReportProto.Builder baseReport() {
        return PlayerStateReportProto.newBuilder().setSubject(EntityRefProto.newBuilder().setEntityId(1).build())
            .setMode(StateWriteMode.FULL);
    }
}
