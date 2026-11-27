package com.elfmcys.ysm.natives.render;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.schema.model.PhysicsDefinitionValidator;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.natives.physics.NativePhysicsAsset;
import com.elfmcys.ysm.natives.physics.NativePhysicsWorld;
import com.elfmcys.ysm.natives.physics.PhysicsDebugSnapshot;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AxisConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollisionShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ConstraintFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.FrequencyDampingProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoPropertiesProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LimitKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsDefinitionProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SphereShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringParametersProto;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.util.ProtoUtil;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.elfmcys.ysm.natives.render.NativeModelState.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class NativePhysicsExtractionIntegrationTest {
    private static final int STRIDE = AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;
    private static final float DT = 1 / 30F;
    private static final int ACCEPTED = EXTRACT_OK | PHYSICS_ACCEPTED;
    private static final ConstraintFrameProto FRAME = ConstraintFrameProto.newBuilder().addPivotOffset(0, 0, 0)
        .addAxisX(1, 0, 0).addAxisY(0, 1, 0).addAxisZ(0, 0, 1).build();

    @Test
    void rejectsInputsBeforeAdvancingAndObservesThePreviousSample() throws Exception {
        withWorld(1, rig -> {
            rig.world.setDebugContactCapture(true);
            rig.world.setDebugContactCapture(false);
            assertEquals(ACCEPTED, rig.extract(RESET));
            var reset = rig.pose();
            assertTrue(rig.state.extract(rig.model, rig.attributes));
            assertTrue(reset.equals(rig.pose(), 1e-6F));
            int count = rig.attributes.length / STRIDE;
            var slots = new long[5];
            var locators = new short[count];
            assertEquals(0,
                rig.raw(rig.world.get(), Arrays.copyOf(rig.attributes, count * STRIDE - 1), locators, slots));
            assertEquals(0,
                rig.raw(rig.world.get(), Arrays.copyOf(rig.attributes, count * STRIDE + 1), locators, slots));
            assertEquals(0, rig.raw(rig.world.get(), rig.attributes, new short[count - 1], slots));
            assertEquals(0, rig.raw(rig.world.get(), rig.attributes, locators, new long[4]));
            assertEquals(0, rig.raw(0, rig.attributes, locators, slots));
            assertEquals(EXTRACT_OK, rig.extract(OBSERVE));
            assertEquals(reset, rig.pose());
            assertEquals(ACCEPTED, rig.extract(STEP));
            assertTrue(rig.pose().m31() < reset.m31() - 1e-4F);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {12, 13})
    void observerRetryPublishesTheAlreadyCommittedStep(int channel) throws Exception {
        withWorld(1, rig -> {
            assertEquals(ACCEPTED, rig.extract(RESET));
            var reset = rig.pose();
            int index = rig.dynamic * STRIDE + channel;
            float original = rig.attributes[index];
            rig.attributes[index] = channel == 12 ? 0.5F : 0x10FF;
            assertEquals(PHYSICS_ACCEPTED, rig.extract(STEP));
            assertFalse(rig.state.isValid());
            assertNull(rig.state.getBonePoses());
            assertEquals(0, rig.state.getRawJoltError());
            rig.attributes[index] = original;
            assertEquals(EXTRACT_OK, rig.extract(OBSERVE));
            var stepped = rig.pose();
            assertTrue(stepped.m31() < reset.m31() - 1e-4F);
            assertEquals(EXTRACT_OK, rig.extract(OBSERVE));
            assertEquals(stepped, rig.pose());
        });
    }

    @Test
    void failedSimulationStillExtractsAnimationAndClearsPriorCapacityBits() throws Exception {
        withWorld(50, rig -> {
            assertEquals(ACCEPTED, rig.extract(RESET));
            assertEquals(ACCEPTED | (CAPACITY_EXCEEDED << OUTCOME_SHIFT), rig.extract(STEP));
            assertNotEquals(0, rig.state.getRawJoltError());
            var degraded = rig.pose();
            assertTrue(rig.state.extract(rig.model, rig.attributes));
            assertEquals(degraded, rig.pose());
            assertEquals(ACCEPTED, rig.extract(RESET));
            int base = rig.dynamic * STRIDE;
            rig.attributes[base + 12] = -1;
            int count = rig.attributes.length / STRIDE;
            var slots = new long[5];
            var locators = new short[count];
            assertEquals(PHYSICS_ACCEPTED | (CAPACITY_EXCEEDED << OUTCOME_SHIFT),
                rig.raw(rig.world.get(), rig.attributes, locators, slots));
            assertNotEquals(0, slots[4]);
            for (int i = 0; i < count; ++i) {
                rig.attributes[i * STRIDE + 9] = 1;
            }
            rig.attributes[base + 9] = 0;
            assertEquals(PHYSICS_ACCEPTED, rig.extract(RESET));
            // The Y guide is free. Its finite animation target makes the local correction overflow after division by
            // scale.
            rig.attributes[base + 4] = Float.MAX_VALUE / 2;
            rig.attributes[base + 6] = rig.attributes[base + 7] = rig.attributes[base + 8] = 0.01F;
            assertEquals(PHYSICS_ACCEPTED | (NON_FINITE_STATE << OUTCOME_SHIFT),
                rig.raw(rig.world.get(), rig.attributes, locators, slots));
            assertArrayEquals(new long[5], slots);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void scalingPrimaryAnchorRetiresStrandOnlyWithoutAnEffectiveBackup(boolean fullTurn) throws Exception {
        float halfAngle = (float)(fullTurn ? Math.PI : Math.PI / 2);
        withBackupJoint(halfAngle, true, rig -> {
            rig.world.setDebugContactCapture(true);
            assertEquals(ACCEPTED, rig.extract(RESET));
            for (int frame = 2; frame <= 3; ++frame) {
                assertEquals(ACCEPTED, rig.extract(STEP));
                assertEquals(PhysicsDebugSnapshot.Contact.INELIGIBLE, rig.backupSnapshot().contact(0, 2));
            }
            var before = rig.backupSnapshot().nodes().get(2);
            assertTrue(before.active());
            assertEquals(0, before.pose().position().y(), 1e-5F);

            rig.attributes[rig.offset("root") + 6] = 2;
            rig.attributes[rig.offset("strand") + 4] = 12;
            assertTrue(rig.state.extract(rig.model, rig.attributes));
            var animationPose = rig.pose();
            for (int frame = 4; frame < 65; ++frame) {
                assertEquals(ACCEPTED, rig.extract(STEP), "frame " + frame);
                var strand = rig.backupSnapshot().nodes().get(2);
                if (fullTurn) {
                    assertFalse(strand.active(), "frame " + frame);
                    assertEquals(PhysicsDebugSnapshot.Deactivation.LOST_ANCHOR, strand.deactivation());
                    assertEquals("primary", strand.culpritName());
                    assertEquals(12 / 16F, strand.pose().position().y(), 1e-5F);
                    assertTrue(animationPose.equals(rig.pose(), 1e-6F), "frame " + frame);
                } else {
                    assertTrue(strand.active(), "frame " + frame);
                    assertEquals(PhysicsDebugSnapshot.Deactivation.NONE, strand.deactivation());
                }
            }
            if (!fullTurn) {
                assertTrue(rig.backupSnapshot().nodes().get(2).pose().position().y() < -1F);
            }

            rig.attributes[rig.offset("root") + 6] = 1;
            rig.attributes[rig.offset("root") + 4] = 8;
            rig.attributes[rig.offset("strand") + 4] = 8;
            assertEquals(ACCEPTED, rig.extract(STEP));
            var rejoined = rig.backupSnapshot().nodes().get(2);
            assertTrue(rejoined.active());
            assertEquals(PhysicsDebugSnapshot.Deactivation.NONE, rejoined.deactivation());
            if (fullTurn) {
                assertEquals(8 / 16F, rejoined.pose().position().y(), 1e-5F);
                assertEquals(ACCEPTED, rig.extract(STEP));
                assertEquals(rejoined.pose(), rig.backupSnapshot().nodes().get(2).pose());
            }
        });
    }

    @Test
    void fullTurnBackupAllowsContactWhenItsVetoIsOff() throws Exception {
        withBackupJoint((float)Math.PI, false, rig -> {
            rig.world.setDebugContactCapture(true);
            assertEquals(ACCEPTED, rig.extract(RESET));
            assertEquals(ACCEPTED, rig.extract(STEP));
            assertEquals(PhysicsDebugSnapshot.Contact.TOUCHING, rig.backupSnapshot().contact(0, 2));
        });
    }

    private record Rig(NativeBakedModel model, NativePhysicsWorld world, NativeModelState state, float[] attributes,
        int dynamic, List<String> sortedBones) {
        int extract(int mode) {
            return state.extractWithPhysics(model, world, attributes, mode, DT, 0, 0, 0, 0, 0, 0, 1);
        }

        int raw(long worldHandle, float[] input, short[] locators, long[] slots) {
            return NativeModelState.nExtractWithPhysics(state.get(), model.get(), worldHandle, input, locators, slots,
                STEP, DT, 0, 0, 0, 0, 0, 0, 1);
        }

        Matrix4f pose() {
            assertTrue(state.isValid());
            assertEquals(dynamic, Short.toUnsignedInt(state.getLocatorBoneIndices().getShort(0)));
            return state.getBonePoses().getPose(dynamic, new Matrix4f());
        }

        int offset(String bone) {
            int index = sortedBones.indexOf(bone);
            assertTrue(index >= 0, bone);
            return index * STRIDE;
        }

        PhysicsDebugSnapshot backupSnapshot() {
            var snapshot = world.readDebugSnapshot(3, 2, sortedBones::get, List.of("primary", "backup")::get);
            assertNotNull(snapshot);
            return snapshot;
        }
    }

    private static void withBackupJoint(float halfAngle, boolean veto, Consumer<Rig> test) throws Exception {
        var geo = geometry().addBones(bone("strand", ""),
            bone("backup", "").toBuilder().clearPivot().addPivot(3, 0, 0).build(), bone("root", "")).build();
        var shape = sphere();
        var spring = SpringParametersProto.newBuilder()
            .setFrequencyDamping(FrequencyDampingProto.newBuilder().setFrequency(2).setDampingRatio(0.5F).build())
            .build();
        var axes = AxisConfigProto.newBuilder().setLimit(LimitKind.SOFT).setMin(-halfAngle).setMax(halfAngle)
            .setSoftLimitSpring(spring).build();
        var definition = PhysicsDefinitionProto.newBuilder().addGravity(0, -10, 0)
            .addNodes(
                PhysicsNodeProto.newBuilder().setId("backup").setBone("backup").setKind(PhysicsNodeKind.PROXY)
                    .addShapes(shape).build(),
                PhysicsNodeProto.newBuilder().setId("anchor").setBone("root").setKind(PhysicsNodeKind.ANCHOR).build(),
                PhysicsNodeProto.newBuilder().setId("strand").setBone("strand").setKind(PhysicsNodeKind.DYNAMIC)
                    .setMass(1).setLinearDamping(0).setAngularDamping(0).addShapes(shape).build())
            .addConstraints(
                PhysicsConstraintProto.newBuilder().setId("primary").setNodeA("anchor").setNodeB("strand")
                    .setKind(PhysicsConstraintKind.FIXED).setFrameA(FRAME).setFrameB(FRAME).build(),
                PhysicsConstraintProto.newBuilder().setId("backup").setNodeA("backup").setNodeB("strand")
                    .setKind(PhysicsConstraintKind.GENERIC).setFrameA(FRAME).setFrameB(FRAME).setAngularX(axes)
                    .setDisableCollisions(veto).build())
            .build();
        PhysicsDefinitionValidator.validate(definition, geo, "player");
        withWorld(geo, definition, test);
    }

    private static void withWorld(int bodies, Consumer<Rig> test) throws Exception {
        var geo = geometry();
        var definition = PhysicsDefinitionProto.newBuilder().addGravity(0, -10, 0).addNodes(
            PhysicsNodeProto.newBuilder().setId("anchor").setBone("root").setKind(PhysicsNodeKind.ANCHOR).build());
        var sphere = sphere();
        for (int i = 0; i < bodies; ++i) {
            String name = "strand" + i;
            geo.addBones(bone(name, "carrier"));
            definition.addNodes(
                PhysicsNodeProto.newBuilder().setId(name).setBone(name).setKind(PhysicsNodeKind.DYNAMIC).setMass(1)
                    .setLinearDamping(0).setAngularDamping(0).setDisableWhenHidden(true).addShapes(sphere).build());
            definition.addConstraints(PhysicsConstraintProto.newBuilder().setId("guide" + i).setNodeA("anchor")
                .setNodeB(name).setKind(PhysicsConstraintKind.GENERIC).setFrameA(FRAME).setFrameB(FRAME)
                .setLinearX(AxisConfigProto.newBuilder().setLimit(LimitKind.LOCKED).build()).build());
        }
        geo.addBones(bone("unused", ""), bone("carrier", "root"), bone("root", ""));
        withWorld(geo.build(), definition.build(), test);
    }

    private static void withWorld(GeoModelProto geo, PhysicsDefinitionProto definition, Consumer<Rig> test)
        throws Exception {
        var bytes = ProtoUtil.serializeToArray(definition);
        int count = geo.bones().size();
        try (var geometry = ArrayBuffer.move(ProtoUtil.serializeToArray(geo));
            var physics = NativeBuffer.allocate(bytes.length); var texture = NativeBuffer.allocate(4)) {
            physics.nio().put(0, bytes);
            var baked = NativeBakedModel.bake(geometry, count, texture, 1, 1, 29, false);
            try (var model = baked.bakedModel(); var state = NativeModelState.create()) {
                NativePhysicsWorld world;
                try (var asset = NativePhysicsAsset.compile(geometry, physics)) {
                    world = NativePhysicsWorld.create(asset);
                }
                try (world) {
                    assertNotNull(world);
                    int dynamic = -1;
                    var attributes = new float[count * STRIDE];
                    var sortedBones = new String[count];
                    for (int i = 0; i < count; ++i) {
                        int base = i * STRIDE;
                        attributes[base + 6] = attributes[base + 7] = attributes[base + 8] = 1;
                        attributes[base + 12] = 0xffffff;
                        attributes[base + 13] = 0xffff;
                        int authorIndex = Short.toUnsignedInt(baked.sortedBoneIndices()[i]);
                        sortedBones[i] = geo.bones().get(authorIndex).name();
                        if (authorIndex == 0) {
                            dynamic = i;
                            attributes[base + 11] = 1;
                        } else if (sortedBones[i].equals("carrier")) {
                            attributes[base + 4] = 8;
                        }
                    }
                    assertTrue(dynamic >= 0);
                    test.accept(new Rig(model, world, state, attributes, dynamic, List.of(sortedBones)));
                }
            }
        }
    }

    private static GeoModelProto.Builder geometry() {
        return GeoModelProto.newBuilder()
            .setProperties(GeoPropertiesProto.newBuilder().setTextureWidth(16).setTextureHeight(16).build())
            .setCubes(ByteBuffer.allocate(0));
    }

    private static CollisionShapeProto sphere() {
        return CollisionShapeProto.newBuilder()
            .setSphere(SphereShapeProto.newBuilder().addCenter(0, 0, 0).setRadius(2).build()).build();
    }

    private static BoneProto bone(String name, String parent) {
        return BoneProto.newBuilder().setName(name).setParent(parent).addPivot(0, 0, 0).addRotate(0, 0, 0).build();
    }
}
