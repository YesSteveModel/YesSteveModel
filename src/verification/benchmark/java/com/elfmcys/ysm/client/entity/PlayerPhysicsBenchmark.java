package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.ArrayStorage;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.client.animation.AnimationParallelTicker.PassengerTransform;
import com.elfmcys.ysm.client.animation.physics.PlayerTeleportState;
import com.elfmcys.ysm.format.schema.model.PhysicsDefinitionValidator;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.natives.physics.NativePhysicsAsset;
import com.elfmcys.ysm.natives.physics.NativePhysicsWorld;
import com.elfmcys.ysm.natives.physics.PhysicsDebugSnapshot;
import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.proto.mixel.asset.model.data.*;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.util.ProtoUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.elfmcys.ysm.natives.render.NativeModelState.*;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class PlayerPhysicsBenchmark {
    private static final int DYNAMIC = 15;
    private static final int NODES = DYNAMIC + 4;
    private static final int BONES = 96;
    private static final int STRIDE = AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;
    private static final float SPACING = 1.8F;
    private static final Object LEVEL = new Object();

    public enum Scene {
        ANIMATION(100, 60), STEP_1(1, 60), STEP_50(50, 60), STEP_100(100, 60), STEP_30(100, 30), STEP_10(100, 10),
        SHADOW(100, 60), STILL(100, 60), SCALE(100, 60), LONG_JUMP(100, 60), RIDING(100, 60), COMPOUND(100, 60),
        RESUME(100, 60), NO_PEER_CONTACT(100, 60);

        final int entities;
        final int hz;

        Scene(int entities, int hz) {
            this.entities = entities;
            this.hz = hz;
        }
    }

    @State(Scope.Benchmark)
    public static class Frames {
        @Param
        public Scene scene;

        private NativeBakedModel model;
        private final List<EntitySample> entities = new ArrayList<>();
        private final List<Callable<Void>> partitions = new ArrayList<>();
        private ExecutorService workers;
        private int frame = -1;
        private double seconds;
        private PassengerTransform passenger;

        @Setup(Level.Trial)
        public void open() throws Exception {
            prepare();
            try {
                advance();
                advance();
            } catch (Exception | Error failure) {
                close();
                throw failure;
            }
        }

        void prepare() throws Exception {
            NativeLibraryExtension.loadNativeLibrary();
            try (var input = new Inputs(scene == Scene.COMPOUND, scene != Scene.NO_PEER_CONTACT);
                var texture = NativeBuffer.allocate(4)) {
                var bake = NativeBakedModel.bake(input.geometry, BONES, texture, 1, 1, 29, false);
                model = bake.bakedModel();
                var bones = bake.sortedBoneIndices();
                if (scene == Scene.ANIMATION) {
                    addEntities(bones, null);
                } else {
                    try (var asset = input.compile()) {
                        verifyContact(asset, bones);
                        addEntities(bones, asset);
                    }
                }
                var count = Math.min(8, scene.entities);
                if (count > 1)
                    workers = Executors.newFixedThreadPool(count);
                for (int worker = 0; worker < count; worker++) {
                    var owned =
                        entities.subList(worker * scene.entities / count, (worker + 1) * scene.entities / count);
                    partitions.add(() -> {
                        for (var entity : owned)
                            entity.evaluate(model, scene, frame, seconds, passenger);
                        return null;
                    });
                }
            } catch (Exception | Error failure) {
                close();
                throw failure;
            }
        }

        private void addEntities(short[] bones, NativePhysicsAsset asset) {
            for (int i = 0; i < scene.entities; i++)
                entities.add(new EntitySample(bones, asset, scene == Scene.SHADOW));
        }

        private void verifyContact(NativePhysicsAsset asset, short[] bones) {
            try (var probe = new EntitySample(bones, asset, false)) {
                var world = probe.physics.world();
                world.setDebugContactCapture(true);
                int framesWithContact = 0;
                for (int i = 0; i < 60; i++) {
                    probe.evaluate(model, Scene.STILL, i, i / 60.0, null);
                    var snapshot = world.readDebugSnapshot(NODES, DYNAMIC, Integer::toString, Integer::toString);
                    if (snapshot == null || !snapshot.sampledLastStep())
                        continue;
                    boolean contact = false;
                    for (int slot = 0; slot < DYNAMIC; slot++)
                        contact |= snapshot.contact(slot, DYNAMIC) == PhysicsDebugSnapshot.Contact.TOUCHING;
                    if (contact)
                        framesWithContact++;
                }
                if (framesWithContact < 45)
                    throw new IllegalStateException("Contact workload did not stay in contact with its proxy");
            }
        }

        void advance() throws Exception {
            frame++;
            // 249 ms is still a continuous solve. Then resume the normal cadence.
            seconds += scene == Scene.RESUME && frame % 60 == 1 ? 0.249 : 1.0 / scene.hz;
            if (scene == Scene.RIDING) {
                float tilt = 0.25F * (float)Math.sin(seconds);
                passenger = new PassengerTransform(-0.35, tilt, -tilt);
            }
            if (workers == null) {
                partitions.get(0).call();
            } else {
                for (var finished : workers.invokeAll(partitions))
                    finished.get();
            }
        }

        @TearDown(Level.Trial)
        public void close() {
            if (workers != null) {
                workers.shutdown();
                boolean interrupted = false;
                while (!workers.isTerminated()) {
                    try {
                        if (!workers.awaitTermination(30, TimeUnit.SECONDS))
                            throw new IllegalStateException("A physics benchmark worker did not exit");
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
                if (interrupted)
                    Thread.currentThread().interrupt();
            }
            for (var entity : entities)
                entity.close();
            entities.clear();
            if (model != null)
                model.close();
        }
    }

    private static final class EntitySample implements AutoCloseable {
        final PlayerPhysicsSession physics;
        final NativeModelState body = NativeModelState.create();
        final NativeModelState shadow;
        final float[] attributes = new float[BONES * STRIDE];
        final int root;
        final int firstProxy;
        PlayerTeleportState teleport = PlayerTeleportState.NONE;

        EntitySample(short[] bones, NativePhysicsAsset asset, boolean withShadow) {
            physics = asset == null ? null : PlayerPhysicsSession.open("benchmark",
                new PlayerPhysicsSession.RecoveryGate(null, LEVEL), () -> NativePhysicsWorld.create(asset));
            shadow = withShadow ? NativeModelState.create() : null;
            int rootOffset = -1;
            int proxyOffset = -1;
            for (int i = 0; i < bones.length; i++) {
                int base = i * STRIDE;
                attributes[base + 6] = attributes[base + 7] = attributes[base + 8] = 1;
                attributes[base + 11] = 1;
                attributes[base + 12] = 0xffffff;
                attributes[base + 13] = 0xffff;
                if (bones[i] == DYNAMIC)
                    proxyOffset = base;
                else if (bones[i] == NODES)
                    rootOffset = base;
            }
            root = rootOffset;
            firstProxy = proxyOffset;
        }

        void evaluate(NativeBakedModel model, Scene scene, int frame, double seconds, PassengerTransform passenger) {
            float phase = (float)(seconds * 2 * Math.PI);
            boolean moving = scene != Scene.STILL;
            attributes[firstProxy + 4] = moving ? 0.12F * (float)Math.sin(phase) : 0;
            if (scene == Scene.SCALE) {
                float scale = 1.1F + 0.08F * (float)Math.sin(phase);
                attributes[root + 6] = attributes[root + 7] = attributes[root + 8] = scale;
            }
            if (physics == null) {
                check(body.extract(model, attributes));
                return;
            }

            var position = Vec3.ZERO;
            int tick = (int)(seconds * 20);
            if (scene == Scene.LONG_JUMP) {
                int interval = tick / 24;
                if (teleport.epoch() != interval)
                    teleport = new PlayerTeleportState(interval, true, interval * 24);
                double blend = Math.min((seconds * 20 - interval * 24) / 3, 1);
                double x = interval == 0 ? 0 : ((interval & 1) == 0 ? 24 * (1 - blend) : 24 * blend);
                position = new Vec3(x, 0, 0);
            }
            physics.invalidate(LEVEL, teleport, tick, position, seconds, frame);
            int mode = physics.select(seconds, frame, true);
            check(mode != PlayerPhysicsSession.ANIMATION_ONLY);
            float dt = mode == STEP ? (float)physics.secondsSinceSample(seconds) : 0;
            var rootPosition = position;
            Quaternionfc rootRotation = null;
            if (passenger != null) {
                var pose = EntityRootTransform.modelPose(180, false, null, 0, false, 0, passenger);
                rootPosition = pose.worldPosition(position, passenger.yOffset());
                rootRotation = pose.rotation();
            }
            int flags = extract(body, model, mode, dt, rootPosition, rootRotation);
            physics.accept(mode, flags, body.getRawJoltError(), LEVEL, teleport, tick, position, seconds, frame);
            if (flags != (mode == OBSERVE ? EXTRACT_OK : EXTRACT_OK | PHYSICS_ACCEPTED))
                throw new IllegalStateException("Physics update failed: " + physics.debugFailure());
            if (shadow != null)
                check(extract(shadow, model, OBSERVE, 0, rootPosition, rootRotation) == EXTRACT_OK);
        }

        private int extract(NativeModelState state, NativeBakedModel model, int mode, float dt, Vec3 position,
            Quaternionfc rotation) {
            if (rotation != null)
                return state.extractWithPhysics(model, physics.world(), attributes, mode, dt, position.x, position.y,
                    position.z, rotation.x(), rotation.y(), rotation.z(), rotation.w());
            return state.extractWithPhysics(model, physics.world(), attributes, mode, dt, position.x, position.y,
                position.z, 0, 0, 0, 1);
        }

        @Override
        public void close() {
            if (physics != null)
                physics.close();
            body.close();
            if (shadow != null)
                shadow.close();
        }
    }

    @State(Scope.Benchmark)
    public static class CompileInput {
        @Param({"false", "true"})
        public boolean compound;

        private Inputs input;

        @Setup(Level.Trial)
        public void open() throws Exception {
            NativeLibraryExtension.loadNativeLibrary();
            input = new Inputs(compound, true);
        }

        @TearDown(Level.Trial)
        public void close() {
            input.close();
        }
    }

    private static final class Inputs implements AutoCloseable {
        final ArrayStorage geometry;
        final ArrayStorage definition;

        Inputs(boolean compound, boolean peerContact) throws Exception {
            var geo = GeoModelProto.newBuilder().setCubes(ByteBuffer.allocate(0))
                .setProperties(GeoPropertiesProto.newBuilder().setTextureWidth(1).setTextureHeight(1).build());
            var physics = PhysicsDefinitionProto.newBuilder();
            for (int i = 0; i < DYNAMIC; i++) {
                String name = "piece" + i;
                float x = (i % 8 - 3.5F) * SPACING;
                float z = i < 8 ? -1.15F : 1.15F;
                geo.addBones(bone(name, "root", x, 1.5F, z));
                int proxy = 1 << (i < 8 ? 15 : 16);
                int mask = proxy | (peerContact ? 1 : 1 << i);
                var node = node(name, PhysicsNodeKind.DYNAMIC, mask).setMass(1).setLinearDamping(0.25F);
                node.addShapes(sphere(0, 0, 0, 1));
                // Sub-primitives stay away from the proxy-facing surface, preserving the nominal contact geometry.
                if (compound)
                    for (int j = 1; j < 32; j++)
                        node.addShapes(sphere((j % 4 - 1.5F) * 0.25F, 0.35F, (j / 4 - 3.5F) * 0.15F, 0.12F));
                physics.addNodes(node.build());
                String anchor = "anchor" + (i < 8 ? 0 : 1);
                physics.addConstraints(PhysicsConstraintProto.newBuilder().setId("joint" + i).setNodeA(anchor)
                    .setNodeB(name).setKind(PhysicsConstraintKind.POINT).setFrameA(frame(x, 0, 0))
                    .setFrameB(frame(0, 2, 0)).build());
            }
            for (int i = 0; i < 2; i++) {
                String name = "proxy" + i;
                float z = i == 0 ? -1.15F : 1.15F;
                geo.addBones(bone(name, "root", 0, 0, z));
                var node = node(name, PhysicsNodeKind.PROXY, 1 << (15 + i));
                if (compound) {
                    for (int j = 0; j < 16; j++)
                        node.addShapes(box((j - 7.5F) * 0.95F, 0.95F));
                } else {
                    node.addShapes(box(0, 15.2F));
                }
                physics.addNodes(node.build());
            }
            for (int i = 0; i < 2; i++) {
                String name = "anchor" + i;
                geo.addBones(bone(name, "root", 0, 3.5F, i == 0 ? -1.15F : 1.15F));
                physics.addNodes(
                    PhysicsNodeProto.newBuilder().setId(name).setBone(name).setKind(PhysicsNodeKind.ANCHOR).build());
            }
            geo.addBones(bone("root", "", 0, 0, 0));
            for (int i = NODES + 1; i < BONES; i++)
                geo.addBones(bone("ordinary" + i, "root", 0, 0, 0));
            var bakedGeo = geo.build();
            var compiledPhysics = physics.build();
            PhysicsDefinitionValidator.validate(compiledPhysics, bakedGeo, RenderTargetIds.PLAYER);
            geometry = ArrayBuffer.move(ProtoUtil.serializeToArray(bakedGeo));
            definition = ArrayBuffer.move(ProtoUtil.serializeToArray(compiledPhysics));
        }

        NativePhysicsAsset compile() throws Exception {
            return NativePhysicsAsset.compile(geometry, definition);
        }

        @Override
        public void close() {
            definition.close();
            geometry.close();
        }
    }

    private static BoneProto bone(String name, String parent, float x, float y, float z) {
        return BoneProto.newBuilder().setName(name).setParent(parent).addPivot(x, y, z).addRotate(0, 0, 0).build();
    }

    private static PhysicsNodeProto.Builder node(String name, PhysicsNodeKind kind, int mask) {
        return PhysicsNodeProto.newBuilder().setId(name).setBone(name).setKind(kind).setCollectionMask(mask);
    }

    private static ConstraintFrameProto frame(float x, float y, float z) {
        return ConstraintFrameProto.newBuilder().addPivotOffset(x, y, z).addAxisX(1, 0, 0).addAxisY(0, 1, 0)
            .addAxisZ(0, 0, 1).build();
    }

    private static CollisionShapeProto sphere(float x, float y, float z, float radius) {
        return CollisionShapeProto.newBuilder()
            .setSphere(SphereShapeProto.newBuilder().addCenter(x, y, z).setRadius(radius).build()).build();
    }

    private static CollisionShapeProto box(float x, float width) {
        return CollisionShapeProto.newBuilder()
            .setBox(
                BoxShapeProto.newBuilder().addCenter(x, 0, 0).addSize(width, 1.2F, 2).addRotation(0, 0, 0, 1).build())
            .build();
    }

    private static void check(boolean success) {
        if (!success)
            throw new IllegalStateException("Pose extraction failed");
    }

    @Benchmark
    public void frame(Frames frames) throws Exception {
        frames.advance();
    }

    @Benchmark
    public void compileAsset(CompileInput input) throws Exception {
        input.input.compile().close();
    }
}
