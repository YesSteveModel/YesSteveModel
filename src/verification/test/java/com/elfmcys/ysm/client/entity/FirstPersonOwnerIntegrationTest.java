package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.client.event.RenderFirstPlayerBackground;
import com.elfmcys.ysm.client.event.ReplacePlayerHandRenderEvent;
import com.elfmcys.ysm.client.renderer.CustomFirstPersonArmRenderer;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.event.api.SpecialPlayerRenderEvent;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.InvocationInputs;
import com.elfmcys.ysm.expression.host.builtin.PositionComponent;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.geo.CustomTranslucentRenderType;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import com.elfmcys.ysm.natives.render.RenderContextType;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.util.NativeLibUtil;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Timer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderArmEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@EnabledIfEnvironmentVariable(named = "YSM_FIRST_PERSON_FIXTURES", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class FirstPersonOwnerIntegrationTest {
    private static final Path FIXTURES = Path.of(System.getenv("YSM_FIRST_PERSON_FIXTURES"));
    private static final int STRIDE = AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;
    private static final ForgeConfigSpec.BooleanValue ORIGINAL_MODEL = ClientConfig.DISABLE_SELF_MODEL;
    private static final ForgeConfigSpec.BooleanValue ORIGINAL_HANDS = ClientConfig.DISABLE_SELF_HANDS;
    private static final ForgeConfigSpec.BooleanValue ORIGINAL_OTHER = ClientConfig.DISABLE_OTHER_MODEL;
    private static final ForgeConfigSpec.DoubleValue ORIGINAL_SOUND_VOLUME = ClientConfig.SOUND_VOLUME;
    private static Object originalMinecraft;
    private static Object originalRenderer;
    private static Object originalRenderThread;
    private static boolean originalAvailable;
    private static boolean originalBootstrapped;
    private static TestLocator locator;
    private static Object originalPlayerLocator;
    FakePlayer player;
    PlayerAnimatableCapability cap;
    CustomFirstPersonArmRenderer renderer;

    @BeforeAll
    static void initializeHost() throws Exception {
        SharedConstants.tryDetectVersion();
        var gate = field(Bootstrap.class, "isBootstrapped");
        originalBootstrapped = gate.getBoolean(null);
        gate.setBoolean(null, true);
        BuiltInRegistries.REGISTRY.size();
        Class.forName(LocalPlayer.class.getName());
        originalMinecraft = field(Minecraft.class, "instance").get(null);
        originalRenderer = field(RegisterEntityRenderersEvent.class, "CUSTOM_FIRST_PERSON_RENDERER").get(null);
        originalRenderThread = field(RenderSystem.class, "renderThread").get(null);
        originalAvailable = field(NativeLibUtil.class, "AVAILABLE").getBoolean(null);
        field(RenderSystem.class, "renderThread").set(null, Thread.currentThread());
        field(NativeLibUtil.class, "AVAILABLE").setBoolean(null, true);
        var minecraft = allocate(Minecraft.class);
        var options = allocate(Options.class);
        field(Options.class, "bobView").set(options, OptionInstance.createBoolean("bobView", false));
        field(Minecraft.class, "options").set(minecraft, options);
        field(Minecraft.class, "timer").set(minecraft, new Timer(20, 0));
        field(Minecraft.class, "instance").set(null, minecraft);
        MinecraftForge.EVENT_BUS.start();
        var registered = com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorTypeRegistry
            .get(new ResourceLocation("ysm", "t2_fixture"));
        locator = registered == null ? new TestLocator() : (TestLocator)registered;
        var playerLocatorType = com.elfmcys.ysm.client.model.locator.PlayerLocator.class;
        originalPlayerLocator = field(playerLocatorType, "INSTANCE").get(null);
        if (originalPlayerLocator == null) {
            var value = com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorTypeRegistry
                .get(ResourceLocation.tryParse("ysm:player"));
            if (value == null) {
                var constructor = playerLocatorType.getDeclaredConstructor();
                constructor.setAccessible(true);
                value = constructor.newInstance();
                var freeze = GeoLocatorType.class.getDeclaredMethod("freeze");
                freeze.setAccessible(true);
                freeze.invoke(value);
            }
            field(playerLocatorType, "INSTANCE").set(null, value);
        }
    }

    private static java.lang.reflect.Method playerStateReceiver() throws Exception {
        // Standalone JVM tests supply Forge's capability registration; receive code is unchanged.
        var provider = "com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider";
        var receiver = "com.elfmcys.ysm.network.forge.PlayerStateHandler";
        var loader = new ClassLoader(FirstPersonOwnerIntegrationTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(provider) && !name.equals(receiver) && !name.startsWith(receiver + "$"))
                    return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    var loaded = findLoadedClass(name);
                    if (loaded != null)
                        return loaded;
                    try (var bytes = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] code = java.util.Objects.requireNonNull(bytes).readAllBytes();
                        if (name.equals(provider)) {
                            var node = new org.objectweb.asm.tree.ClassNode();
                            new org.objectweb.asm.ClassReader(code).accept(node, 0);
                            var initialization = node.methods.stream().filter(method -> method.name.equals("<clinit>"))
                                .findFirst().orElseThrow();
                            initialization.instructions.clear();
                            initialization.tryCatchBlocks.clear();
                            if (initialization.localVariables != null)
                                initialization.localVariables.clear();
                            initialization.instructions
                                .add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
                            var writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
                            node.accept(writer);
                            code = writer.toByteArray();
                        }
                        var result = defineClass(name, code, 0, code.length);
                        if (resolve)
                            resolveClass(result);
                        return result;
                    } catch (java.io.IOException failure) {
                        throw new ClassNotFoundException(name, failure);
                    }
                }
            }
        };
        var capability = net.minecraftforge.common.capabilities.Capability.class.getDeclaredConstructor(String.class);
        capability.setAccessible(true);
        Class.forName(provider, true, loader).getField("CAP").set(null,
            capability.newInstance(PlayerAnimatableCapability.class.getName().replace('.', '/')));
        var method = Class.forName(receiver, true, loader).getDeclaredMethod("applyClientUpdate",
            com.elfmcys.ysm.proto.network.PlayerStateUpdateProto.class);
        method.setAccessible(true);
        return method;
    }

    @AfterAll
    static void restoreHost() throws Exception {
        MinecraftForge.EVENT_BUS.shutdown();
        ClientConfig.DISABLE_SELF_MODEL = ORIGINAL_MODEL;
        ClientConfig.DISABLE_SELF_HANDS = ORIGINAL_HANDS;
        ClientConfig.DISABLE_OTHER_MODEL = ORIGINAL_OTHER;
        ClientConfig.SOUND_VOLUME = ORIGINAL_SOUND_VOLUME;
        field(Minecraft.class, "instance").set(null, originalMinecraft);
        field(RegisterEntityRenderersEvent.class, "CUSTOM_FIRST_PERSON_RENDERER").set(null, originalRenderer);
        field(RenderSystem.class, "renderThread").set(null, originalRenderThread);
        field(NativeLibUtil.class, "AVAILABLE").setBoolean(null, originalAvailable);
        field(Bootstrap.class, "isBootstrapped").setBoolean(null, originalBootstrapped);
        field(com.elfmcys.ysm.client.model.locator.PlayerLocator.class, "INSTANCE").set(null, originalPlayerLocator);
        RenderFirstPlayerBackground.reset();
    }

    @BeforeEach
    void createHost() throws Exception {
        var builder = new ForgeConfigSpec.Builder();
        ClientConfig.DISABLE_SELF_MODEL = builder.define("model", false);
        ClientConfig.DISABLE_SELF_HANDS = builder.define("hands", false);
        ClientConfig.DISABLE_OTHER_MODEL = builder.define("other", false);
        ClientConfig.SOUND_VOLUME = builder.defineInRange("volume", 100.0, 0.0, 100.0);
        builder.build().setConfig(CommentedConfig.inMemory());
        player = allocate(FakePlayer.class);
        field(net.minecraft.world.entity.Entity.class, "position").set(player, net.minecraft.world.phys.Vec3.ZERO);
        field(net.minecraft.world.entity.LivingEntity.class, "walkAnimation").set(player,
            new net.minecraft.world.entity.WalkAnimationState());
        Minecraft.getInstance().player = player;
        cap = new PlayerAnimatableCapability(player);
        renderer = new CustomFirstPersonArmRenderer();
        field(RegisterEntityRenderersEvent.class, "CUSTOM_FIRST_PERSON_RENDERER").set(null, renderer);
        RenderFirstPlayerBackground.reset();
    }

    @AfterEach
    void releaseHost() {
        com.elfmcys.ysm.client.animation.AnimationParallelTicker.waitAll();
        renderer.release();
        cap.retire();
        RenderFirstPlayerBackground.reset();
    }

    static void startAsync(AnimatableEntity<?> owner, float partial) {
        if (!com.elfmcys.ysm.client.animation.AnimationParallelTicker.isInvocationActive())
            com.elfmcys.ysm.client.animation.AnimationParallelTicker.scheduleAll(partial);
        if (!owner.hasPendingUpdate())
            com.elfmcys.ysm.client.animation.AnimationParallelTicker.schedule(owner, partial);
    }

    @Test
    void synchronousAnimationPassSharesItsReadsAndAlwaysWithdrawsTheLocalView(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var file = root.resolve("animations");
            java.nio.file.Files.write(file, new byte[] {3, 7, 9});
            var opens = new java.util.concurrent.atomic.AtomicInteger();
            var closes = new java.util.concurrent.atomic.AtomicInteger();
            var chunks = new com.elfmcys.ysm.format.schema.file.FileChunkDataSource(file, 3, () -> {
                var channel = java.nio.channels.FileChannel.open(file);
                opens.incrementAndGet();
                return (java.nio.channels.SeekableByteChannel)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {java.nio.channels.SeekableByteChannel.class}, (proxy, method, args) -> {
                        try {
                            var value = method.invoke(channel, args);
                            if (method.getName().equals("close"))
                                closes.incrementAndGet();
                            return value == channel ? proxy : value;
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            });
            java.util.function.Supplier<com.elfmcys.ysm.model.resource.client.AnimationStore> create =
                () -> com.elfmcys.ysm.model.resource.client.AnimationStore.lazyBound(List.of("a", "b", "c"),
                    new com.elfmcys.ysm.model.resource.client.AnimationStore.AnimationLoader() {
                        @Override
                        public Animation load(String name) throws Exception {
                            return read(chunks, name);
                        }

                        @Override
                        public com.elfmcys.ysm.model.resource.client.AnimationStore.AnimationLoader inReadPhase(
                            com.elfmcys.ysm.format.schema.file.ReadPhaseSet phases) throws java.io.IOException {
                            var phase = phases.acquire(chunks, chunks::openReadPhase);
                            return name -> read(phase, name);
                        }

                        private Animation read(com.elfmcys.ysm.format.schema.file.ChunkDataSource source, String name)
                            throws java.io.IOException {
                            int offset = name.charAt(0) - 'a';
                            byte value = new byte[] {3, 7, 9}[offset];
                            var hash = com.elfmcys.ysm.model.storage.ModelHashing
                                .blake3(java.nio.ByteBuffer.wrap(new byte[] {value})).bytes();
                            var descriptor = new com.elfmcys.ysm.format.container.AssetContainerView.ChunkInfo(name, "",
                                offset, 1, 0, 0, 0, 0, hash);
                            {
                                var bytes = source.readPayload(descriptor, com.elfmcys.ysm.buffer.BufferType.ARRAY);
                                assertEquals(value, bytes.nio().get());
                            }
                            return new Animation(name, 1, com.elfmcys.ysm.geckolib3.core.builder.LoopType.PLAY_ONCE,
                                null, List.of(), List.of(), List.of());
                        }
                    }, null, ignored -> com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate.none());
            var body = new Timeline(player);
            try {
                body.install(fixture.model, module);
                body.readBatchStore = create.get();
                body.beforeAnimation = () -> {
                    assertNotNull(body.getAnimation("a"));
                    assertNotNull(body.getAnimation("b"));
                    assertEquals(1, opens.get());
                    assertEquals(0, closes.get());
                };
                body.advance(0.25f);
                assertEquals(1, closes.get());
                assertNotNull(body.getAnimation("c"));
                assertEquals(2, opens.get(), "A read outside the pass needs a new phase");
                assertEquals(2, closes.get());
                body.readBatchStore = create.get();
                body.beforeAnimation = () -> {
                    assertNotNull(body.getAnimation("a"));
                    assertNotNull(body.getAnimation("b"));
                    throw new java.util.concurrent.CancellationException("cancel pass");
                };
                player.tickCount++;
                body.advance(0.75f);
                assertEquals(3, opens.get());
                assertEquals(3, closes.get());
                assertNotNull(body.getAnimation("c"));
                assertEquals(4, opens.get());
                assertEquals(4, closes.get());
            } finally {
                body.retire();
            }
        }
    }

    @Test
    void namedGlowIsLocalAndDynamicWritesPersistWhileBackgroundKeepsItsDeclaration() throws Exception {
        try (var fixture = new Fixture("parts-a", locator, false, "Outside", "LeftArmChild1", "BackgroundChild1");
            var replacement = new Fixture("parts-b", locator, false, "Outside", "LeftArmChild1", "BackgroundChild1");
            var full = NativeModelState.create()) {
            var animated = new AnimatedGeoModel(fixture.model);
            var state = new FirstPersonModelState();
            try {
                state.bind(target("glow"), fixture.model);
                assertTrue(full.extract(fixture.model.bakedModel(), animated.getBoneAttributes()));
                var output = new Probe();
                assertTrue(NativeRenderer.render(output.consumer, new PoseStack().last(), full,
                    full.getTotalVertexCount(), 0x00300070, 456, -1, RenderContextType.LEVEL));
                assertMarkers(output, 0, 1, 2, 3, 4, 5, 6);
                for (var vertex : output.vertices) {
                    if ((int)vertex[5] != 0) {
                        assertEquals(marker(vertex) == 1 || marker(vertex) == 5 ? 0x00F000F0 : 0x00300070,
                            (int)vertex[10]);
                        assertEquals(456, (int)vertex[9]);
                    }
                }

                var originalCompatibility = ClientConfig.USE_COMPATIBILITY_RENDERER;
                try (var region = NativeBuffer.allocate(full.getTotalVertexCount() * 36)) {
                    var builder = new ForgeConfigSpec.Builder();
                    ClientConfig.USE_COMPATIBILITY_RENDERER = builder.define("compatibility", false);
                    builder.build().setConfig(CommentedConfig.inMemory());
                    var advanced = new int[1];
                    var direct = (VertexConsumer)Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                        new Class<?>[] {VertexConsumer.class, com.elfmcys.ysm.accessor.VertexBufferAccessor.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "ysm$ok" -> true;
                            case "ysm$vertexFormat" -> com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY;
                            case "ysm$reserve" -> com.elfmcys.ysm.buffer.BorrowedNativeBuffer.of(region,
                                region.nio(), 0, (int)args[0] * 36);
                            case "ysm$advance" -> {
                                advanced[0] += (int)args[0];
                                yield null;
                            }
                            default -> throw new AssertionError(method.getName());
                        });
                    assertTrue(NativeRenderer.render(direct, new PoseStack().last(), full, full.getTotalVertexCount(),
                        0x00300070, 456, -1, RenderContextType.LEVEL));
                    assertEquals(output.vertices.size(), advanced[0]);
                    var bytes = region.nio().order(java.nio.ByteOrder.nativeOrder());
                    for (int i = 0; i < advanced[0]; i++) {
                        if ((int)output.vertices.get(i)[5] != 0) {
                            assertEquals((int)output.vertices.get(i)[10], bytes.getInt(i * 36 + 28));
                            assertEquals((int)output.vertices.get(i)[9], bytes.getInt(i * 36 + 24));
                        }
                    }
                } finally {
                    ClientConfig.USE_COMPATIBILITY_RENDERER = originalCompatibility;
                }

                for (var bone : animated.getSortedBones())
                    bone.setGlow(0);
                animated.setModelInplace(replacement.model);
                state.bind(target("glow-replacement"), replacement.model);
                for (int sample = 0; sample < 2; sample++) {
                    state.prepareArms(sample, true);
                    for (var part : List.of(FirstPersonPart.LEFT_ARM, FirstPersonPart.BACKGROUND)) {
                        var local = new Probe();
                        assertTrue(state.render(part, animated.getBoneAttributes(), local.consumer,
                            new PoseStack().last(), 0x00300070, 456, -1));
                        assertMarkers(local, part == FirstPersonPart.LEFT_ARM ? 0 : 4,
                            part == FirstPersonPart.LEFT_ARM ? 1 : 5);
                        for (var vertex : local.vertices) {
                            if ((int)vertex[5] != 0)
                                assertEquals(part == FirstPersonPart.LEFT_ARM ? 0
                                    : marker(vertex) == 5 ? 0x00F000F0 : 0x00300070, (int)vertex[10]);
                        }
                    }
                }
                for (var bone : animated.getSortedBones())
                    bone.setGlow(-1);
                state.invalidateArms();
                var restored = new Probe();
                assertTrue(state.render(FirstPersonPart.LEFT_ARM, animated.getBoneAttributes(), restored.consumer,
                    new PoseStack().last(), 0x00300070, 456, -1));
                assertMarkers(restored, 0, 1);
                for (var vertex : restored.vertices) {
                    if ((int)vertex[5] != 0)
                        assertEquals(0x00300070, (int)vertex[10]);
                }
            } finally {
                state.clear();
            }
        }
    }

    @Test
    void initialBackgroundSurvivesAnimatedWritesAndAlternatingRealOutput() throws Exception {
        try (var fixture = new Fixture("parts-a")) {
            var state = new FirstPersonModelState();
            try {
                state.bind(target("a"), fixture.model);
                var animated = new AnimatedGeoModel(fixture.model);
                for (var bone : animated.getSortedBones()) {
                    bone.setPosition(10, 20, 30);
                    bone.setRotation(1, 2, 3);
                    bone.setColor(0x11, 0x22, 0x33);
                    bone.setScale(2, 3, 4);
                }
                var background = new Probe();
                assertTrue(state.render(FirstPersonPart.BACKGROUND, animated.getBoneAttributes(), background.consumer,
                    new PoseStack().last(), 123, 0, -1));
                assertFalse(background.vertices.isEmpty());
                assertMarkers(background, 4, 5);
                // Geometry corners and matrices come from the frozen declaration, not a second extract.
                var oracle = JsonParser.parseString(Files.readString(FIXTURES.resolve("pose-oracles.json")))
                    .getAsJsonObject().getAsJsonObject("scenarios").getAsJsonObject("initial")
                    .getAsJsonObject("expected").getAsJsonObject("Background").getAsJsonObject("poses");
                var declaration = JsonParser.parseString(Files.readString(FIXTURES.resolve("parts-a.json")))
                    .getAsJsonObject().getAsJsonArray("bones");
                for (var vertex : background.vertices) {
                    if ((int)vertex[5] == 0)
                        continue;
                    int marker = marker(vertex);
                    var bone = declaration.get(marker == 4 ? 5 : 6).getAsJsonObject();
                    var cube = bone.getAsJsonArray("cubes").get(0).getAsJsonObject();
                    var rows = oracle.getAsJsonArray(bone.get("name").getAsString());
                    var matrix = new Matrix4f();
                    for (int row = 0; row < 4; row++)
                        for (int column = 0; column < 4; column++)
                            matrix.set(column, row, rows.get(row).getAsJsonArray().get(column).getAsFloat());
                    boolean cornerMatches = false;
                    for (int corner = 0; corner < 8; corner++) {
                        var point = new org.joml.Vector3f();
                        for (int axis = 0; axis < 3; axis++)
                            point.setComponent(axis, cube.getAsJsonArray("origin").get(axis).getAsFloat()
                                + ((corner >> axis) & 1) * cube.getAsJsonArray("size").get(axis).getAsFloat());
                        matrix.transformPosition(point);
                        cornerMatches |= Math.abs(point.x - vertex[0]) < 0.00002f
                            && Math.abs(point.y - vertex[1]) < 0.00002f && Math.abs(point.z - vertex[2]) < 0.00002f;
                    }
                    assertTrue(cornerMatches);
                }
                for (var part : List.of(FirstPersonPart.LEFT_ARM, FirstPersonPart.BACKGROUND, FirstPersonPart.RIGHT_ARM,
                    FirstPersonPart.BACKGROUND)) {
                    var output = new Probe();
                    assertTrue(state.render(part, animated.getBoneAttributes(), output.consumer, new PoseStack().last(),
                        123, 0, -1));
                    assertMarkers(output,
                        part == FirstPersonPart.LEFT_ARM ? 0 : part == FirstPersonPart.RIGHT_ARM ? 2 : 4,
                        part == FirstPersonPart.LEFT_ARM ? 1 : part == FirstPersonPart.RIGHT_ARM ? 3 : 5);
                    if (part == FirstPersonPart.BACKGROUND)
                        assertVertices(background, output);
                    assertTrue(outputs(state)[part.ordinal()].isValid());
                }
            } finally {
                state.clear();
            }
        }
    }

    @Test
    void exactRootsReplacementFailureAndSharedBackingAreIndependent() throws Exception {
        try (var aliases = new Fixture("parts-aliases"); var a = new Fixture("parts-a");
            var b = new Fixture("parts-b")) {
            var state = new FirstPersonModelState();
            var accepted = target("a");
            state.bind(accepted, aliases.model);
            assertFalse(state.hasPart(FirstPersonPart.LEFT_ARM));
            state.bind(accepted, a.model);
            assertTrue(state.hasPart(FirstPersonPart.LEFT_ARM));
            var animated = new AnimatedGeoModel(a.model);
            var bad = animated.getBoneAttributes().clone();
            bad[STRIDE + 12] = -1;
            var rejected = new Probe();
            assertFalse(
                state.render(FirstPersonPart.LEFT_ARM, bad, rejected.consumer, new PoseStack().last(), 0, 0, -1));
            assertEquals(0, rejected.vertices.size());
            assertTrue(state.render(FirstPersonPart.RIGHT_ARM, animated.getBoneAttributes(), rejected.consumer,
                new PoseStack().last(), 0, 0, -1));
            var oldScratch = outputs(state)[FirstPersonPart.RIGHT_ARM.ordinal()];
            state.bind(accepted, b.model);
            assertThrows(IllegalStateException.class, oldScratch::get);
            var replacement = new Probe();
            assertTrue(
                state.render(FirstPersonPart.BACKGROUND, null, replacement.consumer, new PoseStack().last(), 0, 0, -1));
            assertMarkers(replacement, 4, 5);
            state.clear();
            assertFalse(state.hasPart(FirstPersonPart.BACKGROUND));
            // Retiring derived outputs does not close the shared model.
            try (var other = NativeModelState.create()) {
                assertTrue(other.extract(a.model.bakedModel(), animated.getBoneAttributes()));
            }
        }
    }

    @Test
    void unifiedHookControlsTextureEmptyAndMissingDriveActualArmCancellation() throws Exception {
        try (var fixture = new Fixture("parts-a"); var missing = new Fixture("missing-leftarm")) {
            var owner = install(fixture);
            var output = new Probe();
            var pose = new PoseStack();
            var before = new Matrix4f(pose.last().pose());
            Consumer<SpecialPlayerRenderEvent> reject = event -> event.setCanceled(true);
            MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                SpecialPlayerRenderEvent.class, reject);
            try {
                var arm = armEvent(HumanoidArm.LEFT, pose, output);
                renderArm(arm);
                assertFalse(arm.isCanceled());
                assertEquals(1, owner.updates);
                assertTrue(output.vertices.isEmpty());
            } finally {
                MinecraftForge.EVENT_BUS.unregister(reject);
            }
            var override = new ResourceLocation("ysm", "override");
            Consumer<SpecialPlayerRenderEvent> replace = event -> event.setTextureLocationOverride(override);
            MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                SpecialPlayerRenderEvent.class, replace);
            try {
                var arm = armEvent(HumanoidArm.RIGHT, pose, output);
                renderArm(arm);
                assertTrue(arm.isCanceled());
                assertSame(CustomTranslucentRenderType.create(override), output.types.get(0));
                assertMarkers(output, 2, 3);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(replace);
            }
            var next = new Probe();
            assertTrue(renderer.render(player, cap, HumanoidArm.LEFT, pose, next, 0, 0.25f));
            assertSame(CustomTranslucentRenderType.create(owner.getTextureLocation()), next.types.get(0));
            assertEquals(before, pose.last().pose());
            for (var bone : owner.getLoadedGeoModel().getSortedBones())
                bone.setHidden(true, false);
            var empty = new Probe();
            var hidden = armEvent(HumanoidArm.LEFT, pose, empty);
            renderArm(hidden);
            assertTrue(hidden.isCanceled());
            assertTrue(empty.vertices.isEmpty());
            owner.install(missing.model);
            var absent = armEvent(HumanoidArm.LEFT, pose, empty);
            renderArm(absent);
            assertFalse(absent.isCanceled());
            ClientConfig.DISABLE_SELF_HANDS.set(true);
            assertFalse(renderer.render(player, cap, FirstPersonPart.BACKGROUND, pose, empty, 0, 0, 0.25f));
            ClientConfig.DISABLE_SELF_HANDS.set(false);
            ClientConfig.DISABLE_SELF_MODEL.set(true);
            assertFalse(renderer.render(player, cap, HumanoidArm.RIGHT, pose, empty, 0, 0.25f));
        }
    }

    @Test
    void hookResourceMutationAndControlledFailureKeepHostAndRestorePose() throws Exception {
        try (var a = new Fixture("parts-a"); var b = new Fixture("parts-b")) {
            var owner = install(a);
            Consumer<SpecialPlayerRenderEvent> change = event -> owner.getLoadedGeoModel().setModelInplace(b.model);
            MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                SpecialPlayerRenderEvent.class, change);
            try {
                assertFalse(renderer.render(player, cap, HumanoidArm.LEFT, new PoseStack(), new Probe(), 0, 0.25f));
                assertEquals(1, owner.updates);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(change);
            }
            owner.install(b.model);
            var attributes = owner.getLoadedGeoModel().getBoneAttributes();
            attributes[STRIDE + 12] = -1;
            var pose = new PoseStack();
            var expected = new Matrix4f(pose.last().pose());
            var output = new Probe();
            var event = armEvent(HumanoidArm.LEFT, pose, output);
            renderArm(event);
            assertFalse(event.isCanceled());
            assertTrue(output.vertices.isEmpty());
            assertEquals(expected, pose.last().pose());
            attributes[STRIDE + 12] = 0xFFFFFF;
            var next = armEvent(HumanoidArm.RIGHT, pose, output);
            renderArm(next);
            assertTrue(next.isCanceled());
            assertMarkers(output, 2, 3);
            var invalidPose = new PoseStack();
            invalidPose.last().pose().m00(Float.NaN);
            var generationFailure = new Probe();
            var failed = armEvent(HumanoidArm.LEFT, invalidPose, generationFailure);
            renderArm(failed);
            assertFalse(failed.isCanceled());
            assertTrue(generationFailure.vertices.isEmpty());
        }
    }

    @Test
    void correctConsumerExceptionCrossesUnifiedStandardAndBackgroundAdapters() throws Exception {
        try (var fixture = new Fixture("parts-a")) {
            var owner = install(fixture);
            var known = new IllegalStateException("known replay exception");
            for (boolean background : new boolean[] {false, true}) {
                var pose = new PoseStack();
                var expected = new Matrix4f(pose.last().pose());
                var output = new Probe();
                output.failure = known;
                var arm = background ? null : armEvent(HumanoidArm.LEFT, pose, output);
                var hand = background ? handEvent(pose, output) : null;
                assertSame(known, assertThrows(IllegalStateException.class, () -> {
                    if (background)
                        renderBackground(hand);
                    else
                        renderArm(arm);
                }));
                assertFalse(background ? hand.isCanceled() : arm.isCanceled());
                assertEquals(expected, pose.last().pose());
                var state =
                    (FirstPersonModelState)field(CustomFirstPersonArmEntity.class, "firstPersonState").get(owner);
                assertTrue(
                    outputs(state)[(background ? FirstPersonPart.BACKGROUND : FirstPersonPart.LEFT_ARM).ordinal()]
                        .isValid());
                if (background)
                    assertNull(field(RenderFirstPlayerBackground.class, "renderedPlayer").get(null));
            }
        }
    }

    @Test
    void backgroundRejectionCanRetryAndSuccessDoesNotAdvanceOrCancelHost() throws Exception {
        try (var fixture = new Fixture("parts-a")) {
            var owner = install(fixture);
            var output = new Probe();
            var event = handEvent(new PoseStack(), output);
            Consumer<SpecialPlayerRenderEvent> reject = hook -> hook.setCanceled(true);
            MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                SpecialPlayerRenderEvent.class, reject);
            try {
                renderBackground(event);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(reject);
            }
            assertNull(field(RenderFirstPlayerBackground.class, "renderedPlayer").get(null));
            renderBackground(event);
            assertFalse(event.isCanceled());
            assertEquals(0, owner.updates);
            assertMarkers(output, 4, 5);
            int count = output.vertices.size();
            renderBackground(event);
            assertEquals(count, output.vertices.size());
            RegisterEntityRenderersEvent.releaseFirstPersonArm();
            assertNull(field(RenderFirstPlayerBackground.class, "renderedPlayer").get(null));
            assertNull(field(CustomFirstPersonArmRenderer.class, "armEntity").get(renderer));
        }
    }

    @Test
    void animationOnlyUsesRealNormalTimelineAndIndependentRuntimeWithoutFullExtract() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var arm = new Timeline(player);
            var body = new Timeline(player);
            try {
                arm.install(fixture.model, module);
                body.install(fixture.model, module);
                assertNotSame(arm.getExpressionRuntime(), body.getExpressionRuntime());
                assertNotSame(arm.exactComponent(PositionComponent.class).orElseThrow(),
                    body.exactComponent(PositionComponent.class).orElseThrow());
                assertTrue(arm.advance(0.25f));
                assertEquals(1, arm.normalUpdates);
                assertTrue(arm.lastContext.immutable());
                assertTrue(arm.lastContext.level());
                assertFalse(arm.lastContext.firstPersonMod());
                assertTrue(arm.advance(0.25f));
                assertEquals(1, arm.normalUpdates);
                player.tickCount++;
                assertTrue(arm.advance(0.25f));
                assertEquals(2, arm.normalUpdates);
                assertEquals(0, body.normalUpdates);
                assertNull(field(AnimatableEntity.class, "mainRenderData").get(arm));
            } finally {
                arm.retire();
                body.retire();
            }
        }
    }

    @Test
    void canonicalProducerRecoversDamagedStateWithoutRepeatingLogicalActions() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var body = new Timeline(player);
            try {
                body.install(fixture.model, module);
                var first = body.produce(0.25f, RenderContext.levelImmutable());
                assertEquals(1,
                    first.modelState.locatorGroupSize(fixture.model.locatorType().getByBoneName("Background")));
                var output = new Probe();
                assertTrue(
                    NativeRenderer.render(output.consumer, new PoseStack().last(), first.modelState.getNativeState(),
                        first.modelState.getVertexCount(), 123, 456, -1, RenderContextType.LEVEL));
                first.modelState.getNativeState().invalidate();
                var recovered = body.produce(0.25f, RenderContext.levelImmutable());
                assertTrue(recovered.modelState.isValid(),
                    "normal producer must recover damaged publication in the same frame");
                assertEquals(1, body.normalUpdates);
                assertEquals(1, body.actions);
            } finally {
                body.retire();
            }
        }
    }

    @Test
    void realOwnerAlternatesPersistentOutputsAndRecoversInTheSameFrame() throws Exception {
        try (var fixture = new Fixture("parts-a"); var replacement = new Fixture("parts-b");
            var module = TestExpressionModules.create()) {
            var owner = new RealArm(player, cap);
            owner.install(fixture.model, module, replacement.model);
            field(CustomFirstPersonArmRenderer.class, "armEntity").set(renderer, owner);
            var expectedViews = new Object[3];
            var firstOutputs = new Probe[3];
            for (var part : List.of(FirstPersonPart.LEFT_ARM, FirstPersonPart.RIGHT_ARM, FirstPersonPart.BACKGROUND,
                FirstPersonPart.LEFT_ARM, FirstPersonPart.RIGHT_ARM)) {
                var output = new Probe();
                assertTrue(renderer.render(player, cap, part, new PoseStack(), output, 123, 456, 0.25f));
                assertMarkers(output, part == FirstPersonPart.LEFT_ARM ? new int[] {0, 1}
                    : part == FirstPersonPart.RIGHT_ARM ? new int[] {2, 3} : new int[] {4, 5});
                var view = outputs(owner.state())[part.ordinal()].getBonePoses();
                if (expectedViews[part.ordinal()] == null) {
                    expectedViews[part.ordinal()] = view;
                    firstOutputs[part.ordinal()] = output;
                } else
                    assertSame(expectedViews[part.ordinal()], view, "repeat draw must not extract again");
            }
            assertEquals(1, owner.actions);
            assertEquals(1, owner.normalUpdates);
            for (var vertex : firstOutputs[2].vertices) {
                if (vertex[5] == 0)
                    continue;
                assertEquals(1, vertex[5]);
                for (int channel = 6; channel < 9; channel++)
                    assertEquals(1, vertex[channel]);
                assertEquals(123, vertex[10]);
            }
            var states = outputs(owner.state()).clone();
            assertNotSame(states[0], states[1]);
            assertNotSame(states[1], states[2]);
            owner.texture = new ResourceLocation("ysm", "arm_repeat");
            var changedHost = new PoseStack();
            changedHost.translate(2, 3, 4);
            changedHost.last().normal().rotationZ(0.5f);
            var repeated = new Probe();
            assertTrue(renderer.render(player, cap, FirstPersonPart.LEFT_ARM, changedHost, repeated, 321, 654, 0.25f));
            assertSame(CustomTranslucentRenderType.create(owner.texture), repeated.types.get(0));
            assertSame(expectedViews[0], states[0].getBonePoses());
            assertEquals(firstOutputs[0].vertices.size(), repeated.vertices.size());
            for (int i = 0; i < repeated.vertices.size(); i++) {
                if (repeated.vertices.get(i)[5] == 0)
                    continue;
                var first = firstOutputs[0].vertices.get(i);
                var next = repeated.vertices.get(i);
                for (int axis = 0; axis < 3; axis++)
                    assertEquals(first[axis] + axis + 2, next[axis], 0.00002f);
                assertEquals(321, next[10]);
                assertEquals(654, next[9]);
                var normal = new org.joml.Vector3f(first[11], first[12], first[13]);
                changedHost.last().normal().transform(normal);
                for (int axis = 0; axis < 3; axis++)
                    assertEquals(normal.get(axis), next[11 + axis], 2f / 127 + 0.00002f);
            }
            var invalidHost = new PoseStack();
            invalidHost.last().normal().m00(Float.NaN);
            var rejected = new Probe();
            assertFalse(renderer.render(player, cap, FirstPersonPart.LEFT_ARM, invalidHost, rejected, 0, 0, 0.25f));
            assertTrue(rejected.vertices.isEmpty());
            assertTrue(states[0].isValid());
            assertSame(expectedViews[0], states[0].getBonePoses());
            assertTrue(
                renderer.render(player, cap, FirstPersonPart.LEFT_ARM, new PoseStack(), new Probe(), 321, 654, 0.25f));
            assertSame(expectedViews[0], states[0].getBonePoses());
            damagePose(states[0]);
            assertFalse(renderer.render(player, cap, FirstPersonPart.LEFT_ARM, new PoseStack(), rejected, 0, 0, 0.25f));
            assertTrue(rejected.vertices.isEmpty());
            assertFalse(states[0].isValid());
            assertTrue(states[1].isValid());
            assertTrue(
                renderer.render(player, cap, FirstPersonPart.LEFT_ARM, new PoseStack(), new Probe(), 0, 0, 0.25f));
            assertNotSame(expectedViews[0], states[0].getBonePoses());
            assertEquals(1, owner.actions);
            // Different legal samples invalidate both arm publications while preserving allocations.
            var sampled = new Probe();
            assertTrue(
                renderer.render(player, cap, FirstPersonPart.RIGHT_ARM, new PoseStack(), sampled, 123, 456, 0.5f));
            assertSame(states[1], outputs(owner.state())[1]);
            assertNotSame(expectedViews[1], states[1].getBonePoses());
            for (int i = 0; i < sampled.vertices.size(); i++) {
                if (sampled.vertices.get(i)[5] == 0)
                    continue;
                // Model position X and the host arm scale each reverse the sign.
                assertEquals(firstOutputs[1].vertices.get(i)[0] + 0.25f, sampled.vertices.get(i)[0], 0.00002f);
            }
            int actions = owner.actions;
            var background = new Probe();
            assertTrue(
                renderer.render(player, cap, FirstPersonPart.BACKGROUND, new PoseStack(), background, 123, 456, 0.5f));
            assertVertices(firstOutputs[2], background);
            assertEquals(actions, owner.actions);
            owner.corruptAttributes = true;
            var failedExtract = new Probe();
            assertFalse(
                renderer.render(player, cap, FirstPersonPart.LEFT_ARM, new PoseStack(), failedExtract, 0, 0, 0.75f));
            assertTrue(failedExtract.vertices.isEmpty());
            assertFalse(states[0].isValid());
            int committedActions = owner.actions;
            owner.corruptAttributes = false;
            assertTrue(
                renderer.render(player, cap, FirstPersonPart.LEFT_ARM, new PoseStack(), new Probe(), 0, 0, 0.75f));
            assertEquals(committedActions, owner.actions);
            owner.replace(replacement.model);
            assertTrue(
                renderer.render(player, cap, FirstPersonPart.BACKGROUND, new PoseStack(), new Probe(), 0, 0, 0.5f));
            for (var state : states)
                assertThrows(IllegalStateException.class, state::get);
            var current = outputs(owner.state())[2];
            renderer.releaseForPlayer(allocate(FakePlayer.class));
            assertTrue(current.isValid());
            renderer.releaseForPlayer(player);
            assertThrows(IllegalStateException.class, current::get);
            assertFalse(owner.synchronizeFirstPersonModel());
        }
    }

    @Test
    void canonicalRepeatRefreshesMetadataAndRejectsOutputWithoutReextracting() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var body = new Timeline(player);
            try {
                body.install(fixture.model, module);
                var data = body.produce(0.25f, RenderContext.levelImmutable());
                var state = data.modelState.getNativeState();
                var view = state.getBonePoses();
                var first = new Probe();
                assertTrue(NativeRenderer.render(first.consumer, new PoseStack().last(), state,
                    state.getTotalVertexCount(), 123, 456, -1, RenderContextType.LEVEL));
                body.texture = new ResourceLocation("ysm", "next");
                var next = body.produce(0.25f, RenderContext.levelImmutable());
                assertSame(data, next);
                assertEquals(body.texture, next.texture);
                assertSame(view, state.getBonePoses());
                var pose = new PoseStack();
                pose.translate(2, 3, 4);
                pose.last().normal().rotationZ(0.5f);
                var second = new Probe();
                assertTrue(NativeRenderer.render(second.consumer, pose.last(), state, state.getTotalVertexCount(), 321,
                    654, -1, RenderContextType.LEVEL));
                assertEquals(first.vertices.size(), second.vertices.size());
                for (int i = 0; i < first.vertices.size(); i++) {
                    if (first.vertices.get(i)[5] == 0)
                        continue;
                    for (int axis = 0; axis < 3; axis++)
                        assertEquals(first.vertices.get(i)[axis] + axis + 2, second.vertices.get(i)[axis], 0.00002f);
                    assertEquals(321, second.vertices.get(i)[10]);
                    assertEquals(654, second.vertices.get(i)[9]);
                    var normal = new org.joml.Vector3f(first.vertices.get(i)[11], first.vertices.get(i)[12],
                        first.vertices.get(i)[13]);
                    pose.last().normal().transform(normal);
                    for (int axis = 0; axis < 3; axis++)
                        assertEquals(normal.get(axis), second.vertices.get(i)[11 + axis], 2f / 127 + 0.00002f,
                            "two signed-byte normal encodings bound the error");
                }
                pose.last().normal().m00(Float.NaN);
                var rejected = new Probe();
                assertFalse(NativeRenderer.render(rejected.consumer, pose.last(), state, state.getTotalVertexCount(), 0,
                    0, -1, RenderContextType.LEVEL));
                assertTrue(rejected.vertices.isEmpty());
                assertTrue(state.isValid());
                assertSame(view,
                    body.produce(0.25f, RenderContext.levelImmutable()).modelState.getNativeState().getBonePoses());
                damagePose(state);
                assertFalse(NativeRenderer.render(rejected.consumer, new PoseStack().last(), state,
                    state.getTotalVertexCount(), 0, 0, -1, RenderContextType.LEVEL));
                assertTrue(rejected.vertices.isEmpty());
                assertTrue(body.produce(0.25f, RenderContext.levelImmutable()).modelState.isValid());
                assertNotSame(view, state.getBonePoses());
                field(NativeModelState.class, "totalVertexCount").setInt(state, state.getTotalVertexCount() + 4);
                assertFalse(NativeRenderer.render(rejected.consumer, new PoseStack().last(), state,
                    state.getTotalVertexCount(), 0, 0, -1, RenderContextType.LEVEL));
                assertFalse(state.isValid());
                assertTrue(body.produce(0.25f, RenderContext.levelImmutable()).modelState.isValid());
                assertEquals(1, body.actions);
                // Mutable evaluation writes the same array, then canonical must rebuild from restored input.
                body.corruptAttributes = true;
                var mutable = new RenderContext(false, false, false, true, false, false, false);
                assertNull(body.produce(0.25f, mutable));
                body.corruptAttributes = false;
                body.getLoadedGeoModel().getBoneAttributes()[12] = 0xFFFFFF;
                assertTrue(body.produce(0.25f, RenderContext.levelImmutable()).modelState.isValid());
                assertEquals(1, body.actions);
            } finally {
                body.retire();
            }
        }
    }

    @Test
    void candidateFailureAndReentryPreserveTheCompleteCurrentInstallation() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var owner = new Timeline(player);
            try {
                owner.install(fixture.model, module);
                var original = owner.produce(0.25f, RenderContext.levelImmutable());
                var generation = owner.getExpressionRuntime().generation();
                var position = owner.exactComponent(PositionComponent.class).orElseThrow();
                for (int marker = 2; marker <= 5; marker++) {
                    owner.resources.target(marker, module, fixture.model);
                    owner.select(marker);
                    int action = marker;
                    owner.controllers = () -> {
                        assertSame(original.installation, owner.installationIdentity());
                        assertSame(fixture.model, owner.getLoadedGeoModel().getModel());
                        assertEquals(generation, owner.getExpressionRuntime().generation());
                        assertThrows(IllegalStateException.class, () -> {
                            if (action == 2)
                                owner.reset();
                            else if (action == 3)
                                owner.retire();
                            else if (action == 4)
                                owner.select(1);
                            else
                                owner.produce(0.25f, RenderContext.levelImmutable());
                        });
                        throw new IllegalArgumentException("reject candidate");
                    };
                    DemandFixtures.advance(owner);
                    assertTrue(owner.isAssigned());
                    assertSame(original.installation, owner.installationIdentity());
                    assertEquals(generation, owner.getExpressionRuntime().generation());
                    assertSame(position, owner.exactComponent(position.getClass()).orElseThrow());
                    assertTrue(owner.canConsume(original, original.ctx));
                    assertEquals(1, owner.resources.leases.get(owner.resources.leases.size() - 1).closes);
                    assertEquals(0, owner.resources.leases.get(0).closes);
                }
                owner.controllers = () -> {
                };
                owner.resources.target(6, module, fixture.model);
                owner.select(6);
                DemandFixtures.advance(owner);
                assertTrue(owner.getExpressionRuntime().generation() > generation,
                    "same module still requires a new installation qualification");
                assertFalse(owner.canConsume(original, original.ctx));
                assertEquals(1, owner.resources.leases.get(0).closes);
            } finally {
                owner.retire();
            }
        }
    }

    @Test
    void publishedRenderCallbackSealsImmediatelyAndDefersPhysicalReleaseUntilBorrowExit() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var owner = new Timeline(player);
            owner.install(fixture.model, module);
            var data = owner.produce(0.25f, RenderContext.levelImmutable());
            var failure = new IllegalArgumentException("consumer failed after sealing");
            assertSame(failure,
                assertThrows(IllegalArgumentException.class, () -> owner.consumePrepared(data, borrowed -> {
                    assertTrue(owner.hasMatchingInstallation());
                    assertNotNull(owner.getLoadedGeoModel());
                    assertNotNull(owner.getAnimationData());
                    assertNotNull(owner.getAnimationProcessor());
                    assertTrue(owner.getExpressionRuntime().module().isPresent());
                    owner.retire();
                    assertFalse(owner.isAssigned());
                    assertFalse(owner.canEvaluate());
                    assertEquals(0, owner.resources.leases.get(0).closes);
                    throw failure;
                })));
            assertOwnerReleased(owner);
            assertEquals(1, owner.resources.leases.get(0).closes);
            assertNull(owner.produce(0.25f, RenderContext.levelImmutable()));
            assertThrows(IllegalStateException.class, () -> owner.select(1));
            assertEquals(1, owner.resources.acquisitions);
            assertFalse(owner.isAssigned());
            owner.retire();
        }
    }

    @Test
    void exactBorrowRejectsRetirementAfterPurePrecheckAndKeepsBackingForTheOtherOwner() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var first = new Timeline(player);
            var other = new Timeline(player);
            try {
                first.install(fixture.model, module);
                other.install(fixture.model, module);
                var data = first.produce(0.25f, RenderContext.levelImmutable());
                assertTrue(first.canConsume(data, data.ctx));
                assertFalse(first.consumePrepared(data, borrowed -> {
                    first.retire();
                    assertEquals(0, first.resources.leases.get(0).closes);
                    assertFalse(first.canConsume(borrowed, borrowed.ctx));
                    return false;
                }));
                assertOwnerReleased(first);
                assertEquals(1, first.resources.leases.get(0).closes);
                player.tickCount++;
                var next = other.produce(0.5f, RenderContext.levelImmutable());
                var output = new Probe();
                assertTrue(other.consumePrepared(next,
                    borrowed -> NativeRenderer.render(output.consumer, new PoseStack().last(),
                        borrowed.modelState.getNativeState(), borrowed.modelState.getVertexCount(), 1, 2, -1,
                        RenderContextType.LEVEL)));
                assertFalse(output.vertices.isEmpty());
                assertEquals(0, other.resources.leases.get(0).closes);
            } finally {
                first.retire();
                other.retire();
            }
        }
    }

    @Test
    void queriesHaveNoAcquireJoinEvaluationOrExtractionEffects() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var owner = new Timeline(player);
            try {
                assertFalse(owner.isAssigned());
                assertEquals(AnimatableEntity.BlockReason.UNASSIGNED, owner.getSelectionBlockReason());
                owner.install(fixture.model, module);
                var prepared = owner.produce(0.25f, RenderContext.levelImmutable());
                int acquisitions = owner.resources.acquisitions, actions = owner.actions;
                var extractedView = prepared.modelState.getNativeState().getBonePoses();
                for (int i = 0; i < 10; i++) {
                    assertTrue(owner.isAssigned());
                    assertTrue(owner.isPresentationAllowed());
                    assertTrue(owner.isRunRequired());
                    assertTrue(owner.canAcceptPlayback());
                    assertTrue(owner.canEvaluate());
                    assertTrue(owner.canPrepare(prepared.ctx));
                    assertTrue(owner.canConsume(prepared, prepared.ctx));
                    assertEquals(AnimatableEntity.InstallationRelation.MATCHING, owner.getInstallationRelation());
                    assertEquals(AnimatableEntity.BlockReason.NONE, owner.getSelectionBlockReason());
                    assertFalse(owner.hasUnfinishedWork());
                    assertTrue(owner.isAssigned());
                    assertNotNull(owner.getModelRenderTarget());
                    assertEquals("", owner.getRequestedTextureName());
                }
                assertEquals(acquisitions, owner.resources.acquisitions);
                assertEquals(actions, owner.actions);
                assertSame(extractedView, prepared.modelState.getNativeState().getBonePoses());
                owner.restrict(false);
                assertTrue(owner.isAssigned());
                assertFalse(owner.canEvaluate());
                assertEquals(1, owner.resources.leases.get(0).closes);
                owner.select(1);
                owner.restrict(true);
                DemandFixtures.advance(owner);
                assertTrue(owner.canEvaluate());
                assertEquals(2, owner.resources.acquisitions);
                owner.producer = true;
                owner.restrict(false);
                assertTrue(owner.isRunRequired());
                assertTrue(owner.canEvaluate());
                assertFalse(owner.canPrepare(prepared.ctx));
                assertFalse(owner.canConsume(prepared, prepared.ctx));
            } finally {
                owner.retire();
            }
        }
    }

    @Test
    void sameSubjectModelReplacementPreservesPositionValuesAndNewOwnerStartsItsOwnHistory() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var owner = new Timeline(player);
            var fresh = new Timeline(player);
            try {
                owner.install(fixture.model, module);
                var before = owner.exactComponent(PositionComponent.class).orElseThrow();
                before.update(0, 0, 1);
                field(net.minecraft.world.entity.Entity.class, "position").set(player,
                    new net.minecraft.world.phys.Vec3(4, 0, 0));
                owner.resources.target(2, module, fixture.model);
                owner.select(2);
                DemandFixtures.advance(owner);
                var after = owner.exactComponent(before.getClass()).orElseThrow();
                assertNotSame(before, after);
                after.update(0, 1, 1);
                assertEquals(4f, after.positionValue(0, true));
                fresh.install(fixture.model, module);
                assertEquals(0f, fresh.exactComponent(before.getClass()).orElseThrow().positionValue(0, true));
            } finally {
                owner.retire();
                fresh.retire();
            }
        }
    }

    @Test
    void interruptedWaitConfirmsTheRealWorkerTerminalAndRestoresInterrupt() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var owner = new Timeline(player);
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var exited = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                owner.install(fixture.model, module);
                owner.beforeAnimation = () -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(30, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        throw new AssertionError(failure);
                    }
                    exited.set(true);
                };
                startAsync(owner, 0.25f);
                assertTrue(entered.await(30, java.util.concurrent.TimeUnit.SECONDS));
                var waiter = Thread.currentThread();
                var gate = new Thread(() -> {
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                    while (waiter.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
                        Thread.onSpinWait();
                    release.countDown();
                });
                gate.start();
                Thread.currentThread().interrupt();
                owner.waitForAsyncUpdate();
                assertTrue(exited.get());
                assertTrue(Thread.interrupted());
                assertFalse(owner.hasUnfinishedWork());
                gate.join(30000);
                assertFalse(gate.isAlive());
                assertNotNull(owner.produce(0.25f, RenderContext.levelImmutable()));
            } finally {
                release.countDown();
                Thread.interrupted();
                owner.retire();
            }
        }
    }

    @Test
    void replacementJoinsTheRunningWorkerBeforeClosingItsLeaseEvenWhenInterrupted() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var owner = new Timeline(player);
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            try {
                owner.install(fixture.model, module);
                var previous = owner.produce(0.25f, RenderContext.levelImmutable());
                owner.resources.target(2, module, fixture.model);
                var oldLease = owner.resources.leases.get(0);
                owner.beforeAnimation = () -> {
                    entered.countDown();
                    await(release);
                };
                player.tickCount++;
                startAsync(owner, 0.5f);
                assertTrue(entered.await(30, java.util.concurrent.TimeUnit.SECONDS));
                var lifecycle = field(AnimatableEntity.class, "lifecycle").get(owner);
                var task =
                    (java.util.concurrent.Future<?>)field(EntityExecutionLifecycle.class, "asyncTask").get(lifecycle);
                oldLease.onClose = () -> assertTrue(task.isDone(), "A lease must survive the actual worker body");
                var waiter = Thread.currentThread();
                var gate = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    awaitWaiting(waiter);
                    assertFalse(task.isDone());
                    assertEquals(0, oldLease.closes);
                    waiter.interrupt();
                    release.countDown();
                });
                owner.select(2);
                DemandFixtures.advance(owner);
                assertTrue(Thread.interrupted());
                gate.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertFalse(owner.hasUnfinishedWork());
                assertEquals(1, oldLease.closes);
                assertFalse(owner.canConsume(previous, previous.ctx));
                assertEquals(OwnerResourceFixtures.hash(2), owner.getModelHash());
                owner.beforeAnimation = () -> {
                };
                assertNativeConsumption(owner, owner.produce(0.5f, RenderContext.levelImmutable()));
            } finally {
                release.countDown();
                Thread.interrupted();
                owner.retire();
            }
        }
    }

    @Test
    void earlyGetAndDrawKeepOtherOwnersPrivateUntilTheInvocationDrain() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var early = new Timeline(player);
            var slow = new Timeline(player);
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            try {
                early.install(fixture.model, module);
                slow.install(fixture.model, module);
                slow.beforeAnimation = () -> {
                    entered.countDown();
                    await(release);
                };
                startAsync(early, 0.25f);
                startAsync(slow, 0.25f);
                assertTrue(entered.await(30, java.util.concurrent.TimeUnit.SECONDS));
                early.waitForAsyncUpdate();
                assertNativeConsumption(early, early.produce(0.25f, RenderContext.levelImmutable()));
                assertTrue(slow.hasPendingUpdate());
                assertEquals(1, early.actions);
                release.countDown();
                com.elfmcys.ysm.client.animation.AnimationParallelTicker.waitAll();
                assertFalse(slow.hasPendingUpdate());
                assertEquals(1, slow.actions);
                player.tickCount++;
                startAsync(early, 0.5f);
                com.elfmcys.ysm.client.animation.AnimationParallelTicker.waitAll();
                assertEquals(2, early.actions);
            } finally {
                release.countDown();
                com.elfmcys.ysm.client.animation.AnimationParallelTicker.waitAll();
                early.retire();
                slow.retire();
            }
        }
    }

    @Test
    void invocationDrainJoinsAnUndrawnQueuedOwnerAfterAnotherWorkerFails() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var failed = new Timeline(player);
            var queued = new Timeline(player);
            var tasks = new ArrayList<Runnable>();
            var failure = new AssertionError("worker fixture");
            try {
                failed.install(fixture.model, module);
                queued.install(fixture.model, module);
                for (var owner : List.of(failed, queued)) {
                    var lifecycle = field(AnimatableEntity.class, "lifecycle").get(owner);
                    field(EntityExecutionLifecycle.class, "executor").set(lifecycle,
                        (java.util.concurrent.Executor)tasks::add);
                }
                failed.beforeAnimation = () -> { throw failure; };
                startAsync(failed, 0.25f);
                startAsync(queued, 0.25f);
                assertEquals(2, tasks.size());
                var main = Thread.currentThread();
                var worker = new Thread(() -> {
                    awaitWaiting(main);
                    tasks.forEach(Runnable::run);
                });
                worker.start();
                assertSame(failure, assertThrows(AssertionError.class,
                    com.elfmcys.ysm.client.animation.AnimationParallelTicker::waitAll));
                worker.join(5000);
                assertFalse(worker.isAlive());
                assertFalse(failed.hasPendingUpdate());
                assertFalse(queued.hasPendingUpdate());
                assertFalse(com.elfmcys.ysm.client.animation.AnimationParallelTicker.isInvocationActive());
                assertEquals(1, queued.actions);
                assertEquals(0, queued.resources.leases.get(0).closes);
            } finally {
                tasks.forEach(Runnable::run);
                com.elfmcys.ysm.client.animation.AnimationParallelTicker.waitAll();
                failed.retire();
                queued.retire();
            }
        }
    }

    @Test
    void workerLifecycleReentryIsRejectedBeforeAnyOwnerChange() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var owner = new Timeline(player);
            try {
                owner.install(fixture.model, module);
                owner.beforeAnimation = () -> {
                    assertThrows(IllegalStateException.class, owner::retire);
                    assertThrows(IllegalStateException.class, owner::reset);
                    assertThrows(IllegalStateException.class, () -> owner.select(2));
                };
                startAsync(owner, 0.25f);
                owner.waitForAsyncUpdate();
                assertTrue(owner.isAssigned());
                assertEquals(0, owner.resources.leases.get(0).closes);
                assertNativeConsumption(owner, owner.produce(0.25f, RenderContext.levelImmutable()));
            } finally {
                owner.retire();
            }
        }
    }

    @Test
    void lateDemandResultsCannotReplaceConsumedCOrResumePausedAndRetiredOwners() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            for (int ending = 0; ending < 3; ending++) {
                var owner = new Timeline(player);
                try {
                    owner.install(fixture.model, module);
                    owner.resources.target(2, module, fixture.model);
                    owner.resources.delay(OwnerResourceFixtures.hash(2));
                    owner.select(2);
                    DemandFixtures.advance(owner);
                    assertTrue(owner.hasUnfinishedWork(), "The pending demand is owned work too");
                    owner.resources.target(3, module, fixture.model);
                    if (ending == 0) {
                        owner.select(3);
                        DemandFixtures.advance(owner);
                        assertNativeConsumption(owner, owner.produce(0.25f, RenderContext.levelImmutable()));
                    } else if (ending == 1) {
                        owner.restrict(false);
                        owner.select(3);
                    } else
                        owner.retire();
                    int reports = owner.resources.activeReports;
                    var late = owner.resources.deliver(OwnerResourceFixtures.hash(2));
                    assertEquals(1, late.closes);
                    assertEquals(reports, owner.resources.activeReports);
                    assertEquals(0, owner.resources.failureReports);
                    assertFalse(owner.hasUnfinishedWork());
                    if (ending != 2)
                        assertEquals(OwnerResourceFixtures.hash(3), owner.getModelHash());
                    if (ending == 1) {
                        assertFalse(owner.hasInstalledModel());
                        owner.restrict(true);
                        DemandFixtures.advance(owner);
                        assertNativeConsumption(owner, owner.produce(0.25f, RenderContext.levelImmutable()));
                    }
                    if (ending == 2)
                        assertOwnerReleased(owner);
                } finally {
                    owner.retire();
                }
            }
        }
    }

    @Test
    void realBodyAndArmFactoriesRejectCandidatesWithoutChangingTheOldInstallationAndAlwaysEndCoreUse()
        throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            var bodyResources = new OwnerResourceFixtures();
            bodyResources.attach(cap);
            var a = bodyResources.target(1, module, fixture.model);
            var badGeometry = bodyResources.target(2, module, fixture.model);
            var badControllers = bodyResources.target(3, module, fixture.model);
            verifyRoleFailureAndCleanup(cap, bodyResources, a, badGeometry, badControllers, "playerControllerFactory",
                id -> cap.updateModelAndTexture(id, "variant0"));
            var body = new PlayerAnimatableCapability(player);
            var arm = new CustomFirstPersonArmEntity(player, body);
            var armResources = new OwnerResourceFixtures();
            armResources.attach(arm);
            try {
                a = armResources.target(1, module, fixture.model);
                badGeometry = armResources.target(2, module, fixture.model);
                badControllers = armResources.target(3, module, fixture.model);
                verifyRoleFailureAndCleanup(arm, armResources, a, badGeometry, badControllers, "fpArmControllerFactory",
                    id -> body.updateModelAndTexture(id, "variant0"));
            } finally {
                arm.retire();
                body.retire();
            }
        }
    }

    @Test
    void realScopedFactoriesAndRoamingInitializationHaveTheSameCandidateAndCleanupBoundary() throws Exception {
        try (var fixture = new Fixture("parts-a");
            var module = TestExpressionModules.create(java.util.Set.of("known"))) {
            var builder = new ForgeConfigSpec.Builder();
            var oldProjectile = ClientConfig.DISABLE_PROJECTILE_MODEL;
            var oldVehicle = ClientConfig.DISABLE_VEHICLE_MODEL;
            ClientConfig.DISABLE_PROJECTILE_MODEL = builder.define("projectile", false);
            ClientConfig.DISABLE_VEHICLE_MODEL = builder.define("vehicle", false);
            builder.build().setConfig(CommentedConfig.inMemory());
            try {
                for (var kind : List.of(com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE,
                    com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.VEHICLE)) {
                    var resources = new OwnerResourceFixtures();
                    var a = resources.scopedTarget(1, module, fixture.model, kind);
                    var badGeometry = resources.scopedTarget(2, module, fixture.model, kind);
                    var badControllers = resources.scopedTarget(3, module, fixture.model, kind);
                    var subject = kind == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE
                        ? allocate(FakeArrow.class) : allocate(FakeBoat.class);
                    field(net.minecraft.world.entity.Entity.class, "position").set(subject,
                        net.minecraft.world.phys.Vec3.ZERO);
                    AnimatableEntity<?> owner =
                        subject instanceof net.minecraft.world.entity.projectile.Projectile projectile
                            ? new com.elfmcys.ysm.capability.ProjectileAnimatableCapability(projectile)
                            : new com.elfmcys.ysm.capability.VehicleAnimatableCapability(subject);
                    resources.attach(owner);
                    try (var catalog = resources.useCatalog()) {
                        verifyRoleFailureAndCleanup(owner, resources, a, badGeometry, badControllers,
                            "controllerFactory", owner::updateModelHash);
                    } finally {
                        owner.retire();
                    }

                    // A new scoped owner must reject its own invalid projected namespace before publication.
                    var invalidOwner = subject instanceof net.minecraft.world.entity.projectile.Projectile projectile
                        ? (AnimatableEntity<?>)new com.elfmcys.ysm.capability.ProjectileAnimatableCapability(projectile)
                        : new com.elfmcys.ysm.capability.VehicleAnimatableCapability(subject);
                    resources = new OwnerResourceFixtures();
                    resources.attach(invalidOwner);
                    a = resources.scopedTarget(4, module, fixture.model, kind);
                    var invalid = new it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap();
                    invalid.put(com.elfmcys.ysm.util.StringPool.computeIfAbsent("unknown"), 9);
                    if (invalidOwner instanceof com.elfmcys.ysm.capability.ProjectileAnimatableCapability projectile)
                        projectile.initRoamingVars(invalid);
                    else
                        ((com.elfmcys.ysm.capability.VehicleAnimatableCapability)invalidOwner).initRoamingVars(invalid);
                    try (var catalog = resources.useCatalog()) {
                        invalidOwner.updateModelHash(a.modelHash());
                        DemandFixtures.advance(invalidOwner);
                        assertFalse(invalidOwner.hasInstalledModel());
                        assertTrue(invalidOwner.getExpressionRuntime().module().isEmpty());
                        assertNotNull(invalidOwner.modelLoadFailure());
                        assertEquals(1, resources.leases.get(0).closes);
                    } finally {
                        invalidOwner.retire();
                    }
                }
            } finally {
                ClientConfig.DISABLE_PROJECTILE_MODEL = oldProjectile;
                ClientConfig.DISABLE_VEHICLE_MODEL = oldVehicle;
            }
        }
    }

    @Test
    void publicQueriesDistinguishDefaultRetainedHiddenPausedAndRevokedPreparationWithoutDoingWork() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            var resources = new OwnerResourceFixtures();
            resources.attach(cap);
            var fallback = resources.target(0, module, fixture.model);
            var waiting = resources.target(1, module, fixture.model);
            var a = resources.target(2, module, fixture.model);
            var b = resources.target(3, module, fixture.model);
            resources.delay(waiting.modelHash());
            cap.updateModelAndTexture(waiting.modelHash(), "unresolved");
            var substitute = DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable());
            assertEquals(waiting.modelHash(), cap.getModelHash());
            assertSame(fallback, cap.getModelRenderTarget());
            assertEquals("unresolved", cap.getRequestedTextureName());
            assertQueryPattern(cap, resources, substitute, AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE,
                true, true, true, AnimatableEntity.BlockReason.WAITING);
            assertTrue(cap.hasUnfinishedWork());
            assertNativeConsumption(cap, substitute);
            cap.updateModelAndTexture((com.elfmcys.ysm.model.domain.Hash256)null, "default-request");
            DemandFixtures.advance(cap);
            assertQueryPattern(cap, resources, substitute, AnimatableEntity.InstallationRelation.MATCHING, true, true,
                true, AnimatableEntity.BlockReason.NONE);
            assertEquals(1, resources.deliver(waiting.modelHash()).closes);
            cap.updateModelAndTexture(a.modelHash(), "variant0");
            var matching = DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable());
            assertQueryPattern(cap, resources, matching, AnimatableEntity.InstallationRelation.MATCHING, true, true,
                true, AnimatableEntity.BlockReason.NONE);
            resources.delay(b.modelHash());
            cap.updateModelAndTexture(b.modelHash(), "requested-b");
            DemandFixtures.advance(cap);
            assertSame(a, cap.getModelRenderTarget());
            assertEquals("requested-b", cap.getRequestedTextureName());
            assertQueryPattern(cap, resources, matching, AnimatableEntity.InstallationRelation.RETAINED, true, true,
                true, AnimatableEntity.BlockReason.WAITING);
            assertNativeConsumption(cap, matching);
            assertFalse(cap.canConsume(matching, matching.ctx.withImmutable(false)));
            assertEquals(AnimatableEntity.BlockReason.STALE_RESULT,
                cap.getConsumptionBlockReason(matching, matching.ctx.withImmutable(false)));
            ClientConfig.DISABLE_SELF_MODEL.set(true);
            assertQueryPattern(cap, resources, matching, AnimatableEntity.InstallationRelation.RETAINED, true, true,
                false, AnimatableEntity.BlockReason.WAITING);
            assertEquals(AnimatableEntity.BlockReason.RESTRICTED, cap.getPreparationBlockReason(matching.ctx));
            ClientConfig.DISABLE_SELF_MODEL.set(false);
            assertTrue(cap.canConsume(matching, matching.ctx));
            cap.reloadGeoModel();
            assertFalse(cap.consumePrepared(matching, data -> {
                fail("A precheck does not reserve the old output");
                return true;
            }));

            var consumer = new CustomPlayerEntity(player, false, false) {};
            var remoteResources = new OwnerResourceFixtures();
            remoteResources.attach(consumer);
            var remoteA = remoteResources.target(2, module, fixture.model);
            var remoteC = remoteResources.target(4, module, fixture.model);
            try {
                consumer.updateModelAndTexture(remoteA.modelHash(), "variant0");
                var data = DemandFixtures.produce(consumer, 0.25f, RenderContext.levelImmutable());
                consumer.setDisabled(true);
                ClientConfig.DISABLE_OTHER_MODEL.set(true);
                consumer.updateModelAndTexture(remoteC.modelHash(), "latest");
                consumer.setDisabled(false);
                assertTrue(consumer.isAssigned());
                assertQueryPattern(consumer, remoteResources, null, AnimatableEntity.InstallationRelation.UNBOUND,
                    false, false, false, AnimatableEntity.BlockReason.RESTRICTED);
                assertFalse(consumer.canConsume(data, data.ctx));
                consumer.stopExtraAnimation();
                ClientConfig.DISABLE_OTHER_MODEL.set(false);
                DemandFixtures.advance(consumer);
                assertSame(remoteC, consumer.getModelRenderTarget());
                assertNativeConsumption(consumer,
                    DemandFixtures.produce(consumer, 0.25f, RenderContext.levelImmutable()));
                consumer.revokeAssignment();
                assertFalse(consumer.isAssigned());
                assertQueryPattern(consumer, remoteResources, null, AnimatableEntity.InstallationRelation.UNBOUND,
                    false, false, false, AnimatableEntity.BlockReason.UNASSIGNED);
                consumer.retire();
                assertOwnerReleased(consumer);
            } finally {
                consumer.retire();
                ClientConfig.DISABLE_OTHER_MODEL.set(false);
            }
        } finally {
            ClientConfig.DISABLE_SELF_MODEL.set(false);
        }
    }

    @Test
    void armQueriesKeepMissingPartsSeparateAndDoNotReserveAResultAcrossRetirement() throws Exception {
        try (var fixture = new Fixture("missing-leftarm"); var module = TestExpressionModules.create()) {
            var resources = new OwnerResourceFixtures();
            var owner = new CustomFirstPersonArmEntity(player, cap);
            resources.attach(owner);
            var target = resources.target(1, module, fixture.model);
            cap.updateModelAndTexture(target.modelHash(), "variant0");
            try {
                DemandFixtures.advance(owner);
                var right = owner.preparePart(FirstPersonPart.RIGHT_ARM, 0.25f);
                assertNotNull(right);
                assertTrue(owner.canEvaluate());
                assertEquals(AnimatableEntity.InstallationRelation.MATCHING, owner.getInstallationRelation());
                int count = resources.acquisitions;
                for (int i = 0; i < 10; i++) {
                    assertFalse(owner.canPrepare(FirstPersonPart.LEFT_ARM, RenderContext.levelImmutable()));
                    assertEquals(AnimatableEntity.BlockReason.MISSING_PART,
                        owner.getPreparationBlockReason(FirstPersonPart.LEFT_ARM, RenderContext.levelImmutable()));
                    assertTrue(owner.canPrepare(FirstPersonPart.RIGHT_ARM, RenderContext.levelImmutable()));
                    assertTrue(owner.canConsume(right));
                }
                assertEquals(count, resources.acquisitions);
                assertTrue(owner.renderPart(right, () -> new Probe().consumer, new PoseStack().last(), 0, 0, -1));
                ClientConfig.DISABLE_SELF_HANDS.set(true);
                DemandFixtures.advance(owner);
                cap.setDisabled(true);
                ClientConfig.DISABLE_SELF_HANDS.set(false);
                DemandFixtures.advance(owner);
                assertFalse(owner.isRunRequired());
                assertFalse(owner.canConsume(right));
                assertFalse(owner.hasInstalledModel());
                cap.setDisabled(false);
                DemandFixtures.advance(owner);
                right = owner.preparePart(FirstPersonPart.RIGHT_ARM, 0.25f);
                assertTrue(owner.canConsume(right));
                owner.retire();
                assertFalse(owner.renderPart(right, () -> {
                    fail("Retired part cannot request output");
                    return null;
                }, new PoseStack().last(), 0, 0, -1));
                assertEquals(AnimatableEntity.BlockReason.UNASSIGNED,
                    owner.getPreparationBlockReason(FirstPersonPart.RIGHT_ARM, RenderContext.levelImmutable()));
            } finally {
                owner.retire();
                ClientConfig.DISABLE_SELF_HANDS.set(false);
                cap.setDisabled(false);
            }
        }
    }

    @Test
    void realArmConnectionRejectsACorruptProducerSnapshotWithoutPublishingItsCandidate() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create(java.util.Set.of("shared"))) {
            var bodyResources = new OwnerResourceFixtures();
            bodyResources.attach(cap);
            var body = bodyResources.target(1, module, fixture.model);
            var armResources = new OwnerResourceFixtures();
            var arm = new CustomFirstPersonArmEntity(player, cap);
            armResources.attach(arm);
            var a = armResources.target(1, module, fixture.model);
            var b = armResources.target(2, module, fixture.model);
            var values = new it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap();
            values.put(com.elfmcys.ysm.util.StringPool.computeIfAbsent("shared"), 7);
            cap.resetRoamingVars(body.modelHash().roamingHash(), values);
            cap.updateModelAndTexture(body.modelHash(), "variant0");
            DemandFixtures.advance(cap);
            DemandFixtures.advance(arm);
            long generation = arm.getExpressionRuntime().generation();
            var producer = cap.getExpressionRuntime().roamingState().orElseThrow();
            try {
                assertEquals(7f, arm.getExpressionRuntime().roamingState().orElseThrow().read("shared"));
                // Corrupt only the supplied compiled state; the real connection validates its payload.
                producer.write("shared", Float.NaN);
                cap.updateModelAndTexture(b.modelHash(), "variant0");
                DemandFixtures.advance(arm);
                var failure = arm.modelLoadFailure();
                assertInstanceOf(IllegalArgumentException.class, failure);
                assertTrue(java.util.Arrays.stream(failure.getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("prepareArmConnection")));
                assertSame(a, arm.getModelRenderTarget());
                assertEquals(generation, arm.getExpressionRuntime().generation());
                assertEquals(b.modelHash(), arm.getModelHash());
                assertEquals(7f, arm.getExpressionRuntime().roamingState().orElseThrow().read("shared"));
                assertEquals(0, armResources.leases.get(0).closes);
                assertEquals(1, armResources.leases.get(1).closes);
                var part = arm.preparePart(FirstPersonPart.RIGHT_ARM, 0.25f);
                var output = new Probe();
                assertTrue(arm.renderPart(part, () -> output.consumer, new PoseStack().last(), 0, 0, -1));
                assertFalse(output.vertices.isEmpty());
                producer.write("shared", 7);
                arm.reloadGeoModel();
                DemandFixtures.advance(arm);
                part = arm.preparePart(FirstPersonPart.RIGHT_ARM, 0.25f);
                assertTrue(arm.renderPart(part, () -> output.consumer, new PoseStack().last(), 0, 0, -1));
                assertSame(b, arm.getModelRenderTarget());
                assertEquals(7f, arm.getExpressionRuntime().roamingState().orElseThrow().read("shared"));
                assertEquals(1, armResources.leases.get(0).closes);
            } finally {
                producer.write("shared", 7);
                arm.retire();
            }
        }
    }

    @Test
    void realOwnersKeepRoamingPhysicsHeadAndComponentHistorySeparateAcrossCompatibleAndNewModels(
        @org.junit.jupiter.api.io.TempDir Path cache) throws Exception {
        var definition = com.elfmcys.ysm.format.parser.AnimationBuilder
            .build(com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile.createGson(false).fromJson("""
                {"animations":{"unused":{"animation_length":1,"timeline":{"0":"r.known=r.known;"}}}}
                """, com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile.class));
        var model = com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto.newBuilder()
            .putAnimationFiles("main", definition).build();
        var host = ExpressionHost.create(HostAvailability.all());
        var factory = new com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory(
            track(new com.elfmcys.ysm.expression.aot.ExpressionArtifactStore(cache,
                track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(cache)))),
            host, new com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics());
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get(), true);
            var variant = new Fixture("parts-b", com.elfmcys.ysm.client.model.locator.PlayerLocator.get(), true);
            var module = factory.create(() -> false,
                new com.elfmcys.ysm.model.domain.ModelFileIdentity(OwnerResourceFixtures.hash(1),
                    OwnerResourceFixtures.hash(255)),
                "player", "", OwnerResourceFixtures.PROFILE, false, model, null,
                com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto.newBuilder().build(), new byte[0])) {
            var other = new PlayerAnimatableCapability(player);
            var firstResources = new OwnerResourceFixtures();
            firstResources.attach(cap);
            var otherResources = new OwnerResourceFixtures();
            otherResources.attach(other);
            var first = firstResources.target(1, module, fixture.model, variant.model);
            var second = otherResources.target(1, module, fixture.model);
            var next = firstResources.target(2, module, fixture.model);
            var firstValues = new it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap();
            var otherValues = new it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap();
            int key = com.elfmcys.ysm.util.StringPool.computeIfAbsent("known");
            firstValues.put(key, 7);
            otherValues.put(key, 11);
            cap.resetRoamingVars(first.modelHash().roamingHash(), firstValues);
            other.resetRoamingVars(second.modelHash().roamingHash(), otherValues);
            cap.updateModelAndTexture(first.modelHash(), "variant0");
            other.updateModelAndTexture(second.modelHash(), "variant0");
            player.xRotO = 20;
            player.setXRot(20);
            var arm = new CustomFirstPersonArmEntity(player, cap);
            firstResources.attach(arm);
            try {
                var old = DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable());
                DemandFixtures.produce(other, 0.25f, RenderContext.levelImmutable());
                DemandFixtures.advance(arm);
                assertEquals(7f, readRoaming(arm, "known"));
                arm.getExpressionRuntime().applyRoamingPatch(
                    new dev.semaweave.RoamingPayload(new java.util.TreeMap<>(java.util.Map.of("known", 19f))));
                assertEquals(7f, cap.getExpressionRuntime().snapshot().orElseThrow().payload().values().get("known"));
                var closed = factory.create(() -> false,
                    new com.elfmcys.ysm.model.domain.ModelFileIdentity(OwnerResourceFixtures.hash(3),
                        OwnerResourceFixtures.hash(255)),
                    "player", "", OwnerResourceFixtures.PROFILE, false, model, null,
                    com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto.newBuilder().build(), new byte[0]);
                var rejected = firstResources.target(3, closed, fixture.model);
                closed.close();
                cap.updateModelAndTexture(rejected.modelHash(), "variant0");
                DemandFixtures.advance(arm);
                assertSame(first, arm.getModelRenderTarget());
                assertNotNull(arm.modelLoadFailure());
                assertEquals(1, firstResources.leases.get(firstResources.leases.size() - 1).closes);
                assertEquals(19f, readRoaming(arm, "known"));
                cap.updateModelAndTexture(first.modelHash(), "variant0");
                var firstPhysics = cap.getPhysicsManager(cap.createIngressAnimationEvent());
                var otherPhysics = other.getPhysicsManager(other.createIngressAnimationEvent());
                firstPhysics.put(33, new com.elfmcys.ysm.client.animation.physics.FirstOrder(10, 1));
                otherPhysics.put(33, new com.elfmcys.ysm.client.animation.physics.FirstOrder(20, 1));
                firstPhysics.update(1);
                otherPhysics.update(1);
                firstPhysics.update(11);
                otherPhysics.update(11);
                assertEquals(5, firstPhysics.get(33).getValue(), 0.00001f);
                assertEquals(10, otherPhysics.get(33).getValue(), 0.00001f);
                assertEquals(7f, cap.getExpressionRuntime().snapshot().orElseThrow().payload().values().get("known"));
                assertEquals(11f,
                    other.getExpressionRuntime().snapshot().orElseThrow().payload().values().get("known"));
                var imType = com.elfmcys.ysm.client.compat.immersivemelodies.ImmersiveMelodiesBuiltinComponent.class;
                var melody = cap.exactComponent(imType).orElseThrow();
                ((com.elfmcys.ysm.client.compat.immersivemelodies.ImmersiveMelodiesCompat.ImmersiveMelodiesData)field(
                    imType, "reading").get(melody)).time = 23;
                assertEquals(23f, melody.time(null));
                var firstPosition = cap.exactComponent(PositionComponent.class).orElseThrow();
                field(net.minecraft.world.entity.Entity.class, "position").set(player,
                    new net.minecraft.world.phys.Vec3(4, 0, 0));
                firstPosition.update(0, 1, 1);
                assertEquals(4f, firstPosition.positionValue(0, true));
                cap.updateTextureName("variant1");
                DemandFixtures.advance(cap);
                DemandFixtures.advance(arm);
                assertEquals(19f, readRoaming(arm, "known"));
                assertSame(variant.model, cap.getLoadedGeoModel().getModel());
                var changed = DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable());
                assertNativeConsumption(cap, changed);
                assertFalse(cap.canConsume(old, old.ctx));
                assertEquals(5, cap.getPhysicsManager(cap.createIngressAnimationEvent()).get(33).getValue(), 0.00001f);
                assertEquals(23f, cap.exactComponent(imType).orElseThrow().time(null));
                assertEquals(4f, cap.exactComponent(firstPosition.getClass()).orElseThrow().positionValue(0, true));
                assertEquals((float)-Math.toRadians(20), cap.getLoadedGeoModel().getFirstPersonHead().getRotationX(),
                    0.00001f);
                for (int i = 0; i < 4; i++) {
                    DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable());
                    assertEquals((float)-Math.toRadians(20),
                        cap.getLoadedGeoModel().getFirstPersonHead().getRotationX(), 0.00001f);
                }
                assertEquals(7f, cap.getExpressionRuntime().snapshot().orElseThrow().payload().values().get("known"));
                cap.updateModelAndTexture(next.modelHash(), "variant0");
                DemandFixtures.advance(cap);
                assertEquals(0f, cap.exactComponent(imType).orElseThrow().time(null));
                assertEquals(4f, cap.exactComponent(firstPosition.getClass()).orElseThrow().positionValue(0, true));
                assertNull(cap.getPhysicsManager(cap.createIngressAnimationEvent()).get(33));
                assertEquals(10, other.getPhysicsManager(other.createIngressAnimationEvent()).get(33).getValue(),
                    0.00001f);
                assertEquals(11f,
                    other.getExpressionRuntime().snapshot().orElseThrow().payload().values().get("known"));
                assertNativeConsumption(other, DemandFixtures.produce(other, 0.25f, RenderContext.levelImmutable()));
            } finally {
                arm.retire();
                other.retire();
            }
        }
    }

    private static <E extends net.minecraft.world.entity.Entity> Object readRoaming(AnimatableEntity<E> owner,
        String name) throws Exception {
        var runtime = owner.getExpressionRuntime();
        try (var watch = runtime.compileTransient(runtime.generation(), "return r." + name + ";",
            com.elfmcys.ysm.expression.aot.TransientExpressionSession.Privilege.LOCAL_WATCH,
            com.elfmcys.ysm.expression.aot.TransientExpressionSession.Purpose.WATCH, "fixture:owner-roaming",
            () -> false)) {
            return runtime.executeTransient(
                owner.getAnimationProcessor().invocationInputs(owner.createIngressAnimationEvent()), watch);
        }
    }

    private static void assertOwnerReleased(AnimatableEntity<?> owner) {
        assertFalse(owner.isAssigned());
        assertFalse(owner.hasInstalledModel());
        assertFalse(owner.hasUnfinishedWork());
        assertTrue(owner.getExpressionRuntime().module().isEmpty());
    }

    private static void assertQueryPattern(AnimatableEntity<?> owner, OwnerResourceFixtures resources,
        com.elfmcys.ysm.geckolib3.geo.GeoRenderData data, AnimatableEntity.InstallationRelation relation, boolean runs,
        boolean evaluates, boolean presents, AnimatableEntity.BlockReason selection) {
        int acquisitions = resources.acquisitions, reports = resources.activeReports;
        float time = owner.getSeekTime();
        var view = data == null ? null : data.modelState.getNativeState().getBonePoses();
        for (int i = 0; i < 10; i++) {
            assertEquals(relation, owner.getInstallationRelation());
            assertEquals(runs, owner.isRunRequired());
            assertEquals(evaluates, owner.canEvaluate());
            assertEquals(evaluates, owner.canAcceptPlayback());
            assertEquals(presents, owner.isPresentationAllowed());
            assertEquals(presents && evaluates, owner.canPrepare(RenderContext.levelImmutable()));
            assertEquals(presents && evaluates && data != null, owner.canConsume(data, RenderContext.levelImmutable()));
            assertEquals(selection, owner.getSelectionBlockReason());
            owner.getModelHash();
            owner.getRequestedTextureName();
            owner.getInstalledModelId();
            owner.hasUnfinishedWork();
        }
        assertEquals(acquisitions, resources.acquisitions);
        assertEquals(reports, resources.activeReports);
        assertEquals(time, owner.getSeekTime());
        if (data != null)
            assertSame(view, data.modelState.getNativeState().getBonePoses());
    }

    @SuppressWarnings("unchecked")
    static void verifyRoleFailureAndCleanup(AnimatableEntity<?> owner, OwnerResourceFixtures resources,
        ModelRenderTarget a, ModelRenderTarget badGeometry, ModelRenderTarget badControllers, String factoryName,
        Consumer<com.elfmcys.ysm.model.domain.Hash256> select) throws Exception {
        select.accept(a.modelHash());
        var old = DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable());
        assertNativeConsumption(owner, old);
        long generation = owner.getExpressionRuntime().generation();
        if (badGeometry.playerResources() != null) {
            var variant = badGeometry.playerResources().variants().get("variant0");
            field(variant.getClass(), "mainModel").set(variant, null);
            field(variant.getClass(), "armModel").set(variant, null);
        } else {
            var scoped = field(ModelRenderTarget.class, "targetResources").get(badGeometry);
            field(scoped.getClass(), "model").set(scoped, null);
        }
        select.accept(badGeometry.modelHash());
        DemandFixtures.advance(owner);
        assertSame(a, owner.getModelRenderTarget());
        assertEquals(generation, owner.getExpressionRuntime().generation());
        assertNativeConsumption(owner, old);
        assertEquals(1, resources.leases.get(resources.leases.size() - 1).closes);

        var definition = field(ModelRenderTarget.class, "targetResources").get(badControllers);
        var factory = field(definition.getClass(), factoryName);
        var original = (java.util.function.BiConsumer<Object,
            com.elfmcys.ysm.geckolib3.core.manager.AnimationData>)factory.get(definition);
        int[] calls = {0};
        var rejection = new IllegalArgumentException("controlled real role controller rejection");
        factory.set(definition, (java.util.function.BiConsumer<Object,
            com.elfmcys.ysm.geckolib3.core.manager.AnimationData>)(actual, animations) -> {
                original.accept(actual, animations);
                calls[0]++;
                assertSame(a, owner.getModelRenderTarget());
                assertEquals(generation, owner.getExpressionRuntime().generation());
                assertThrows(IllegalStateException.class, owner::reset);
                assertThrows(IllegalStateException.class, owner::retire);
                assertThrows(IllegalStateException.class, () -> owner.updateModelHash(a.modelHash()));
                assertThrows(IllegalStateException.class,
                    () -> DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable()));
                throw rejection;
            });
        select.accept(badControllers.modelHash());
        DemandFixtures.advance(owner);
        assertSame(rejection, owner.modelLoadFailure());
        assertTrue(owner.isAssigned());
        assertSame(a, owner.getModelRenderTarget());
        assertEquals(generation, owner.getExpressionRuntime().generation());
        assertNativeConsumption(owner, old);
        DemandFixtures.advance(owner);
        assertEquals(1, calls[0]);
        assertEquals(0, resources.leases.get(0).closes);

        select.accept(a.modelHash());
        owner.reloadGeoModel();
        assertTrue(owner.isAssigned());
        assertFalse(owner.canConsume(old, old.ctx));
        DemandFixtures.advance(owner);
        var replacement = DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable());
        assertNativeConsumption(owner, replacement);
        NativeModelState[] parts = null;
        if (owner instanceof CustomFirstPersonArmEntity arm) {
            for (var part : FirstPersonPart.values()) {
                var prepared = arm.preparePart(part, 0.25f);
                assertNotNull(prepared);
                assertTrue(arm.renderPart(prepared, () -> new Probe().consumer, new PoseStack().last(), 0, 0, -1));
            }
            parts = outputs((FirstPersonModelState)field(CustomFirstPersonArmEntity.class, "firstPersonState").get(arm))
                .clone();
        }
        var cleanup = new IllegalStateException("controlled lease cleanup failure");
        var lease = resources.leases.get(resources.leases.size() - 1);
        lease.onClose = () -> {
            throw cleanup;
        };
        assertSame(cleanup, assertThrows(IllegalStateException.class, owner::retire));
        assertOwnerReleased(owner);
        assertFalse(owner.hasInstalledModel());
        assertTrue(owner.getExpressionRuntime().module().isEmpty());
        assertFalse(replacement.modelState.isValid());
        if (parts != null)
            for (var output : parts)
                assertFalse(output.isValid());
        owner.retire();
        assertEquals(1, lease.closes);
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitWaiting(Thread waiter) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (waiter.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
            Thread.onSpinWait();
        assertEquals(Thread.State.WAITING, waiter.getState());
    }

    static void assertNativeConsumption(AnimatableEntity<?> owner, com.elfmcys.ysm.geckolib3.geo.GeoRenderData data) {
        var output = new Probe();
        assertTrue(owner.consumePrepared(data, ready -> NativeRenderer.render(output.consumer, new PoseStack().last(),
            ready.modelState.getNativeState(), ready.modelState.getVertexCount(), 0, 0, -1, RenderContextType.LEVEL)));
        assertFalse(output.vertices.isEmpty());
    }

    private static NativeModelState[] outputs(FirstPersonModelState state) throws Exception {
        return (NativeModelState[])field(FirstPersonModelState.class, "outputs").get(state);
    }

    @Test
    void realDeltaIngressDropsPlaybackWithoutInstallingAndKeepsTheOtherProjection() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var remote = allocate(FakeRemotePlayer.class);
            field(net.minecraft.world.entity.Entity.class, "position").set(remote, net.minecraft.world.phys.Vec3.ZERO);
            var owner = new PlayerAnimatableCapability(remote);
            remote.owner = owner;
            var resources = new OwnerResourceFixtures();
            resources.attach(owner);
            var previousLevel = Minecraft.getInstance().level;
            var level = allocate(FakeClientLevel.class);
            level.remote = remote;
            Minecraft.getInstance().level = level;
            try {
                var receive = playerStateReceiver();
                var delta = com.elfmcys.ysm.network.forge.PlayerStateHandler.newDelta(42)
                    .setGameplay(com.elfmcys.ysm.proto.network.GameplayStateProto.newBuilder().setHealth(17).build())
                    .setAnimation(
                        com.elfmcys.ysm.proto.network.AnimationStateProto.newBuilder().setAnimationId("wave").build())
                    .build();
                receive.invoke(null, delta);
                assertFalse(owner.isAssigned());
                assertFalse(owner.isPlayingExtraAnimation());
                assertEquals(17, owner.getStateTracker().health());
                assertEquals(0, resources.acquisitions);
                var target = resources.target(1, module, fixture.model);
                target.playerResources().animations().put("wave", allocate(Animation.class));
                var assigned = com.elfmcys.ysm.network.forge.PlayerStateHandler.newDelta(42)
                    .setModel(com.elfmcys.ysm.proto.network.ModelSelectionStateProto.newBuilder()
                        .setModel(com.elfmcys.ysm.proto.network.ModelReferenceProto.newBuilder()
                            .setModelHash(java.nio.ByteBuffer.wrap(target.modelHash().bytes())).build())
                        .setTextureId("variant0").build())
                    .build();
                receive.invoke(null, assigned);
                DemandFixtures.advance(owner);
                assertFalse(owner.isPlayingExtraAnimation(), "Ready does not replay an earlier instantaneous request");
                receive.invoke(null, delta);
                assertTrue(owner.isPlayingExtraAnimation());
                owner.setDisabled(true);
                assertTrue(owner.isAssigned());
                assertEquals(17, owner.getStateTracker().health());
                receive.invoke(null, delta);
                assertFalse(owner.isPlayingExtraAnimation());
                owner.stopExtraAnimation();
                assertEquals(1, resources.acquisitions);
                owner.setDisabled(false);
                DemandFixtures.advance(owner);
                assertFalse(owner.isPlayingExtraAnimation());
            } finally {
                Minecraft.getInstance().level = previousLevel;
                owner.retire();
            }
        }
    }

    @Test
    void catalogCardRetirementReleasesInstalledStateAndAnimationReferences() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            var constructor = com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.class
                .getDeclaredConstructor(net.minecraft.world.entity.player.Player.class);
            constructor.setAccessible(true);
            var owner = constructor.newInstance(player);
            var resources = new OwnerResourceFixtures();
            var clock = new java.util.concurrent.atomic.AtomicLong();
            resources.attach(owner, clock::get);
            var target = resources.target(1, module, fixture.model);
            var coordinator = resources.coordinator(clock::get);
            var page = coordinator.openPage("catalog");
            var stateType = Class.forName("com.elfmcys.ysm.client.gui.button.CatalogModelCardState");
            var state = allocate(stateType);
            var intent =
                new com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Target(target.modelHash(), "variant0");
            field(stateType, "entity").set(state, owner);
            field(stateType, "page").set(state, page);
            field(stateType, "target").set(state, intent);
            var animationType = Class.forName("com.elfmcys.ysm.client.gui.button.CatalogModelPreviewAnimationState");
            var animationConstructor = animationType.getDeclaredConstructor();
            animationConstructor.setAccessible(true);
            var animations = animationConstructor.newInstance();
            field(stateType, "previewAnimations").set(state, animations);
            var display = (com.elfmcys.ysm.client.demand.ClientDemandCoordinator.CardDisplay)state;
            page.card(display, intent);
            var draw = allocate(ProbePlayerRenderer.class);
            var output = new Probe();
            try {
                page.pointer(intent);
                clock.set(299);
                coordinator.tick();
                assertFalse(owner.isAssigned());
                assertFalse(owner.hasInstalledModel());
                assertEquals(0, resources.acquisitions);
                clock.set(300);
                coordinator.tick();
                assertTrue(owner.hasInstalledModel());
                assertTrue(draw.renderReplacement(owner, null, 0, 0.25f, new PoseStack(), output, 123, () -> {
                }));
                assertFalse(output.vertices.isEmpty());
                var request = owner.assignment().request();
                var other = resources.findReady(request).orElseThrow();
                var oldScope = owner.demandScope();
                field(stateType, "configuredTarget").set(state, target);
                field(animationType, "hoverFadeoutMillis").set(animations,
                    (java.util.function.DoubleSupplier)() -> target.playerResources().animations().size());
                page.pointer(null);
                assertTrue(oldScope.isClosed());
                assertFalse(owner.isAssigned());
                assertFalse(owner.hasInstalledModel());
                assertFalse(owner.hasUnfinishedWork());
                assertNull(owner.displayedModel());
                assertNull(field(stateType, "configuredTarget").get(state));
                assertEquals(0d,
                    ((java.util.function.DoubleSupplier)field(animationType, "hoverFadeoutMillis").get(animations))
                        .getAsDouble());
                assertFalse(draw.renderReplacement(owner, null, 0, 0.25f, new PoseStack(), new Probe(), 123, () -> {
                }));
                page.pointer(intent);
                clock.set(599);
                coordinator.tick();
                assertFalse(owner.hasInstalledModel());
                clock.set(600);
                coordinator.tick();
                assertTrue(owner.hasInstalledModel());
                assertNotSame(oldScope, owner.demandScope());
                page.close();
                assertNull(owner.displayedModel());
                assertInstanceOf(com.elfmcys.ysm.model.resource.client.AcquireResult.Ready.class, other.poll());
                other.close();
            } finally {
                page.close();
                owner.retire();
                coordinator.close();
            }
        }
    }

    @Test
    void catalogReadyReceptionUsesTheProductionInstallationWithoutStartingPreparation() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            var type = com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.class;
            var constructor = type.getDeclaredConstructor(net.minecraft.world.entity.player.Player.class,
                com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.class);
            constructor.setAccessible(true);
            var owner =
                constructor.newInstance(player, com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.FULL);
            var resources = new OwnerResourceFixtures();
            resources.attach(owner);
            var target = resources.target(1, module, fixture.model);
            var request = resources.resourceRequest(target.modelHash(), "player", "variant0",
                com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.FULL,
                net.minecraft.client.player.AbstractClientPlayer.class);
            var lease = new OwnerResourceFixtures.Lease(request, target);
            var receive =
                type.getDeclaredMethod("receiveReady", com.elfmcys.ysm.model.resource.client.ResourceRequest.class,
                    com.elfmcys.ysm.model.resource.client.ResourceLease.class);
            receive.setAccessible(true);
            try {
                assertEquals(com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult.INSTALLED,
                    receive.invoke(owner, request, lease));
                assertSame(target, owner.displayedModel());
                var output = new Probe();
                assertTrue(allocate(ProbePlayerRenderer.class).renderReplacement(owner, null, 0, 0.25f, new PoseStack(),
                    output, 123, () -> {
                    }));
                assertFalse(output.vertices.isEmpty());
                assertEquals(0, resources.acquisitions);
                owner.retire();
                assertEquals(1, lease.closes);
            } finally {
                owner.retire();
            }
        }
    }

    @Test
    void guiMatchingAndSuppliedReadyUseTheCompleteOwnerAndActualOutput() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            var constructor = com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.class
                .getDeclaredConstructor(net.minecraft.world.entity.player.Player.class);
            constructor.setAccessible(true);
            var owner = constructor.newInstance(player);
            var resources = new OwnerResourceFixtures();
            resources.attach(owner);
            var target = resources.target(1, module, fixture.model);
            var draw = allocate(ProbePlayerRenderer.class);
            var output = new Probe();
            int[] handoffs = {0};
            try {
                owner.updateModelAndTexture(target.modelHash(), "variant0");
                DemandFixtures.advance(owner);
                assertTrue(
                    draw.renderReplacement(owner, null, 0, 0.25f, new PoseStack(), output, 123, () -> handoffs[0]++));
                assertFalse(output.vertices.isEmpty());
                assertSame(target, owner.displayedModel());
                owner.updateModelAndTexture(OwnerResourceFixtures.hash(2), "next");
                assertNull(owner.displayedModel());
                assertFalse(draw.renderReplacement(owner, null, 0, 0.25f, new PoseStack(), new Probe(), 123,
                    () -> handoffs[0]++));
                assertEquals(1, handoffs[0]);
                assertSame(target, owner.getModelRenderTarget());
                owner.reset();
                owner.updateModelAndTexture(target.modelHash(), "variant0");
                var request = resources.resourceRequest(target.modelHash(), "player", "variant0",
                    com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.GUI,
                    com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class);
                var lease = new OwnerResourceFixtures.Lease(request, target);
                var supplied = owner.getClass().getDeclaredMethod("installPreviewResource",
                    com.elfmcys.ysm.model.resource.client.ResourceRequest.class,
                    com.elfmcys.ysm.model.resource.client.ResourceLease.class);
                supplied.setAccessible(true);
                supplied.invoke(owner, request, lease);
                int before = resources.acquisitions;
                assertTrue(draw.renderReplacement(owner, null, 0, 0.25f, new PoseStack(), new Probe(), 123,
                    () -> handoffs[0]++));
                assertEquals(before, resources.acquisitions);
                owner.retire();
                assertEquals(1, lease.closes);
                assertFalse(draw.renderReplacement(owner, null, 0, 0.25f, new PoseStack(), new Probe(), 123,
                    () -> handoffs[0]++));
            } finally {
                owner.retire();
            }
        }
    }

    @Test
    void guiSelectionChangedByTheHostHookRejectsTheBorrowedResultAndWaitsForItsOwnReady() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            var constructor = com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.class
                .getDeclaredConstructor(net.minecraft.world.entity.player.Player.class);
            constructor.setAccessible(true);
            var owner = constructor.newInstance(player);
            var resources = new OwnerResourceFixtures();
            resources.attach(owner);
            var a = resources.target(1, module, fixture.model);
            var b = resources.target(2, module, fixture.model);
            resources.delay(b.modelHash());
            owner.setResourceDemandMode(com.elfmcys.ysm.client.demand.ModelResourceDemand.Mode.CACHED);
            var draw = allocate(ProbePlayerRenderer.class);
            var output = new Probe();
            int[] handoffs = {0};
            try {
                owner.updateModelAndTexture(a.modelHash(), "variant0");
                DemandFixtures.advance(owner);
                try (var dispatch = hostPreHook(() -> owner.updateModelAndTexture(b.modelHash(), "variant0"))) {
                    assertFalse(draw.renderReplacement(owner, null, 0, 0.25f, new PoseStack(), output, 123,
                        () -> handoffs[0]++));
                }
                assertTrue(output.vertices.isEmpty());
                assertEquals(0, handoffs[0]);
                assertNull(owner.displayedModel());
                assertFalse(owner.canPrepare(RenderContext.levelImmutable()));
                assertFalse(
                    draw.renderReplacement(owner, null, 0, 0.25f, new PoseStack(), output, 123, () -> handoffs[0]++));
                DemandFixtures.advance(owner);
                assertTrue(owner.hasUnfinishedWork());
                resources.deliver(b.modelHash());
                DemandFixtures.advance(owner);
                assertTrue(
                    draw.renderReplacement(owner, null, 0, 0.25f, new PoseStack(), output, 123, () -> handoffs[0]++));
                assertSame(b, owner.displayedModel());
                assertFalse(output.vertices.isEmpty());
                assertEquals(1, handoffs[0]);
            } finally {
                owner.retire();
            }
        }
    }

    @Test
    void worldAndGuiKeepTheirConsumptionRulesAcrossReentrantSelectionFailureReloadAndRetirement() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            var constructor = com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.class
                .getDeclaredConstructor(net.minecraft.world.entity.player.Player.class);
            constructor.setAccessible(true);
            for (boolean gui : new boolean[] {false, true}) {
                CustomPlayerEntity owner =
                    gui ? constructor.newInstance(player) : new PlayerAnimatableCapability(player);
                var resources = new OwnerResourceFixtures();
                resources.attach(owner);
                var a = resources.target(1, module, fixture.model);
                var b = resources.target(2, module, fixture.model);
                var draw = allocate(ProbePlayerRenderer.class);
                var output = new Probe();
                int[] handoffs = {0};
                java.util.function.BooleanSupplier render = () -> gui
                    ? draw.renderReplacement(owner, null, 0, 0.25f, new PoseStack(), output, 123, () -> handoffs[0]++)
                    : draw.renderReplacement((PlayerAnimatableCapability)owner, 0, 0.25f, new PoseStack(), output, 123,
                        () -> handoffs[0]++);
                try {
                    owner.updateModelAndTexture(a.modelHash(), "variant0");
                    DemandFixtures.advance(owner);
                    assertTrue(render.getAsBoolean());
                    assertFalse(output.vertices.isEmpty());
                    var previous = DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable());
                    long generation = owner.getExpressionRuntime().generation();
                    resources.delay(b.modelHash());
                    output.vertices.clear();
                    try (var dispatch = hostPreHook(() -> owner.updateModelAndTexture(b.modelHash(), "variant0"))) {
                        assertEquals(!gui, render.getAsBoolean());
                    }
                    assertEquals(gui, output.vertices.isEmpty());
                    assertSame(a, owner.getModelRenderTarget());
                    assertEquals(gui ? 1 : 2, handoffs[0]);
                    DemandFixtures.advance(owner);
                    assertEquals(!gui, render.getAsBoolean());
                    assertTrue(owner.hasUnfinishedWork());
                    var variant = b.playerResources().variants().get("variant0");
                    field(variant.getClass(), "mainModel").set(variant, null);
                    resources.deliver(b.modelHash());
                    output.vertices.clear();
                    assertEquals(!gui, render.getAsBoolean());
                    assertEquals(gui, output.vertices.isEmpty());
                    assertSame(a, owner.getModelRenderTarget());
                    assertEquals(b.modelHash(), owner.getModelHash());
                    assertEquals(generation, owner.getExpressionRuntime().generation());
                    assertEquals(0, resources.leases.get(0).closes);
                    field(variant.getClass(), "mainModel").set(variant, fixture.model);
                    owner.reloadGeoModel();
                    DemandFixtures.advance(owner);
                    assertTrue(render.getAsBoolean());
                    assertTrue(owner.getExpressionRuntime().generation() > generation);
                    assertFalse(owner.canConsume(previous, previous.ctx));
                    var installed = DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable());
                    var oldView = installed.modelState.getNativeState().getBonePoses();
                    float time = owner.getSeekTime();
                    damagePose(installed.modelState.getNativeState());
                    var rejected = new Probe();
                    assertFalse(NativeRenderer.render(rejected.consumer, new PoseStack().last(),
                        installed.modelState.getNativeState(), installed.modelState.getVertexCount(), 0, 0, -1,
                        RenderContextType.LEVEL));
                    assertTrue(rejected.vertices.isEmpty());
                    assertTrue(render.getAsBoolean());
                    assertNotSame(oldView, installed.modelState.getNativeState().getBonePoses());
                    assertEquals(time, owner.getSeekTime());
                    var lease = resources.leases.get(resources.leases.size() - 1);
                    output.vertices.clear();
                    try (var dispatch = hostPreHook(() -> {
                        owner.retire();
                        assertFalse(owner.isAssigned());
                        assertEquals(0, lease.closes);
                        assertFalse(owner.canEvaluate());
                        assertFalse(owner.canConsume(installed, installed.ctx));
                    })) {
                        assertFalse(render.getAsBoolean());
                    }
                    assertTrue(output.vertices.isEmpty());
                    assertOwnerReleased(owner);
                    assertEquals(1, lease.closes);
                    var replacement = new PlayerAnimatableCapability(player);
                    var nextResources = new OwnerResourceFixtures();
                    nextResources.attach(replacement);
                    nextResources.target(2, module, fixture.model);
                    try {
                        replacement.updateModelAndTexture(b.modelHash(), "variant0");
                        assertNativeConsumption(replacement,
                            DemandFixtures.produce(replacement, 0.25f, RenderContext.levelImmutable()));
                        assertFalse(owner.consumePrepared(installed, data -> {
                            fail("Old owner must stay sealed");
                            return true;
                        }));
                    } finally {
                        replacement.retire();
                    }
                } finally {
                    owner.retire();
                }
            }
        }
    }

    private static AutoCloseable hostPreHook(Runnable hook) throws Exception {
        // The standalone JVM has no Forge event-class transformer; replace only host dispatch.
        var slot = field(MinecraftForge.class, "EVENT_BUS");
        var previous = MinecraftForge.EVENT_BUS;
        var dispatch = Proxy.newProxyInstance(FirstPersonOwnerIntegrationTest.class.getClassLoader(),
            new Class<?>[] {net.minecraftforge.eventbus.api.IEventBus.class}, (proxy, method, args) -> {
                if (method.getName().equals("post")
                    && args[0] instanceof net.minecraftforge.client.event.RenderLivingEvent.Pre) {
                    hook.run();
                    return false;
                }
                return method.invoke(previous, args);
            });
        var unsafe = UnsafeUtil.getUnsafe();
        var base = unsafe.staticFieldBase(slot);
        var offset = unsafe.staticFieldOffset(slot);
        unsafe.putObjectVolatile(base, offset, dispatch);
        return () -> unsafe.putObjectVolatile(base, offset, previous);
    }

    @Test
    void thirdPersonHandoffBorrowsItsPreparationAcrossHookRetirementAndHiddenOutput() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            var resources = new OwnerResourceFixtures();
            resources.attach(cap);
            var target = resources.target(1, module, fixture.model);
            var draw = allocate(ProbePlayerRenderer.class);
            var output = new Probe();
            int[] handoffs = {0};
            cap.updateModelAndTexture(target.modelHash(), "variant0");
            DemandFixtures.advance(cap);
            assertTrue(draw.renderReplacement(cap, 0, 0.25f, new PoseStack(), output, 123, () -> handoffs[0]++));
            assertFalse(output.vertices.isEmpty());
            for (var bone : cap.getLoadedGeoModel().getSortedBones())
                bone.setHidden(true, false);
            var empty = new Probe();
            assertTrue(draw.renderReplacement(cap, 0, 0.25f, new PoseStack(), empty, 123, () -> handoffs[0]++));
            assertTrue(empty.vertices.isEmpty());
            Consumer<SpecialPlayerRenderEvent> retire = event -> {
                cap.retire();
                assertFalse(cap.isAssigned());
                assertEquals(0, resources.leases.get(0).closes);
            };
            MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                SpecialPlayerRenderEvent.class, retire);
            try {
                assertFalse(
                    draw.renderReplacement(cap, 0, 0.25f, new PoseStack(), new Probe(), 123, () -> handoffs[0]++));
                assertEquals(2, handoffs[0]);
                assertEquals(1, resources.leases.get(0).closes);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(retire);
            }
        }
    }

    @Test
    void realBodyRetainsAWhileBWithAnotherTextureWaitsAndPublishesOnlyCompleteB() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var variant = new Fixture("parts-b", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            var resources = new OwnerResourceFixtures();
            resources.attach(cap);
            var a = resources.target(1, module, fixture.model);
            var b = resources.target(2, module, fixture.model, variant.model);
            resources.delay(b.modelHash());
            cap.updateModelAndTexture(a.modelHash(), "variant0");
            var previous = DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable());
            assertNativeConsumption(cap, previous);
            cap.updateModelAndTexture(b.modelHash(), "variant1");
            var retained = DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable());
            assertNativeConsumption(cap, retained);
            assertEquals(AnimatableEntity.InstallationRelation.RETAINED, cap.getInstallationRelation());
            assertSame(a, cap.getModelRenderTarget());
            assertEquals("variant1", cap.getRequestedTextureName());
            assertEquals(new ResourceLocation("ysm", "fixture1"), retained.texture);
            assertEquals(0, resources.leases.get(0).closes);
            resources.deliver(b.modelHash());
            var installed = DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable());
            assertNativeConsumption(cap, installed);
            assertEquals(b.modelHash(), cap.getModelHash());
            assertEquals("variant1", cap.getRequestedTextureName());
            assertEquals(AnimatableEntity.InstallationRelation.MATCHING, cap.getInstallationRelation());
            assertSame(variant.model, cap.getLoadedGeoModel().getModel());
            assertEquals(new ResourceLocation("ysm", "fixture2"), installed.texture);
            assertEquals(1, resources.leases.get(0).closes);
            assertFalse(cap.canConsume(previous, previous.ctx));
        }
    }

    @Test
    void realBodyAndArmUseResidentDefaultDuringTheirIndependentPendingAcquisitions() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            var bodyResources = new OwnerResourceFixtures();
            bodyResources.attach(cap);
            var armResources = new OwnerResourceFixtures();
            var arm = new CustomFirstPersonArmEntity(player, cap);
            armResources.attach(arm);
            var body = bodyResources.target(1, module, fixture.model);
            var arms = armResources.target(1, module, fixture.model);
            bodyResources.target(0, module, fixture.model);
            armResources.target(0, module, fixture.model);
            bodyResources.delay(body.modelHash());
            armResources.delay(arms.modelHash());
            try {
                cap.updateModelAndTexture(body.modelHash(), "variant0");
                assertNativeConsumption(cap, DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable()));
                assertEquals(AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE, cap.getInstallationRelation());
                DemandFixtures.advance(arm);
                var right = arm.preparePart(FirstPersonPart.RIGHT_ARM, 0.25f);
                assertNotNull(right);
                assertEquals(AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE, arm.getInstallationRelation());
                var output = new Probe();
                assertTrue(arm.renderPart(right, () -> output.consumer, new PoseStack().last(), 0, 0, -1));
                assertFalse(output.vertices.isEmpty());
                assertTrue(cap.hasUnfinishedWork());
                assertTrue(arm.hasUnfinishedWork());
                bodyResources.deliver(body.modelHash());
                assertNativeConsumption(cap, DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable()));
                assertEquals(AnimatableEntity.InstallationRelation.MATCHING, cap.getInstallationRelation());
                assertEquals(AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE, arm.getInstallationRelation());
                armResources.deliver(arms.modelHash());
                DemandFixtures.advance(arm);
                right = arm.preparePart(FirstPersonPart.RIGHT_ARM, 0.25f);
                assertEquals(AnimatableEntity.InstallationRelation.MATCHING, arm.getInstallationRelation());
                assertTrue(arm.renderPart(right, () -> output.consumer, new PoseStack().last(), 0, 0, -1));
            } finally {
                arm.retire();
            }
        }
    }

    @Test
    void realHumanoidMissIoAndDeniedAcquisitionsDoNotRetryPerFrameAndExplicitReloadCanRecover() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            for (int scenario = 0; scenario < 3; scenario++) {
                var owner = new CustomPlayerEntity(player, false, false) {};
                var resources = new OwnerResourceFixtures();
                resources.attach(owner, () -> 0);
                var target = resources.target(1, module, fixture.model);
                resources.target(0, module, fixture.model);
                var delayed = resources.delay(target.modelHash());
                try {
                    owner.updateModelAndTexture((com.elfmcys.ysm.model.domain.Hash256)null, "variant0");
                    DemandFixtures.advance(owner);
                    owner.updateModelAndTexture(target.modelHash(), "variant0");
                    DemandFixtures.advance(owner);
                    assertTrue(owner.hasUnfinishedWork());
                    if (scenario == 0)
                        delayed.complete(java.util.Optional.empty());
                    else
                        delayed.completeExceptionally(scenario == 1
                            ? new java.io.UncheckedIOException(new java.io.IOException("controlled asset I/O failure"))
                            : new SecurityException("UNAUTHORIZED"));
                    DemandFixtures.advance(owner);
                    int acquired = resources.acquisitions, failures = resources.failureReports;
                    for (int frame = 0; frame < 10; frame++) {
                        DemandFixtures.advance(owner);
                        assertNativeConsumption(owner,
                            DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable()));
                    }
                    assertEquals(acquired, resources.acquisitions);
                    assertEquals(failures, resources.failureReports);
                    assertEquals(scenario == 0 ? 0 : 1, failures);
                    assertFalse(owner.hasUnfinishedWork());
                    assertEquals(target.modelHash(), owner.getModelHash());
                    assertEquals(AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE,
                        owner.getInstallationRelation());
                    resources.pending.remove(target.modelHash());
                    owner.reloadGeoModel();
                    assertNativeConsumption(owner,
                        DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable()));
                    assertSame(target, owner.getModelRenderTarget());
                    assertEquals(AnimatableEntity.InstallationRelation.MATCHING, owner.getInstallationRelation());
                    assertNull(owner.modelLoadFailure());
                } finally {
                    owner.retire();
                }
            }
        }
    }

    @Test
    void realScopedPendingKeepsTheHostAndReadyActivatesOnlyItsOwnTarget() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var builder = new ForgeConfigSpec.Builder();
            var oldProjectile = ClientConfig.DISABLE_PROJECTILE_MODEL;
            var oldVehicle = ClientConfig.DISABLE_VEHICLE_MODEL;
            ClientConfig.DISABLE_PROJECTILE_MODEL = builder.define("projectile", false);
            ClientConfig.DISABLE_VEHICLE_MODEL = builder.define("vehicle", false);
            builder.build().setConfig(CommentedConfig.inMemory());
            try {
                for (var kind : new com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind[] {
                    com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE,
                    com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.VEHICLE}) {
                    var resources = new OwnerResourceFixtures();
                    var target = resources.scopedTarget(1, module, fixture.model, kind);
                    resources.delay(target.modelHash());
                    AnimatableEntity<?> owner =
                        kind == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE
                            ? new com.elfmcys.ysm.capability.ProjectileAnimatableCapability(allocate(FakeArrow.class))
                            : new com.elfmcys.ysm.capability.VehicleAnimatableCapability(allocate(FakeBoat.class));
                    field(net.minecraft.world.entity.Entity.class, "position").set(owner.getEntity(),
                        net.minecraft.world.phys.Vec3.ZERO);
                    resources.attach(owner);
                    try (var catalog = resources.useCatalog()) {
                        if (owner instanceof com.elfmcys.ysm.capability.ProjectileAnimatableCapability projectile)
                            projectile.init(target.modelHash());
                        else
                            ((com.elfmcys.ysm.capability.VehicleAnimatableCapability)owner).init(target.modelHash());
                        assertNull(DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable()));
                        assertTrue(owner.hasUnfinishedWork());
                        assertFalse(owner.hasInstalledModel());
                        assertFalse(owner.canPrepare(RenderContext.levelImmutable()));
                        resources.deliver(target.modelHash());
                        assertNativeConsumption(owner,
                            DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable()));
                        assertSame(target, owner.getModelRenderTarget());
                        assertEquals(AnimatableEntity.InstallationRelation.MATCHING, owner.getInstallationRelation());
                        owner.reloadGeoModel();
                        var denied = resources.delay(target.modelHash());
                        assertNull(DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable()));
                        denied.completeExceptionally(new SecurityException("UNAUTHORIZED"));
                        assertNull(DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable()));
                        assertFalse(owner.hasInstalledModel());
                        assertFalse(owner.canPrepare(RenderContext.levelImmutable()));
                        assertNotNull(owner.modelLoadFailure());
                        assertEquals(target.modelHash(), owner.getModelHash());
                        resources.pending.remove(target.modelHash());
                        owner.reloadGeoModel();
                        assertNativeConsumption(owner,
                            DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable()));
                        var otherKind = kind == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE
                            ? com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.VEHICLE
                            : com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE;
                        resources.declare(target.modelHash(), otherKind, true);
                        assertNull(DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable()));
                        assertFalse(owner.hasInstalledModel());
                        assertEquals(target.modelHash(), owner.getModelHash());
                    } finally {
                        owner.retire();
                    }
                }
            } finally {
                ClientConfig.DISABLE_PROJECTILE_MODEL = oldProjectile;
                ClientConfig.DISABLE_VEHICLE_MODEL = oldVehicle;
            }
        }
    }

    @Test
    void scopedRolesUseCatalogTargetsPauseAndRevokeMissingTextureWithoutHumanoidFallback() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var builder = new ForgeConfigSpec.Builder();
            var oldProjectile = ClientConfig.DISABLE_PROJECTILE_MODEL;
            var oldVehicle = ClientConfig.DISABLE_VEHICLE_MODEL;
            ClientConfig.DISABLE_PROJECTILE_MODEL = builder.define("projectile", false);
            ClientConfig.DISABLE_VEHICLE_MODEL = builder.define("vehicle", false);
            builder.build().setConfig(CommentedConfig.inMemory());
            try {
                for (var kind : List.of(com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE,
                    com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.VEHICLE)) {
                    var resources = new OwnerResourceFixtures();
                    var target = resources.scopedTarget(3 + kind.ordinal(), module, fixture.model, kind);
                    var subject = kind == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE
                        ? allocate(FakeArrow.class) : allocate(FakeBoat.class);
                    field(net.minecraft.world.entity.Entity.class, "position").set(subject,
                        net.minecraft.world.phys.Vec3.ZERO);
                    AnimatableEntity<?> owner =
                        subject instanceof net.minecraft.world.entity.projectile.Projectile projectile
                            ? new com.elfmcys.ysm.capability.ProjectileAnimatableCapability(projectile)
                            : new com.elfmcys.ysm.capability.VehicleAnimatableCapability(subject);
                    resources.attach(owner);
                    try (var catalog = resources.useCatalog()) {
                        if (owner instanceof com.elfmcys.ysm.capability.ProjectileAnimatableCapability projectile)
                            projectile.init(target.modelHash());
                        else
                            ((com.elfmcys.ysm.capability.VehicleAnimatableCapability)owner).init(target.modelHash());
                        var data = DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable());
                        var output = new Probe();
                        assertTrue(owner.consumePrepared(data,
                            borrowed -> NativeRenderer.render(output.consumer, new PoseStack().last(),
                                borrowed.modelState.getNativeState(), borrowed.modelState.getVertexCount(), 123, 0, -1,
                                RenderContextType.LEVEL)));
                        assertFalse(output.vertices.isEmpty());
                        var setting = kind == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE
                            ? ClientConfig.DISABLE_PROJECTILE_MODEL : ClientConfig.DISABLE_VEHICLE_MODEL;
                        setting.set(true);
                        DemandFixtures.advance(owner);
                        assertTrue(owner.isAssigned());
                        assertFalse(owner.hasInstalledModel());
                        assertEquals(1, resources.leases.get(0).closes);
                        setting.set(false);
                        DemandFixtures.advance(owner);
                        assertSame(target, owner.getModelRenderTarget());
                        resources.declare(target.modelHash(), kind, false);
                        DemandFixtures.advance(owner);
                        assertNull(owner.getModelRenderTarget());
                        assertTrue(owner.isAssigned());
                        assertTrue(owner.getExpressionRuntime().module().isEmpty());
                    } finally {
                        owner.retire();
                    }
                }
            } finally {
                ClientConfig.DISABLE_PROJECTILE_MODEL = oldProjectile;
                ClientConfig.DISABLE_VEHICLE_MODEL = oldVehicle;
            }
        }
    }

    @Test
    void joinedAsyncProducerRechecksPublicationAndCurrentMetadata() throws Exception {
        try (var fixture = new Fixture("parts-a"); var module = TestExpressionModules.create()) {
            var body = new Timeline(player);
            try {
                body.install(fixture.model, module);
                startAsync(body, 0.25f);
                var lifecycle = field(AnimatableEntity.class, "lifecycle").get(body);
                var future =
                    (java.util.concurrent.Future<?>)field(EntityExecutionLifecycle.class, "asyncTask").get(lifecycle);
                var produced =
                    (com.elfmcys.ysm.geckolib3.geo.GeoRenderData)future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                produced.modelState.getNativeState().invalidate();
                body.texture = new ResourceLocation("ysm", "joined");
                var joined = body.produce(0.25f, RenderContext.levelImmutable());
                assertTrue(joined.modelState.isValid());
                assertEquals(body.texture, joined.texture);
                assertEquals(1, body.actions);
                var output = new Probe();
                assertTrue(
                    NativeRenderer.render(output.consumer, new PoseStack().last(), joined.modelState.getNativeState(),
                        joined.modelState.getVertexCount(), 123, 456, -1, RenderContextType.LEVEL));
                assertFalse(output.vertices.isEmpty());
            } finally {
                body.waitForAsyncUpdate();
                body.retire();
            }
        }
    }

    @Test
    void realSessionFailureRevokesOrdinaryUseWhileCatalogDisappearanceRetainsVerifiedOutput() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            var resources = new OwnerResourceFixtures();
            resources.attach(cap);
            var a = resources.target(1, module, fixture.model);
            var fallback = resources.target(0, module, fixture.model);
            cap.updateModelAndTexture(a.modelHash(), "variant0");
            DemandFixtures.advance(cap);
            var prepared = DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable());
            resources.targets.remove(a.modelHash());
            DemandFixtures.advance(cap);
            assertTrue(cap.canConsume(prepared, prepared.ctx));
            var scoped = new Timeline(player);
            scoped.install(fixture.model, module);
            var scopedOutput = scoped.produce(0.25f, RenderContext.levelImmutable());
            var session = new com.elfmcys.ysm.model.session.client.ClientModelSession(
                com.elfmcys.ysm.network.session.SessionMode.AUTO, true,
                com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot.empty());
            var connection =
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.CLIENTBOUND);
            var runtime = com.elfmcys.ysm.network.forge.ClientSessionRuntime.class;
            var current = field(runtime, "current");
            var previous = current.get(null);
            var type = Class.forName(runtime.getName() + "$ConnectionState");
            var constructor = type.getDeclaredConstructor(net.minecraft.network.Connection.class,
                com.elfmcys.ysm.model.session.client.ClientModelSession.class, java.util.function.Consumer.class,
                Object.class);
            constructor.setAccessible(true);
            var sessionOwner = (AutoCloseable)constructor.newInstance(connection, session,
                (java.util.function.Consumer<String>)ignored -> {
                }, new Object());
            current.set(null, sessionOwner);
            try {
                com.elfmcys.ysm.network.forge.ClientSessionRuntime.failProtocolSession(connection);
                assertEquals(com.elfmcys.ysm.model.session.client.ClientModelSession.State.INTRINSIC_DEFAULT_ONLY,
                    session.state());
                assertTrue(com.elfmcys.ysm.network.forge.ClientSessionRuntime.runIfCurrent(connection, () -> {
                }));
                assertFalse(cap.canEvaluate());
                assertFalse(cap.canConsume(prepared, prepared.ctx));
                assertFalse(scoped.canConsume(scopedOutput, scopedOutput.ctx));
                DemandFixtures.advance(cap);
                DemandFixtures.advance(scoped);
                assertEquals(a.modelHash(), cap.getModelHash());
                assertEquals(fallback.modelHash().roamingHash(), cap.localRoamingKey());
                assertEquals(1, resources.leases.get(0).closes);
                assertTrue(cap.canEvaluate());
                var data = DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable());
                var output = new Probe();
                assertTrue(cap.consumePrepared(data,
                    ready -> NativeRenderer.render(output.consumer, new PoseStack().last(),
                        ready.modelState.getNativeState(), ready.modelState.getVertexCount(), 0, 0, -1,
                        RenderContextType.LEVEL)));
                assertFalse(output.vertices.isEmpty());
                assertFalse(scoped.hasInstalledModel());
                assertTrue(scoped.isAssigned());
                assertTrue(scoped.getExpressionRuntime().module().isEmpty());
            } finally {
                scoped.retire();
                cap.retire();
                current.set(null, previous);
                sessionOwner.close();
            }
        }
    }

    @Test
    void endedModelUseDoesNotCancelPlaybackAlreadyAcceptedByItsAudioOwner() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create()) {
            var playback = new AcceptedPlayback();
            var starts = new java.util.concurrent.atomic.AtomicInteger();
            var owner = new CustomPlayerEntity(player, true, false) {
                @Override
                public java.util.Optional<com.elfmcys.ysm.client.sound.instance.AudioPlaybackFactory>
                    getSoundPlayback(String name) {
                    return java.util.Optional.of(control -> {
                        starts.incrementAndGet();
                        return playback;
                    });
                }
            };
            var resources = new OwnerResourceFixtures();
            resources.attach(owner);
            var target = resources.target(1, module, fixture.model);
            target.playerResources().animations()
                .put("voice", new Animation("voice", 20, com.elfmcys.ysm.geckolib3.core.builder.LoopType.PLAY_ONCE,
                    null, List.of(),
                    List.of(new com.elfmcys.ysm.geckolib3.core.keyframe.event.EventKeyFrame<>(0, "voice")), List.of()));
            try {
                owner.updateModelAndTexture(target.modelHash(), "variant0");
                DemandFixtures.advance(owner);
                owner.playExtraAnimation("voice");
                DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable());
                assertEquals(1, starts.get());
                assertFalse(playback.stopped);
                owner.retire();
                assertOwnerReleased(owner);
                assertEquals(1, resources.leases.get(0).closes);
                assertFalse(playback.stopped, "Controller cleanup must not retire an independently accepted playback");
                owner.playExtraAnimation("voice");
                DemandFixtures.produce(owner, 0.5f, RenderContext.levelImmutable());
                assertEquals(1, starts.get());
                playback.setStopped();
                assertTrue(playback.stopped);
            } finally {
                owner.retire();
            }
        }
    }

    @Test
    void realPlayerRoleRejectsAnInvalidStagedNamespaceWithoutPublishingHalfAnInstallation() throws Exception {
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = TestExpressionModules.create(java.util.Set.of("known"))) {
            var resources = new OwnerResourceFixtures();
            resources.attach(cap);
            var a = resources.target(1, module, fixture.model);
            var b = resources.target(2, module, fixture.model);
            cap.updateModelAndTexture(a.modelHash(), "variant0");
            DemandFixtures.advance(cap);
            var ready = DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable());
            var invalid = new it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap();
            invalid.put(com.elfmcys.ysm.util.StringPool.computeIfAbsent("unknown"), 4);
            cap.resetRoamingVars(b.modelHash().roamingHash(), invalid);
            cap.updateModelAndTexture(b.modelHash(), "variant0");
            DemandFixtures.advance(cap);
            assertEquals(b.modelHash(), cap.getModelHash());
            assertEquals(a.modelHash().roamingHash(), cap.localRoamingKey());
            var output = new Probe();
            assertTrue(cap.consumePrepared(ready,
                data -> NativeRenderer.render(output.consumer, new PoseStack().last(), data.modelState.getNativeState(),
                    data.modelState.getVertexCount(), 0, 0, -1, RenderContextType.LEVEL)));
            assertFalse(output.vertices.isEmpty());
            assertEquals(0, resources.leases.get(0).closes);
            assertEquals(1, resources.leases.get(1).closes);
            cap.updateModelAndTexture(a.modelHash(), "variant0");
            cap.reloadGeoModel();
            assertTrue(cap.isAssigned());
            cap.setDisabled(true);
            cap.setDisabled(false);
            DemandFixtures.advance(cap);
            assertEquals(a.modelHash().roamingHash(), cap.localRoamingKey());
            assertNotNull(DemandFixtures.produce(cap, 0.25f, RenderContext.levelImmutable()));
            cap.retire();
            assertOwnerReleased(cap);
        }
    }

    @Test
    void localSyncCommitsOnceAfterAnAcceptedPreviewAnimationRound(
        @org.junit.jupiter.api.io.TempDir Path cache) throws Exception {
        var definition = com.elfmcys.ysm.format.parser.AnimationBuilder
            .build(com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile.createGson(false).fromJson("""
                {"animations":{"sync_probe":{"animation_length":1,"timeline":{"0":"r.sync_count=0;ysm.sync(3);"}}}}
                """, com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile.class));
        var model = com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto.newBuilder()
            .putAnimationFiles("main", definition).build();
        var encoded = com.elfmcys.ysm.expression.EncodedProgramMapper.toProto(
            new com.elfmcys.ysm.expression.frontend.MolangFrontend().compile("r.sync_count=r.sync_count+1;",
                com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceMode.EXPRESSION,
                new com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceLocation("review/local-sync")).program());
        var strings = com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto.newBuilder()
            .addUserFunctions(com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto.newBuilder()
                .setName("@sync").setBody(encoded).build()).build();
        var artifactStore = track(new com.elfmcys.ysm.expression.aot.ExpressionArtifactStore(cache,
            track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(cache))));
        var factory = new com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory(artifactStore,
            ExpressionHost.create(HostAvailability.none()), new com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics());
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = factory.create(() -> false,
                new com.elfmcys.ysm.model.domain.ModelFileIdentity(OwnerResourceFixtures.hash(1),
                    OwnerResourceFixtures.hash(255)),
                "player", "", OwnerResourceFixtures.PROFILE, false, model, strings,
                com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto.newBuilder().build(), new byte[0])) {
            artifactStore.awaitPersistence();
            var binder = new com.elfmcys.ysm.model.resource.client.render.AnimationProtoMapper.ProgramBinder() {
                private com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation
                    location(String path, com.elfmcys.ysm.expression.inventory.ProgramRole role, String discriminator) {
                    return new com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation("player",
                        path, role, discriminator);
                }

                public com.elfmcys.ysm.expression.aot.FloatExpression.Program bindFloat(String path,
                    com.elfmcys.ysm.expression.inventory.ProgramRole role, String discriminator) {
                    return module.requireFloatProgram(location(path, role, discriminator));
                }

                public com.elfmcys.ysm.expression.aot.BooleanProgram bindBoolean(String path,
                    com.elfmcys.ysm.expression.inventory.ProgramRole role, String discriminator) {
                    return module.requireBooleanProgram(location(path, role, discriminator));
                }

                public com.elfmcys.ysm.expression.aot.VoidProgram bindVoid(String path,
                    com.elfmcys.ysm.expression.inventory.ProgramRole role, String discriminator) {
                    return module.requireVoidProgram(location(path, role, discriminator));
                }
            };
            var owner = new CustomPlayerEntity(player, true, false) {};
            var resources = new OwnerResourceFixtures();
            resources.attach(owner);
            var target = resources.target(1, module, fixture.model);
            target.assets().eventHandlers().put("sync", List.of(module.requireEventProgram(
                new com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation("player",
                    "function/@sync", com.elfmcys.ysm.expression.inventory.ProgramRole.EVENT_SYNC, "sync:0"))));
            target.playerResources().animations().put("sync_probe",
                com.elfmcys.ysm.model.resource.client.render.AnimationProtoMapper
                    .animation(definition.animations().get(0), "animation/main", binder));
            try {
                owner.updateModelAndTexture(target.modelHash(), "variant0");
                DemandFixtures.advance(owner);
                owner.playExtraAnimation("sync_probe");
                assertNotNull(DemandFixtures.produce(owner, 0.25f, RenderContext.levelImmutable()));
                assertEquals(1f, owner.getExpressionRuntime().takeRoamingFull().values()
                    .get("sync_count"));
            } finally {
                owner.retire();
            }
        }
    }

    @Test
    void realDomainEffectsCommitInOrderAfterHooksAndDiscardEveryPrecommitFailure(
        @org.junit.jupiter.api.io.TempDir Path cache) throws Exception {
        var definition = com.elfmcys.ysm.format.parser.AnimationBuilder
            .build(com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile.createGson(false).fromJson("""
                {"animations":{
                  "joint":{"animation_length":1,"blend_weight":"7","timeline":{"0":
                    "ysm.play_sound('voice','sound',0,0.5,1.25);ctrl.set_animation('walk',10);ysm.sync(3);"}},
                  "failure":{"animation_length":1,"timeline":{"0":"loop(1000000,0);"}}
                }}
                """, com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile.class));
        var model = com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto.newBuilder()
            .putAnimationFiles("main", definition).build();
        var encoded = com.elfmcys.ysm.expression.EncodedProgramMapper.toProto(
            new com.elfmcys.ysm.expression.frontend.MolangFrontend()
                .compile("ysm.play_sound('sync','sync',0,args[0],1);",
                    com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceMode.EXPRESSION,
                    new com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceLocation("joint/sync")).program());
        var strings = com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto.newBuilder()
            .addUserFunctions(com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto.newBuilder()
                .setName("@sync").setBody(encoded).build()).build();
        var store = track(new com.elfmcys.ysm.expression.aot.ExpressionArtifactStore(cache,
            track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(cache))));
        var factory = new com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory(store,
            ExpressionHost.create(HostAvailability.none()), new com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics());
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = factory.create(() -> false,
                new com.elfmcys.ysm.model.domain.ModelFileIdentity(OwnerResourceFixtures.hash(1),
                    OwnerResourceFixtures.hash(255)),
                "player", "", OwnerResourceFixtures.PROFILE, false, model, strings,
                com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto.newBuilder().build(), new byte[0])) {
            store.awaitPersistence();
            var action = module.requireVoidProgram(jointLocation("animation/main/joint/instruction/0",
                com.elfmcys.ysm.expression.inventory.ProgramRole.ANIMATION_INSTRUCTION, ""));
            var laterFailure = module.requireVoidProgram(jointLocation("animation/main/failure/instruction/0",
                com.elfmcys.ysm.expression.inventory.ProgramRole.ANIMATION_INSTRUCTION, ""));
            var readonly = module.requireFloatProgram(jointLocation("animation/main/joint/blend-weight",
                com.elfmcys.ysm.expression.inventory.ProgramRole.ANIMATION_SAMPLE, ""));
            var sync = module.requireEventProgram(jointLocation("function/@sync",
                com.elfmcys.ysm.expression.inventory.ProgramRole.EVENT_SYNC, "sync:0"));
            for (var failure : List.of("none", "expression", "begin", "end", "validation", "cancel", "retire")) {
                var trace = new ArrayList<String>();
                var payloads = new ArrayList<String>();
                CustomPlayerEntity owner = new CustomPlayerEntity(player, true, false) {
                    public java.util.Optional<com.elfmcys.ysm.client.sound.instance.AudioPlaybackFactory>
                        getSoundPlayback(String name) {
                        return java.util.Optional.of(control -> {
                            trace.add(name);
                            var captured = control.snapshot(false);
                            payloads.add(name + ":" + captured.authorVolume() + ":" + captured.pitch());
                            return new com.elfmcys.ysm.client.sound.instance.ICanStopSound() {
                                boolean stopped;
                                public void setStopped() { stopped = true; }
                                public boolean isPlaybackStopped() { return stopped; }
                            };
                        });
                    }
                };
                var resources = new OwnerResourceFixtures();
                resources.attach(owner);
                var target = resources.target(1, module, fixture.model);
                target.assets().eventHandlers().put("sync", List.of(sync));
                try {
                    owner.updateModelAndTexture(target.modelHash(), "variant0");
                    DemandFixtures.advance(owner);
                    owner.checkModelUpdate();
                    var runtime = owner.getExpressionRuntime();
                    var known = new IllegalStateException(failure);
                    var hooks = new JointRoundHooks(module.prepareComponents(owner, null), trace, failure, known);
                    hooks.setRenderTarget(target, () -> true);
                    runtime.replace(module, java.util.Map.of("sync", List.of(sync)), hooks);
                    var controller = new com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController<>(owner,
                        "first", 0, event -> com.elfmcys.ysm.geckolib3.core.PlayState.CONTINUE) {
                        public void setAnimation(String name,
                            com.elfmcys.ysm.geckolib3.core.builder.LoopType loop) {
                            trace.add("controller");
                            payloads.add("controller:" + name + ":" + loop);
                        }
                    };
                    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
                    var event = new AnimationEvent<AnimatableEntity<net.minecraft.world.entity.player.Player>>(
                        owner, player.tickCount, .25f, .25f,
                        RenderContext.levelImmutable(), new com.elfmcys.ysm.geckolib3.model.provider.data.EntityModelData(),
                        null);
                    var production = owner.getAnimationProcessor().invocationInputs(event).productionInputs();
                    var inputs = new com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.InvocationInputs(production,
                        (effects, provenance) -> {
                            assertTrue(hooks.ended, "all necessary hooks precede sink preparation");
                            assertTrue(trace.isEmpty(), "preparation has no visible domain effects");
                            if (failure.equals("validation"))
                                throw known;
                            return () -> effects.forEach(com.elfmcys.ysm.api.molang.PreparedEffect::commit);
                        }, cancelled::get);
                    org.junit.jupiter.api.function.Executable round = () -> owner.inCall(() -> runtime.update(inputs,
                        evaluation -> {
                            evaluation.setControllerContext(new com.elfmcys.ysm.expression.host.ControllerExecutionState(
                                false, controller));
                            evaluation.setAnimationContext(new com.elfmcys.ysm.expression.host.AnimationExecutionState());
                            evaluation.execute(action);
                            assertTrue(trace.isEmpty());
                            assertThrows(IllegalStateException.class,
                                () -> owner.dispatchSync(com.elfmcys.ysm.expression.host.EventArguments.sync(99)));
                            evaluation.setControllerContext(null);
                            if (failure.equals("expression")) {
                                evaluation.execute(laterFailure);
                                assertTrue(evaluation.failure().isPresent());
                            }
                            assertEquals(7f, evaluation.evaluate(readonly));
                            if (failure.equals("cancel"))
                                cancelled.set(true);
                            if (failure.equals("retire"))
                                runtime.invalidate();
                            return null;
                        }));
                    if (List.of("begin", "end", "validation").contains(failure))
                        assertSame(known, assertThrows(IllegalStateException.class, round));
                    else if (failure.equals("cancel") || failure.equals("retire"))
                        assertThrows(RuntimeException.class, round);
                    else
                        assertDoesNotThrow(round);
                    assertEquals(failure.equals("none") ? List.of("sound", "controller", "sync") : List.of(),
                        trace, failure);
                    assertEquals(failure.equals("none")
                        ? List.of("sound:0.5:1.25", "controller:walk:LOOP", "sync:3.0:1.0") : List.of(),
                        payloads, failure);
                } finally {
                    owner.retire();
                }
            }
        }
    }

    private static com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation
        jointLocation(String path, com.elfmcys.ysm.expression.inventory.ProgramRole role, String discriminator) {
        return new com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation("player", path, role,
            discriminator);
    }

    private static final class JointRoundHooks implements com.elfmcys.ysm.expression.host.ComponentScope {
        private final com.elfmcys.ysm.expression.host.ComponentScope actual;
        private final List<String> trace;
        private final String failure;
        private final RuntimeException known;
        boolean outer = true;
        boolean ended;
        JointRoundHooks(com.elfmcys.ysm.expression.host.ComponentScope actual, List<String> trace, String failure,
            RuntimeException known) {
            this.actual = actual; this.trace = trace; this.failure = failure; this.known = known;
        }
        public AnimatableEntity<?> owner() { return actual.owner(); }
        public Entity subject() { return actual.subject(); }
        public void setRenderTarget(ModelRenderTarget target, java.util.function.BooleanSupplier current) {
            actual.setRenderTarget(target, current);
        }
        public void evaluate(AnimationEvent<?> event, java.util.function.BooleanSupplier current, Runnable body) {
            boolean first = outer;
            outer = false;
            if (first && failure.equals("begin")) throw known;
            actual.evaluate(event, current, body);
            if (first) {
                assertTrue(trace.isEmpty(), "necessary end still precedes every domain handoff");
                if (failure.equals("end")) throw known;
                ended = true;
            }
        }
        public void tick(int previous, int next, java.util.function.BooleanSupplier current) {
            actual.tick(previous, next, current);
        }
        public void update(float previous, float next, float partial, java.util.function.BooleanSupplier current) {
            actual.update(previous, next, partial, current);
        }
        public void invalidate() { actual.invalidate(); }
        public <T> java.util.Optional<T> exact(Class<T> type) { return actual.exact(type); }
        public dev.semaweave.ProviderInstanceTable providers(ExpressionHost host,
            dev.semaweave.compilation.BindingPlan plan) { return actual.providers(host, plan); }
    }

    @Test
    void realPlayerTimelineKeepsOrderedEventsAcrossObservationRateLimitAndSelfSuppression(
        @org.junit.jupiter.api.io.TempDir Path cache) throws Exception {
        var definition = com.elfmcys.ysm.format.parser.AnimationBuilder
            .build(com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile.createGson(false).fromJson("""
                {"animations":{"trace":{"animation_length":1,"timeline":{
                  "0.05":"r.trace=r.trace*10+1;",
                  "0.10":"r.trace=r.trace*10+2;",
                  "0.15":"r.trace=r.trace*10+3;",
                  "0.20":"r.trace=r.trace*10+4;"}}}}
                """, com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile.class));
        var model = com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto.newBuilder()
            .putAnimationFiles("main", definition).build();
        var host = ExpressionHost.create(HostAvailability.none());
        var artifactStore = track(new com.elfmcys.ysm.expression.aot.ExpressionArtifactStore(cache,
            track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(cache))));
        var factory = new com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory(artifactStore, host,
            new com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics());
        try (var fixture = new Fixture("parts-a", com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            var module = factory.create(() -> false,
                new com.elfmcys.ysm.model.domain.ModelFileIdentity(OwnerResourceFixtures.hash(1),
                    OwnerResourceFixtures.hash(255)),
                "player", "", OwnerResourceFixtures.PROFILE, false, model, null,
                com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto.newBuilder().build(), new byte[0])) {
            // The persistence worker must release this fixture before JUnit removes its directory.
            artifactStore.awaitPersistence();
            var binder = new com.elfmcys.ysm.model.resource.client.render.AnimationProtoMapper.ProgramBinder() {
                private com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation
                    location(String path, com.elfmcys.ysm.expression.inventory.ProgramRole role, String discriminator) {
                    return new com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation("player",
                        path, role, discriminator);
                }

                public com.elfmcys.ysm.expression.aot.FloatExpression.Program bindFloat(String path,
                    com.elfmcys.ysm.expression.inventory.ProgramRole role, String discriminator) {
                    return module.requireFloatProgram(location(path, role, discriminator));
                }

                public com.elfmcys.ysm.expression.aot.BooleanProgram bindBoolean(String path,
                    com.elfmcys.ysm.expression.inventory.ProgramRole role, String discriminator) {
                    return module.requireBooleanProgram(location(path, role, discriminator));
                }

                public com.elfmcys.ysm.expression.aot.VoidProgram bindVoid(String path,
                    com.elfmcys.ysm.expression.inventory.ProgramRole role, String discriminator) {
                    return module.requireVoidProgram(location(path, role, discriminator));
                }
            };
            var animation = com.elfmcys.ysm.model.resource.client.render.AnimationProtoMapper
                .animation(definition.animations().get(0), "animation/main", binder);
            cap.retire();
            var expected = List.of(0f, 0f, 12f, 1234f, 1234f);
            assertEquals(expected, playerTimeline(false, module, fixture.model, animation));
            assertEquals(expected, playerTimeline(true, module, fixture.model, animation));
            assertEquals(expected, playerTimeline(false, module, fixture.model, animation, 0));
            assertEquals(expected, playerTimeline(false, module, fixture.model, animation, 1, true));
        }
    }

    private List<Float> playerTimeline(boolean hidden,
        com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule module, GeoModel geometry, Animation animation)
        throws Exception {
        return playerTimeline(hidden, module, geometry, animation, 1);
    }

    private List<Float> playerTimeline(boolean hidden,
        com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule module, GeoModel geometry, Animation animation,
        int modelMarker) throws Exception {
        return playerTimeline(hidden, module, geometry, animation, modelMarker, false);
    }

    private List<Float> playerTimeline(boolean hidden,
        com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule module, GeoModel geometry, Animation animation,
        int modelMarker, boolean switchVariant) throws Exception {
        ClientConfig.DISABLE_SELF_MODEL.set(hidden);
        player.tickCount = 0;
        var owner = new PlayerAnimatableCapability(player);
        var resources = new OwnerResourceFixtures();
        resources.attach(owner);
        var target = resources.target(modelMarker, module, geometry, geometry);
        target.playerResources().animations().put("trace", animation);
        owner.updateModelAndTexture(modelMarker == 0 ? null : target.modelHash(), "variant0");
        DemandFixtures.advance(owner);
        owner.playExtraAnimation("trace");
        var trace = new ArrayList<Float>();
        try {
            for (float time : new float[] {0, 0.01f, 2.5f, 4.5f, 3.5f}) {
                player.tickCount = (int)time;
                var partial = time - player.tickCount;
                com.elfmcys.ysm.client.animation.AnimationParallelTicker.scheduleAll(partial);
                com.elfmcys.ysm.client.animation.AnimationParallelTicker.waitAll();
                var value = owner.roamingSnapshot(target.modelHash().roamingHash()).values()
                    .get(com.elfmcys.ysm.util.StringPool.computeIfAbsent("trace"));
                trace.add(value);
                var data = DemandFixtures.produce(owner, partial, RenderContext.levelImmutable());
                DemandFixtures.produce(owner, partial, RenderContext.levelImmutable().withImmutable(false));
                if (data != null) {
                    damagePose(data.modelState.getNativeState());
                    DemandFixtures.produce(owner, partial, RenderContext.levelImmutable());
                }
                assertEquals(value, owner.roamingSnapshot(target.modelHash().roamingHash()).values()
                    .get(com.elfmcys.ysm.util.StringPool.computeIfAbsent("trace")));
                assertEquals(hidden, !owner.isPresentationAllowed());
                if (switchVariant && time == 2.5f) {
                    owner.updateTextureName("variant1");
                    DemandFixtures.advance(owner);
                }
            }
            assertEquals(switchVariant ? 2 : 1, resources.acquisitions);
            return trace;
        } finally {
            owner.retire();
            ClientConfig.DISABLE_SELF_MODEL.set(false);
        }
    }

    private static void damagePose(NativeModelState state) throws Exception {
        var data = (java.nio.ByteBuffer)field(com.elfmcys.ysm.natives.render.BonePoseView.class, "data")
            .get(state.getBonePoses());
        int bone = Short.toUnsignedInt(state.getRenderBoneIndices().get(0));
        int poseStride = field(com.elfmcys.ysm.natives.render.BonePoseView.class, "STRIDE").getInt(null);
        data.putFloat(bone * poseStride, Float.POSITIVE_INFINITY);
    }

    TestArm install(Fixture fixture) throws Exception {
        var owner = new TestArm(player, cap);
        owner.install(fixture.model);
        field(CustomFirstPersonArmRenderer.class, "armEntity").set(renderer, owner);
        return owner;
    }

    private void renderArm(RenderArmEvent event) throws Exception {
        invokeAdapter(ReplacePlayerHandRenderEvent.class, RenderArmEvent.class, event);
    }

    private void renderBackground(RenderHandEvent event) throws Exception {
        invokeAdapter(RenderFirstPlayerBackground.class, RenderHandEvent.class, event);
    }

    private void invokeAdapter(Class<?> type, Class<?> eventType, Object event) throws Exception {
        var method = type.getDeclaredMethod("render", eventType, LocalPlayer.class, PlayerAnimatableCapability.class);
        method.setAccessible(true);
        try {
            method.invoke(null, event, player, cap);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException original)
                throw original;
            if (failure.getCause() instanceof Error original)
                throw original;
            throw failure;
        }
    }

    private static RenderArmEvent armEvent(HumanoidArm arm, PoseStack pose, Probe output) throws Exception {
        var constructor = RenderArmEvent.class.getConstructors()[0];
        var arguments = new Object[constructor.getParameterCount()];
        var types = constructor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == HumanoidArm.class)
                arguments[i] = arm;
            else if (types[i] == PoseStack.class)
                arguments[i] = pose;
            else if (types[i] == MultiBufferSource.class)
                arguments[i] = output;
            else if (types[i] == int.class)
                arguments[i] = 123;
            else if (types[i].isInstance(Minecraft.getInstance().player))
                arguments[i] = Minecraft.getInstance().player;
            else
                throw new AssertionError(types[i]);
        }
        return (RenderArmEvent)constructor.newInstance(arguments);
    }

    private static RenderHandEvent handEvent(PoseStack pose, Probe output) throws Exception {
        var constructor = RenderHandEvent.class.getConstructors()[0];
        var arguments = new Object[constructor.getParameterCount()];
        var types = constructor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == InteractionHand.class)
                arguments[i] = InteractionHand.MAIN_HAND;
            else if (types[i] == PoseStack.class)
                arguments[i] = pose;
            else if (types[i] == MultiBufferSource.class)
                arguments[i] = output;
            else if (types[i] == int.class)
                arguments[i] = 123;
            else if (types[i] == float.class)
                arguments[i] = 0.25f;
            else if (types[i] == ItemStack.class)
                arguments[i] = ItemStack.EMPTY;
            else
                throw new AssertionError(types[i]);
        }
        return (RenderHandEvent)constructor.newInstance(arguments);
    }

    private static ModelRenderTarget target(String name) throws Exception {
        var target = allocate(ModelRenderTarget.class);
        field(ModelRenderTarget.class, "textureId").set(target, new ResourceLocation("ysm", name));
        return target;
    }

    static Field field(Class<?> type, String name) throws Exception {
        var result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    static <T> T allocate(Class<T> type) throws InstantiationException {
        return type.cast(UnsafeUtil.getUnsafe().allocateInstance(type));
    }

    private static int marker(float[] vertex) {
        return (int)Math.floor(vertex[3] * 8 + 0.000001f) + 8 * (int)Math.floor(vertex[4] * 8 + 0.000001f);
    }

    static void assertMarkers(Probe probe, int... allowed) {
        int visible = 0;
        for (var vertex : probe.vertices) {
            if ((int)vertex[5] == 0)
                continue;
            int marker = marker(vertex);
            boolean found = false;
            for (int value : allowed)
                found |= marker == value;
            assertTrue(found, "unexpected marker " + marker);
            visible++;
        }
        assertTrue(visible > 0);
    }

    static void assertVertices(Probe expected, Probe actual) {
        assertEquals(expected.vertices.size(), actual.vertices.size());
        for (int i = 0; i < expected.vertices.size(); i++)
            assertArrayEquals(expected.vertices.get(i), actual.vertices.get(i), 0.00002f);
    }

    private static final class TestLocator extends GeoLocatorType {
        TestLocator() {
            super(new ResourceLocation("ysm", "t2_fixture"));
            register("LeftArm");
            register("RightArm");
            register("Background");
        }
    }

    static final class FakePlayer extends LocalPlayer {
        private FakePlayer() {
            super(null, null, null, null, null, false, false);
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public String getModelName() {
            return "default";
        }

        @Override
        public ResourceLocation getSkinTextureLocation() {
            return new ResourceLocation("ysm", "host_skin");
        }

        @Override
        public boolean isUsingItem() {
            return false;
        }

        @Override
        public boolean isSleeping() {
            return false;
        }

        @Override
        public net.minecraft.world.entity.Pose getPose() {
            return net.minecraft.world.entity.Pose.STANDING;
        }

        @Override
        public HumanoidArm getMainArm() {
            return HumanoidArm.RIGHT;
        }

        @Override
        public ItemStack getItemBySlot(net.minecraft.world.entity.EquipmentSlot slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public net.minecraft.world.entity.Entity getFirstPassenger() {
            return null;
        }
    }

    static final class FakeRemotePlayer extends net.minecraft.client.player.RemotePlayer {
        PlayerAnimatableCapability owner;

        private FakeRemotePlayer() {
            super(null, null);
        }

        @Override
        public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
            net.minecraftforge.common.capabilities.Capability<T> capability, net.minecraft.core.Direction side) {
            return net.minecraftforge.common.util.LazyOptional.of(() -> owner).cast();
        }
    }

    static final class FakeClientLevel extends net.minecraft.client.multiplayer.ClientLevel {
        net.minecraft.world.entity.Entity remote;

        private FakeClientLevel() {
            super(null, null, null, null, 0, 0, () -> null, null, false, 0);
        }

        @Override
        public net.minecraft.world.entity.Entity getEntity(int id) {
            return id == 42 ? remote : null;
        }
    }

    static final class FakeArrow extends net.minecraft.world.entity.projectile.Arrow {
        private FakeArrow() {
            super(net.minecraft.world.entity.EntityType.ARROW, null);
        }

        @Override
        public net.minecraft.world.entity.EntityType<?> getType() {
            return net.minecraft.world.entity.EntityType.ARROW;
        }
    }

    static final class FakeBoat extends net.minecraft.world.entity.vehicle.Boat {
        private FakeBoat() {
            super(net.minecraft.world.entity.EntityType.BOAT, null);
        }

        @Override
        public net.minecraft.world.entity.EntityType<?> getType() {
            return net.minecraft.world.entity.EntityType.BOAT;
        }
    }

    static final class AcceptedPlayback implements com.elfmcys.ysm.client.sound.instance.ICanStopSound {
        boolean stopped;

        @Override
        public void setStopped() {
            stopped = true;
        }

        @Override
        public boolean isPlaybackStopped() {
            return stopped;
        }
    }

    static final class ProbePlayerRenderer extends com.elfmcys.ysm.client.renderer.CustomPlayerRenderer {
        private ProbePlayerRenderer() {
            super(null);
        }

        @Override
        protected void renderPrepared(CustomPlayerEntity owner, com.elfmcys.ysm.geckolib3.geo.GeoRenderData data,
            ResourceLocation texture, float yaw, float partial, PoseStack pose, MultiBufferSource output, int light) {
            render(data, owner, RenderType.lines(), pose, output, light, 0,
                com.elfmcys.ysm.geckolib3.core.util.Color.WHITE);
        }
    }

    static final class TestArm extends CustomFirstPersonArmEntity {
        int updates;
        int modelIndex;
        final OwnerResourceFixtures resources = new OwnerResourceFixtures();
        final List<com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule> modules = new ArrayList<>();

        TestArm(LocalPlayer player, PlayerAnimatableCapability cap) throws Exception {
            super(player, cap);
            resources.attach(this);
        }

        void install(GeoModel model) throws Exception {
            var module = TestExpressionModules.create();
            modules.add(module);
            var target = resources.target(++modelIndex, module, model);
            getMainModelEntity().updateModelAndTexture(target.modelHash(), "variant0");
            DemandFixtures.advance(this);
            assertSame(target, getModelRenderTarget());
        }

        @Override
        protected void dispatchPersistentEvents(
            com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.Evaluation evaluation, boolean shouldUpdate) {
            updates++;
        }

        @Override
        public com.elfmcys.ysm.client.animation.debug.DebugSource getDebugSource() {
            return null;
        }

        @Override
        public int getFrameRateLimit() {
            return 60;
        }
    }

    static class Timeline extends AnimatableEntity<LocalPlayer> {
        com.elfmcys.ysm.model.resource.client.AnimationStore readBatchStore;

        @Override
        protected com.elfmcys.ysm.model.resource.client.AnimationStore animationSource() {
            return readBatchStore;
        }

        int normalUpdates;
        int actions;
        ResourceLocation texture = new ResourceLocation("ysm", "timeline");
        boolean corruptAttributes;
        boolean permitted = true;
        boolean producer;
        Runnable controllers = () -> {
        };
        Runnable beforeAnimation = () -> {
        };
        RenderContext lastContext;
        final OwnerResourceFixtures resources = new OwnerResourceFixtures();

        Timeline(LocalPlayer player) throws Exception {
            super(player, false);
            resources.attach(this);
        }

        @Override
        protected void prepareAnimationControllers(ModelRenderTarget target,
            com.elfmcys.ysm.geckolib3.core.manager.AnimationData animations) {
            controllers.run();
        }

        @Override
        protected String fallbackRenderTargetId() {
            return null;
        }

        @Override
        protected boolean presentationPermission() {
            return permitted;
        }

        @Override
        protected boolean isProducer() {
            return producer;
        }

        void restrict(boolean permitted) {
            this.permitted = permitted;
            restrictionsChanged();
        }

        void select(int marker) {
            updateModelHash(OwnerResourceFixtures.hash(marker));
        }

        @Override
        protected GeoModel resolveGeoModel(ModelRenderTarget target, String texture) {
            return target.playerResources().defaultVariant().mainModel();
        }

        @Override
        public com.elfmcys.ysm.client.animation.debug.DebugSource getDebugSource() {
            return null;
        }

        void install(GeoModel model, com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule module)
            throws Exception {
            var target = resources.target(1, module, model);
            updateModelHash(target.modelHash());
            DemandFixtures.advance(this);
            assertSame(target, getModelRenderTarget());
        }

        boolean advance(float partialTick) {
            DemandFixtures.advance(this);
            return updateAnimationOnly(partialTick, RenderContext.levelImmutable());
        }

        com.elfmcys.ysm.geckolib3.geo.GeoRenderData produce(float partialTick, RenderContext context) {
            return DemandFixtures.produce(this, partialTick, context);
        }

        @Override
        protected void dispatchPersistentEvents(
            com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.Evaluation evaluation, boolean shouldUpdate) {
            beforeAnimation.run();
            normalUpdates++;
            actions++;
        }

        @Override
        protected void tickAnimation(AnimationEvent<AnimatableEntity<LocalPlayer>> event) {
            lastContext = event.getRenderContext();
            super.tickAnimation(event);
        }

        @Override
        protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<LocalPlayer>> event,
            boolean shouldUpdate) {
            if (corruptAttributes)
                getLoadedGeoModel().getBoneAttributes()[12] = -1;
        }

        @Override
        public int getFrameRateLimit() {
            return 60;
        }

        @Override
        public ResourceLocation getTextureLocation() {
            return texture;
        }

        @Override
        public float getWidthScale() {
            return 1;
        }

        @Override
        public float getHeightScale() {
            return 1;
        }

        @Override
        public Animation getAnimation(String name) {
            return readBatchStore == null ? null : readAnimation(readBatchStore, name);
        }
    }

    private static final class RealArm extends CustomFirstPersonArmEntity {
        ResourceLocation texture = new ResourceLocation("ysm", "real_arm");
        int normalUpdates;
        int actions;
        boolean corruptAttributes;
        final OwnerResourceFixtures resources = new OwnerResourceFixtures();

        RealArm(LocalPlayer player, PlayerAnimatableCapability capability) throws Exception {
            super(player, capability);
            resources.attach(this);
        }

        void install(GeoModel model, com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule module,
            GeoModel... alternatives) throws Exception {
            var models = new GeoModel[alternatives.length + 1];
            models[0] = model;
            System.arraycopy(alternatives, 0, models, 1, alternatives.length);
            var target = resources.target(1, module, models);
            getMainModelEntity().updateModelAndTexture(target.modelHash(), "variant0");
            DemandFixtures.advance(this);
            assertSame(target, getModelRenderTarget());
        }

        void replace(GeoModel model) {
            var target = getModelRenderTarget();
            assertSame(model, target.playerResources().variants().get("variant1").armModel());
            getMainModelEntity().updateTextureName("variant1");
            DemandFixtures.advance(this);
        }

        FirstPersonModelState state() throws Exception {
            return (FirstPersonModelState)field(CustomFirstPersonArmEntity.class, "firstPersonState").get(this);
        }

        @Override
        public com.elfmcys.ysm.client.animation.debug.DebugSource getDebugSource() {
            return null;
        }

        @Override
        public ResourceLocation getTextureLocation() {
            return texture;
        }

        @Override
        public int getFrameRateLimit() {
            return 60;
        }

        @Override
        protected void dispatchPersistentEvents(
            com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.Evaluation evaluation, boolean shouldUpdate) {
            normalUpdates++;
            actions++;
        }

        @Override
        public float getWidthScale() {
            return 1;
        }

        @Override
        public float getHeightScale() {
            return 1;
        }

        @Override
        public Animation getAnimation(String name) {
            return null;
        }

        @Override
        protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<LocalPlayer>> event,
            boolean shouldUpdate) {
            for (var bone : getLoadedGeoModel().getSortedBones()) {
                if (bone.getName().equals("LeftArm") || bone.getName().equals("RightArm")) {
                    bone.setPosition(event.getRequestedPartialTick() * 16, 0, 0);
                    bone.setColor(255, 255, 255);
                    if (corruptAttributes && bone.getName().equals("LeftArm")) {
                        getLoadedGeoModel()
                            .getBoneAttributes()[getLoadedGeoModel().getSortedBones().indexOf(bone) * STRIDE + 12] = -1;
                    }
                } else if (bone.getName().equals("Background")) {
                    bone.setPosition(160, 320, 480);
                    bone.setScale(2, 3, 4);
                    bone.setColor(17, 34, 51);
                    bone.setTransparency(31);
                    bone.setGlow(15);
                }
            }
        }
    }

    static final class Fixture implements AutoCloseable {
        final GeoModel model;

        Fixture(String name) throws Exception {
            this(name, locator);
        }

        Fixture(String name, GeoLocatorType locatorType) throws Exception {
            this(name, locatorType, false);
        }

        Fixture(String name, GeoLocatorType locatorType, boolean head, String... glowBones) throws Exception {
            var bytes = Files.readAllBytes(FIXTURES.resolve(name + ".pb"));
            var source = com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto.parseFrom(bytes);
            if (head) {
                var builder = source.toBuilder().clearBones();
                for (var bone : source.bones())
                    builder.addBones(bone.name().equals("Unrelated") ? bone.toBuilder().setName("Head").build() : bone);
                source = builder.build();
                bytes = com.elfmcys.ysm.util.ProtoUtil.serializeToArray(source);
            }
            if (glowBones.length != 0) {
                var names = java.util.Set.of(glowBones);
                var builder = source.toBuilder().clearBones();
                for (var bone : source.bones()) {
                    var renamed = bone.toBuilder();
                    if (names.contains(bone.name()))
                        renamed.setName("ysmGlow" + bone.name());
                    if (names.contains(bone.parent().orElse("")))
                        renamed.setParent("ysmGlow" + bone.parent().orElseThrow());
                    builder.addBones(renamed.build());
                }
                source = builder.build();
                bytes = com.elfmcys.ysm.util.ProtoUtil.serializeToArray(source);
            }
            var image = ImageIO.read(FIXTURES.resolve("markers-a.png").toFile());
            try (var input = ArrayBuffer.move(bytes);
                var texture = NativeBuffer.allocate(image.getWidth() * image.getHeight() * 4)) {
                var pixels = texture.nio();
                for (int y = 0; y < image.getHeight(); y++)
                    for (int x = 0; x < image.getWidth(); x++) {
                        int color = image.getRGB(x, y);
                        pixels.put((byte)(color >> 16)).put((byte)(color >> 8)).put((byte)color)
                            .put((byte)(color >> 24));
                    }
                var baked = NativeBakedModel.bake(input, source.bones().size(), texture, image.getWidth(),
                    image.getHeight(), 29, false);
                model = new GeoModel(name, source, locatorType, baked);
            }
        }

        @Override
        public void close() {
            model.close();
        }
    }

    static final class Probe implements MultiBufferSource {
        final List<float[]> vertices = new ArrayList<>();
        final List<RenderType> types = new ArrayList<>();
        RuntimeException failure;
        final VertexConsumer consumer = (VertexConsumer)Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
            new Class<?>[] {VertexConsumer.class}, (proxy, method, args) -> {
                if (!method.getName().equals("vertex"))
                    throw new AssertionError(method.getName());
                if (failure != null)
                    throw failure;
                assertEquals(14, args.length);
                vertices.add(new float[] {((Number)args[0]).floatValue(), ((Number)args[1]).floatValue(),
                    ((Number)args[2]).floatValue(), ((Number)args[7]).floatValue(), ((Number)args[8]).floatValue(),
                    ((Number)args[6]).floatValue(), ((Number)args[3]).floatValue(), ((Number)args[4]).floatValue(),
                    ((Number)args[5]).floatValue(), ((Number)args[9]).floatValue(), ((Number)args[10]).floatValue(),
                    ((Number)args[11]).floatValue(), ((Number)args[12]).floatValue(), ((Number)args[13]).floatValue()});
                return null;
            });

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            types.add(type);
            return consumer;
        }
    }
}
