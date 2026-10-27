package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.network.protocol.StarredModelSnapshots;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigFormsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigLabelProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationButtonProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.proto.network.ConfigActionProto;
import com.elfmcys.ysm.proto.network.EntityRefProto;
import com.elfmcys.ysm.proto.network.PrivilegedTransientEventProto;
import com.elfmcys.ysm.proto.network.PrivilegedTransientTargetProto;
import com.elfmcys.ysm.proto.network.StarredModelsSnapshotProto;

import java.nio.ByteBuffer;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlHandlerTest {
    @Test
    void absentStarredModelHashesAreAnEmptySet() {
        var snapshot = StarredModelSnapshots.create(Set.of());

        assertEquals(0, snapshot.modelHashes().size());
        assertEquals(Set.of(), ControlHandler.readHashSet(snapshot.modelHashes()));
    }

    @Test
    void presentModelHashesRoundTrip() {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = 1;
        var hash = new Hash256(bytes);
        var snapshot = StarredModelSnapshots.create(Set.of(hash));

        assertEquals(Set.of(hash), ControlHandler.readHashSet(snapshot.modelHashes()));
    }

    @Test
    void invalidModelHashRejectsTheWholeSnapshot() {
        var snapshot = StarredModelsSnapshotProto.newBuilder().addModelHashes(ByteBuffer.wrap(new byte[Hash256.SIZE]))
            .addModelHashes(ByteBuffer.wrap(new byte[Hash256.SIZE - 1])).build();

        assertNull(ControlHandler.readHashSet(snapshot.modelHashes()));
    }

    @Test
    void syncValidationUsesProductionBoundAndFiniteNumericPolicy() {
        var zero = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        assertTrue(ControlHandler.validArguments(zero));

        var maximum = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        for (var index = 0; index < MolangEventCatalog.SYNC_MAX_ARGUMENTS; index++) {
            maximum.add(index + 0.25f);
        }
        assertTrue(ControlHandler.validArguments(maximum));
        maximum.add(1);
        assertFalse(ControlHandler.validArguments(maximum));

        assertFalse(
            ControlHandler.validArguments(new it.unimi.dsi.fastutil.floats.FloatArrayList(new float[] {Float.NaN})));
        assertFalse(ControlHandler
            .validArguments(new it.unimi.dsi.fastutil.floats.FloatArrayList(new float[] {Float.POSITIVE_INFINITY})));
    }

    @Test
    void configWireAndCatalogValidationRejectsWrongPayloadOrRoute() {
        var program =
            ProgramProto.newBuilder().setFormat(1).setBytecode(java.nio.ByteBuffer.wrap(new byte[] {1})).build();
        var settings = SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("")
            .setDisablePreviewRotation(false)
            .addExtraAnimationButtons(ExtraAnimationButtonProto.newBuilder().setId("mode").setName("").setSound("")
                .addConfigForms(ConfigFormsProto.newBuilder().setType("checkbox").setTitle("").setDescription("")
                    .setStep(0).setMin(0).setMax(0).setReadProgram(program).setWriteProgram(program).build())
                .addConfigForms(ConfigFormsProto.newBuilder().setType("range").setTitle("").setDescription("")
                    .setStep(0).setMin(0).setMax(0).setReadProgram(program).setWriteProgram(program).build())
                .addConfigForms(ConfigFormsProto.newBuilder().setType("radio").setTitle("").setDescription("")
                    .setStep(0).setMin(0).setMax(0).setReadProgram(program).setWriteProgram(program)
                    .addLabels(ConfigLabelProto.newBuilder().setName("next").setActionProgram(program).build()).build())
                .build())
            .build();

        var checkbox = action("mode", 0).setBoolValue(true).build();
        var range = action("mode", 1).setF32Value(1.5f).build();
        var radio = action("mode", 2).setLabelIndex(0).setLabelName("next").build();
        assertTrue(ControlHandler.validWireAction(checkbox));
        assertTrue(ControlHandler.validCatalogAction(checkbox, settings));
        assertTrue(ControlHandler.validCatalogAction(range, settings));
        assertTrue(ControlHandler.validCatalogAction(radio, settings));

        assertFalse(ControlHandler.validWireAction(action("mode", 0).setF32Value(Float.NaN).build()));
        assertFalse(ControlHandler.validWireAction(action("mode", -1).setBoolValue(true).build()));
        assertFalse(ControlHandler.validWireAction(action("mode", 2).setLabelIndex(-1).setLabelName("next").build()));
        assertFalse(ControlHandler.validCatalogAction(action("missing", 0).setBoolValue(true).build(), settings));
        assertFalse(ControlHandler.validCatalogAction(action("mode", 0).setF32Value(1).build(), settings));
        assertFalse(ControlHandler.validCatalogAction(action("mode", 2).setLabelIndex(0).setLabelName("other").build(),
            settings));
        assertFalse(ControlHandler.validCatalogAction(action("mode", 3).setBoolValue(true).build(), settings));
    }

    @Test
    void privilegedTransientRequiresNamedCapabilityAndBoundedExactTarget() {
        var target =
            PrivilegedTransientTargetProto.newBuilder().setTarget(EntityRefProto.newBuilder().setEntityId(7).build())
                .setModelId(java.nio.ByteBuffer.wrap(new byte[Hash256.SIZE])).setTargetId("player").build();
        var accepted = PrivilegedTransientEventProto.newBuilder().addTargets(target).setSource("1")
            .setCapability(com.elfmcys.ysm.proto.network.TransientCapability.SERVER_ADMIN_ACTION).build();
        assertTrue(ControlHandler.validPrivilegedTransient(accepted));

        assertFalse(
            ControlHandler.validPrivilegedTransient(PrivilegedTransientEventProto.newBuilder().addTargets(target)
                .setSource("1").setCapability(com.elfmcys.ysm.proto.network.TransientCapability.UNSPECIFIED).build()));
        assertFalse(ControlHandler.validPrivilegedTransient(PrivilegedTransientEventProto.newBuilder()
            .addTargets(PrivilegedTransientTargetProto.newBuilder()
                .setTarget(EntityRefProto.newBuilder().setEntityId(7).build())
                .setModelId(java.nio.ByteBuffer.wrap(new byte[Hash256.SIZE - 1])).setTargetId("player").build())
            .setSource("1").setCapability(com.elfmcys.ysm.proto.network.TransientCapability.SERVER_ADMIN_ACTION)
            .build()));
    }

    private static ConfigActionProto.Builder action(String buttonId, int formIndex) {
        return ConfigActionProto.newBuilder().setButtonId(buttonId).setFormIndex(formIndex).setLabelName("");
    }
}
