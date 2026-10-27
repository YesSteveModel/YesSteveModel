package com.elfmcys.ysm.expression.inventory;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationReferenceProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BlendTransitionProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneAnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.StateProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.TransitionProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigFormsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigLabelProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationButtonProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.ProgramNode;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProgramInventoryTest {
    private final MolangFrontend frontend = new MolangFrontend();

    @Test
    void borrowedDefaultLocationsStayCompleteWithBoundedDistinctProgramKeys() throws Exception {
        var origin = "fallback/" + "a".repeat(64) + "/player/main/";
        var animationName = "aim_left_shoulder_arm_auxiliary_finger_index_tip";
        var boneName = "LeftShoulderArmAuxiliaryFingerIndexTip";
        var animation = AnimationProto.newBuilder().setName(animationName)
            .addBoneAnimations(BoneAnimationProto.newBuilder().setBoneName(boneName)
                .addRotation(BoneKeyFrameProto.newBuilder().setStartTick(0)
                    .addPre(ExpressionValueProto.newBuilder().setProgram(program("1")).build()).build())
                .build())
            .build();
        var definition = ModelDataProto.newBuilder()
            .putAnimationFiles(origin + "main", AnimationFileProto.newBuilder().addAnimations(animation).build())
            .build();
        var first = ModelProgramInventory.collect("player", ExpressionSet.MAIN, definition, null, null,
            MolangEventCatalog.production());
        var located = ModelProgramInventory.locate("player", ExpressionSet.MAIN, definition, null, null,
            MolangEventCatalog.production());
        var arm = ModelProgramInventory.collect("player", ExpressionSet.FP_ARM, definition, null, null,
            MolangEventCatalog.production());
        assertEquals(1, first.programs().size());
        assertEquals(512, first.manifest().get(0).keyHex().length());
        assertEquals(first.programs().get(0).key(), located.get(0).key());
        assertNotEquals(first.programs().get(0).key(), arm.programs().get(0).key());
        assertTrue(first.manifest().get(0).location().contains(origin + "main/" + animationName));
        assertTrue(first.manifest().get(0).location().contains(boneName));
        var otherAnimation = AnimationProto.newBuilder().setName(animationName)
            .addBoneAnimations(BoneAnimationProto.newBuilder().setBoneName(boneName.replace("Tip", "Top"))
                .addRotation(BoneKeyFrameProto.newBuilder().setStartTick(0)
                    .addPre(ExpressionValueProto.newBuilder().setProgram(program("1")).build()).build())
                .build())
            .build();
        var otherDefinition = ModelDataProto.newBuilder()
            .putAnimationFiles(origin + "main", AnimationFileProto.newBuilder().addAnimations(otherAnimation).build())
            .build();
        var other = ModelProgramInventory.collect("player", ExpressionSet.MAIN, otherDefinition, null, null,
            MolangEventCatalog.production());
        assertEquals(first.manifest().get(0).keyHex().substring(0, 448),
            other.manifest().get(0).keyHex().substring(0, 448));
        assertNotEquals(first.programs().get(0).key(), other.programs().get(0).key());
    }

    @Test
    void inventoriesEveryFormalRoleWithStableKeysAndCounts() throws Exception {
        var first = ModelProgramInventory.collect("player", model(false), strings(), settings());
        var second = ModelProgramInventory.collect("player", model(true), strings(), settings());

        assertEquals(first.manifest(), second.manifest());
        assertEquals(10, first.counts().candidates());
        assertEquals(10, first.counts().executables());
        assertEquals(1, first.counts().literals());
        assertEquals(10, first.counts().programs());
        assertEquals(10, first.counts().fallbacks());
        assertEquals(0, first.counts().failures());
        assertEquals(1, first.counts().callableEntries());
        assertEquals(1, first.counts().eventEntries());
        assertEquals(0, first.counts().rejectedSuffixes());
        assertEquals(10, first.programs().stream().map(value -> value.key()).distinct().count());
        assertTrue(first.manifest().stream()
            .allMatch(value -> value.keyHex().length() > 64 && value.keySha256().length() == 64));
        assertTrue(first.manifest().stream().anyMatch(value -> value.role() == ProgramRole.USER_FUNCTION
            && value.signature().contains("VariadicParameter") && value.fallbackPolicy().equals("typed-zero:f32")));
        assertTrue(first.manifest().stream().anyMatch(value -> value.role() == ProgramRole.EVENT_PLAYER_UPDATE
            && value.signature().contains("BOOL") && value.handlerOrder() == 0));
    }

    @Test
    void rejectsMissingPayloadAndDuplicateCanonicalEntry() throws Exception {
        var controller = AnimationControllerProto.newBuilder().setName("main").setDefaultState("default")
            .addStates(
                StateProto.newBuilder().setName("default").setBlendTransition(BlendTransitionProto.newBuilder().build())
                    .addTransitions(TransitionProto.newBuilder().setDst("next").setCondition(
                        ProgramProto.newBuilder().setFormat(999).setBytecode(ByteBuffer.wrap(new byte[] {1})).build())
                        .build())
                    .build())
            .build();
        var invalid = ModelDataProto.newBuilder().putAnimationControllers("main",
            AnimationControllerFileProto.newBuilder().addControllers(controller).build()).build();
        assertThrows(ModelProgramInventory.InventoryException.class,
            () -> ModelProgramInventory.collect("player", invalid, null, null));

        var duplicate = StringDataProto.newBuilder().addUserFunctions(function("same", "1"))
            .addUserFunctions(function("same", "2")).build();
        assertThrows(ModelProgramInventory.InventoryException.class,
            () -> ModelProgramInventory.collect("player", ModelDataProto.newBuilder().build(), duplicate, null));
    }

    @Test
    void rejectsUnknownPortableProgramFormat() {
        var unknown = ProgramProto.newBuilder().setFormat(999).setBytecode(ByteBuffer.wrap(new byte[] {1})).build();
        var strings = StringDataProto.newBuilder()
            .addUserFunctions(UserFunctionProto.newBuilder().setName("bad").setBody(unknown).build()).build();

        assertThrows(ModelProgramInventory.InventoryException.class,
            () -> ModelProgramInventory.collect("player", ModelDataProto.newBuilder().build(), strings, null));
    }

    @Test
    void classifiesClosedEventsPreservesOrderAndKeepsDualRoleDistinct() throws Exception {
        var strings = StringDataProto.newBuilder().addUserFunctions(function("dual@player_update", "v.seen=args[0];"))
            .addUserFunctions(function("first@sync", "v.first=args[0];"))
            .addUserFunctions(function("second@sync", "v.second=args[99];"))
            .addUserFunctions(function("@defer", "v.deferred=args[0];"))
            .addUserFunctions(function("@player_init", "v.initialized=1;"))
            .addUserFunctions(function("@player_ctrl_main", "return 2;"))
            .addUserFunctions(function("kept@unknown_suffix", "return 1;")).build();
        var result = ModelProgramInventory.collect("player", ModelDataProto.newBuilder().build(), strings, null);

        assertEquals(7, result.counts().candidates());
        assertEquals(4, result.counts().callableEntries());
        assertEquals(6, result.counts().eventEntries());
        assertEquals(1, result.counts().rejectedSuffixes());
        assertEquals(MolangEventCatalog.production().fingerprint(), result.eventCatalogFingerprint());
        assertEquals("unknown-event-suffix:v2:unknown_suffix", result.rejectedSuffixes().get(0).diagnostic());

        var callable =
            result.manifest().stream().filter(value -> value.location().endsWith("function/dual@player_update")
                && value.role() == ProgramRole.USER_FUNCTION).findFirst().orElseThrow();
        var event = result.manifest().stream().filter(value -> value.location().endsWith("function/dual@player_update")
            && value.role() == ProgramRole.EVENT_PLAYER_UPDATE).findFirst().orElseThrow();
        assertNotEquals(callable.keyHex(), event.keyHex());
        assertNotEquals(callable.signature(), event.signature());
        assertNotEquals(callable.fallbackPolicy(), event.fallbackPolicy());
        assertNotEquals(callable.diagnosticPolicy(), event.diagnosticPolicy());
        assertEquals("UNIT_LOCAL", callable.publicReachability());
        assertEquals("HOST_ONLY", event.publicReachability());

        var syncOrders = result.manifest().stream().filter(value -> value.role() == ProgramRole.EVENT_SYNC)
            .map(ModelProgramInventory.ManifestEntry::handlerOrder).toList();
        assertEquals(List.of(0, 1), syncOrders);
        assertTrue(result.manifest().stream().filter(value -> value.role() == ProgramRole.EVENT_SYNC)
            .allMatch(value -> value.maximumArguments() == MolangEventCatalog.SYNC_MAX_ARGUMENTS));
        assertTrue(result.manifest().stream().filter(value -> value.role() == ProgramRole.EVENT_DEFER)
            .allMatch(value -> value.maximumArguments() == MolangEventCatalog.DEFER_MAX_ARGUMENTS));
    }

    @Test
    void voidEventEvaluatesReturnValueBeforeReturningWithoutOne() throws Exception {
        var result = ModelProgramInventory.collect("player", ModelDataProto.newBuilder().build(), strings(), null);
        var binding = result.bindings().stream()
            .filter(value -> value.location().role() == ProgramRole.EVENT_PLAYER_UPDATE).findFirst().orElseThrow();
        var program = PortableProgramCodecs.v1().decode(binding.source().program());
        var block = assertInstanceOf(ProgramNode.Block.class, program.roots().get(0));
        assertEquals(2, block.children().size());
        var control = assertInstanceOf(ProgramNode.Control.class, block.children().get(1));
        assertEquals(ProgramNode.ControlOperator.RETURN, control.operator());
        assertTrue(control.value().isEmpty());
    }

    private ModelDataProto model(boolean reverse) throws Exception {
        var result = ModelDataProto.newBuilder();
        var a = file("a", true);
        var b = file("b", false);
        if (reverse) {
            result.putAnimationFiles(b.getKey(), b.getValue()).putAnimationFiles(a.getKey(), a.getValue());
        } else {
            result.putAnimationFiles(a.getKey(), a.getValue()).putAnimationFiles(b.getKey(), b.getValue());
        }
        var state = StateProto.newBuilder().setName("default")
            .setBlendTransition(BlendTransitionProto.newBuilder().build())
            .addAnimations(AnimationReferenceProto.newBuilder().setName("idle").setCondition(program("true")).build())
            .addTransitions(TransitionProto.newBuilder().setDst("next").setCondition(program("q.ready")).build())
            .setOnEntry(program("v.entered=1")).setOnExit(program("v.entered=0")).build();
        var controller =
            AnimationControllerProto.newBuilder().setName("main").setDefaultState("default").addStates(state).build();
        result.putAnimationControllers("main",
            AnimationControllerFileProto.newBuilder().addControllers(controller).build());
        return result.build();
    }

    private Map.Entry<String, AnimationFileProto> file(String key, boolean withValues) throws Exception {
        var animation = AnimationProto.newBuilder().setName(key).setLength(0)
            .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.PLAY_ONCE);
        if (withValues) {
            animation.setBlendWeight(ExpressionValueProto.newBuilder().setProgram(program("q.weight")).build());
            animation.addBoneAnimations(BoneAnimationProto.newBuilder().setBoneName("root")
                .addPosition(BoneKeyFrameProto.newBuilder().setStartTick(0)
                    .setEasingType(com.elfmcys.ysm.proto.mixel.asset.model.data.EasingType.NONE)
                    .addPre(ExpressionValueProto.newBuilder().setNum(1).build()).build())
                .build());
        }
        return Map.entry(key, AnimationFileProto.newBuilder().addAnimations(animation.build()).build());
    }

    private StringDataProto strings() throws Exception {
        return StringDataProto.newBuilder().addUserFunctions(function("tick@player_update", "return args[0];")).build();
    }

    private UserFunctionProto function(String name, String source) throws Exception {
        return UserFunctionProto.newBuilder().setName(name).setBody(program(source)).build();
    }

    private SettingsProto settings() throws Exception {
        var form = ConfigFormsProto.newBuilder().setType("radio").setTitle("").setDescription("")
            .setReadProgram(program("v.mode")).setWriteProgram(program("v.mode=t.value")).setStep(0).setMin(0).setMax(0)
            .addLabels(ConfigLabelProto.newBuilder().setName("next").setActionProgram(program("v.mode=1")).build())
            .build();
        return SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("").setDisablePreviewRotation(false)
            .addExtraAnimationButtons(ExtraAnimationButtonProto.newBuilder().setId("mode").setName("").setSound("")
                .addConfigForms(form).build())
            .build();
    }

    private ProgramProto program(String source) throws Exception {
        return EncodedProgramMapper.toProto(frontend
            .compile(source, MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("test/program"))
            .program());
    }
}
