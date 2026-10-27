package com.elfmcys.ysm.expression.inventory;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.ProgramNode;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ModelProgramPreparationTest {
    private static final String PREFIX = "fallback/" + "a".repeat(64) + "/player/main/";
    private final MolangFrontend frontend = new MolangFrontend();

    @Test
    void onePreparationRetainsLocationsAndFormalRolesForEverySetAndTarget() throws Exception {
        var animation = animation("idle", "7");
        var source = ModelDataProto.newBuilder().putAnimationFiles("main", file(animation)).build();
        var strings = StringDataProto.newBuilder().addUserFunctions(function("local", "13")).build();
        for (var target : List.of("player", "projectile", "vehicle")) {
            for (var set : ExpressionSet.values()) {
                var prepared =
                    ModelProgramInventory.prepare(target, set, source, strings, null, MolangEventCatalog.production());
                assertEquals(2, prepared.locations().size());
                var inventory = prepared.collect();
                assertEquals(2, inventory.programs().size());
                assertEquals(2, inventory.counts().candidates());
                for (var binding : inventory.bindings()) {
                    assertEquals(target, binding.location().targetId());
                    assertEquals(set, binding.location().set());
                    assertEquals(binding.source().key(), prepared.locations().stream()
                        .filter(value -> value.location().equals(binding.location())).findFirst().orElseThrow().key());
                    var root = assertInstanceOf(ProgramNode.F32.class,
                        PortableProgramCodecs.v1().decode(binding.source().program()).roots().get(0));
                    assertEquals(binding.location().role() == ProgramRole.USER_FUNCTION ? 13F : 7F,
                        Float.intBitsToFloat(root.rawBits()));
                }
            }
        }
    }

    @Test
    void defaultSubsetRetainsOriginAndInputChangesUseCurrentPrograms() throws Exception {
        var idle = animation("idle", "7");
        var world = animation("world", "19");
        var defaults = ModelDataProto.newBuilder().putAnimationFiles("main", file(idle, world)).build();
        var functions = StringDataProto.newBuilder().addUserFunctions(function(PREFIX + "common", "11")).build();
        var subset = defaults.toBuilder().clearAnimationFiles().putAnimationFiles(PREFIX + "main", file(idle)).build();
        var prepared = ModelProgramInventory.prepare("projectile", ExpressionSet.FP_ARM, subset, functions, null,
            MolangEventCatalog.production());
        var inventory = prepared.collect();
        assertEquals(2, inventory.programs().size());
        assertTrue(inventory.bindings().stream().allMatch(
            value -> value.location().location().contains(PREFIX) && value.location().targetId().equals("projectile")));
        assertTrue(inventory.manifest().stream()
            .anyMatch(value -> value.location().equals("projectile/animation/" + PREFIX + "main/idle/blend-weight")));
        assertTrue(inventory.manifest().stream().noneMatch(value -> value.location().contains("/world/")));

        var changed =
            ModelDataProto.newBuilder().putAnimationFiles(PREFIX + "main", file(animation("idle", "29"))).build();
        var changedPrepared = ModelProgramInventory.prepare("projectile", ExpressionSet.FP_ARM, changed, functions,
            null, MolangEventCatalog.production());
        var sample = changedPrepared.collect().bindings().stream()
            .filter(value -> value.location().role() == ProgramRole.ANIMATION_SAMPLE).findFirst().orElseThrow();
        assertEquals(29F, Float.intBitsToFloat(assertInstanceOf(ProgramNode.F32.class,
            PortableProgramCodecs.v1().decode(sample.source().program()).roots().get(0)).rawBits()));
        assertNotEquals(prepared.sourceFingerprint(), changedPrepared.sourceFingerprint());
    }

    @Test
    void canonicalFingerprintCoversFullInputAndIsIndependentOfMapInsertionOrder() throws Exception {
        var a = file(animation("a", "1"));
        var b = file(animation("b", "2"));
        var first = ModelDataProto.newBuilder().putAnimationFiles("a", a).putAnimationFiles("b", b)
            .putGeoModels("body", ByteBuffer.wrap(new byte[] {1, 2})).build();
        var second = ModelDataProto.newBuilder().putAnimationFiles("b", b).putAnimationFiles("a", a)
            .putGeoModels("body", ByteBuffer.wrap(new byte[] {1, 2})).build();
        var functions = StringDataProto.newBuilder().addUserFunctions(function("one@sync", "1"))
            .addUserFunctions(function("two@sync", "2")).build();
        var reversed = StringDataProto.newBuilder().addUserFunctions(functions.userFunctions().get(1))
            .addUserFunctions(functions.userFunctions().get(0)).build();
        var settings = SettingsProto.newBuilder().setDefaultTexture("main").build();
        var fingerprint = ModelProgramInventory.sourceFingerprint(first, functions, settings);
        assertEquals(fingerprint, ModelProgramInventory.sourceFingerprint(second, functions, settings));
        assertNotEquals(fingerprint, ModelProgramInventory.sourceFingerprint(first, reversed, settings));
        assertNotEquals(fingerprint, ModelProgramInventory.sourceFingerprint(first, functions,
            settings.toBuilder().setDefaultTexture("other").build()));
        assertNotEquals(fingerprint, ModelProgramInventory.sourceFingerprint(
            first.toBuilder().putGeoModels("body", ByteBuffer.wrap(new byte[] {1, 3})).build(), functions, settings));
        assertNotEquals(fingerprint, ModelProgramInventory.sourceFingerprint(
            first.toBuilder().putAnimationFiles("b", file(animation("b", "3"))).build(), functions, settings));
    }

    @Test
    void preparedFingerprintAndManifestUseTheCurrentOriginComposition() throws Exception {
        var animation = animation("idle", "fn.common()");
        var functions = StringDataProto.newBuilder().addUserFunctions(function(PREFIX + "common", "11")).build();
        var source = ModelDataProto.newBuilder().putAnimationFiles(PREFIX + "main", file(animation)).build();
        var prepared = ModelProgramInventory.prepare("player", ExpressionSet.MAIN, source, functions, null,
            MolangEventCatalog.production());
        var inventory = prepared.collect();
        assertEquals(ModelProgramInventory.sourceFingerprint(source, functions, null), prepared.sourceFingerprint());
        assertEquals(ModelProgramInventory
            .collect("player", ExpressionSet.MAIN, source, functions, null, MolangEventCatalog.production()).manifest(),
            inventory.manifest());
        assertEquals(ModelProgramInventory.locate("player", ExpressionSet.MAIN, source, functions, null,
            MolangEventCatalog.production()), prepared.locations());
        assertTrue(inventory.manifest().stream().filter(value -> value.role() == ProgramRole.ANIMATION_SAMPLE).allMatch(
            value -> value.referencedSymbols().stream().noneMatch(name -> name.contains("ysm.user-function:common:"))));
        var currentFunctions = StringDataProto.newBuilder().addUserFunctions(function("common", "41")).build();
        var current = ModelProgramInventory.prepare("player", ExpressionSet.MAIN, source, currentFunctions, null,
            MolangEventCatalog.production());
        assertNotEquals(prepared.sourceFingerprint(), current.sourceFingerprint());
        assertTrue(current.collect().manifest().stream().filter(value -> value.role() == ProgramRole.ANIMATION_SAMPLE)
            .anyMatch(value -> value.referencedSymbols().stream()
                .anyMatch(name -> name.contains("ysm.user-function:common:"))));
    }

    private AnimationProto animation(String name, String source) throws Exception {
        return AnimationProto.newBuilder().setName(name)
            .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(program(source)).build()).build();
    }

    private static AnimationFileProto file(AnimationProto... animations) {
        var result = AnimationFileProto.newBuilder();
        for (var animation : animations)
            result.addAnimations(animation);
        return result.build();
    }

    private UserFunctionProto function(String name, String source) throws Exception {
        return UserFunctionProto.newBuilder().setName(name).setBody(program(source)).build();
    }

    private ProgramProto program(String source) throws Exception {
        return EncodedProgramMapper.toProto(frontend.compile(source, MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("source-preparation-test")).program());
    }
}
