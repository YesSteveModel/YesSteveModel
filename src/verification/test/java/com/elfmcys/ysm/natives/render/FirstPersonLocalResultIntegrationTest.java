package com.elfmcys.ysm.natives.render;

import com.elfmcys.ysm.buffer.NativeStorage;
import com.elfmcys.ysm.accessor.VertexBufferAccessor;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.imageio.ImageIO;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@EnabledIfEnvironmentVariable(named = "YSM_FIRST_PERSON_FIXTURES", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class FirstPersonLocalResultIntegrationTest {
    private static final Path FIXTURES = Path.of(System.getenv("YSM_FIRST_PERSON_FIXTURES"));
    private static final int STRIDE = AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;
    private static final ForgeConfigSpec.BooleanValue ORIGINAL_COMPATIBILITY = ClientConfig.USE_COMPATIBILITY_RENDERER;
    private static Thread originalRenderThread;

    @BeforeAll
    static void initializeRenderThread() throws Exception {
        var field = com.mojang.blaze3d.systems.RenderSystem.class.getDeclaredField("renderThread");
        field.setAccessible(true);
        originalRenderThread = (Thread)field.get(null);
        if (originalRenderThread == null) {
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        } else {
            field.set(null, Thread.currentThread());
        }
    }

    @AfterAll
    static void restoreConfig() throws Exception {
        ClientConfig.USE_COMPATIBILITY_RENDERER = ORIGINAL_COMPATIBILITY;
        var field = com.mojang.blaze3d.systems.RenderSystem.class.getDeclaredField("renderThread");
        field.setAccessible(true);
        field.set(null, originalRenderThread);
    }

    @Test
    void localAndFullPosesMatchFrozenIndependentOracle() throws Exception {
        var scenarios = json("pose-oracles.json").getAsJsonObject("scenarios");
        try (var fixture = new Fixture("parts-a"); var state = NativeModelState.create()) {
            for (var entry : scenarios.entrySet()) {
                var scenario = entry.getValue().getAsJsonObject();
                var attributes = fixture.attributes(scenario.getAsJsonObject("attributes"));
                for (var part : List.of("LeftArm", "RightArm", "Background", "body")) {
                    assertTrue(part.equals("body") ? state.extract(fixture.model, attributes)
                        : state.extractSubtree(fixture.model, attributes, fixture.index(part)), entry.getKey());
                    var expected = scenario.getAsJsonObject("expected").getAsJsonObject(part);
                    var expectedIndices = new ArrayList<Integer>();
                    for (var cube : expected.getAsJsonArray("visible_cubes")) {
                        var id = cube.getAsString();
                        var name = id.equals("sibling") ? "Unrelated"
                            : id.substring(0, id.indexOf(':')) + (id.endsWith("child") ? "Child1" : "");
                        var index = fixture.index(name);
                        if (!expectedIndices.contains(index))
                            expectedIndices.add(index);
                    }
                    assertEquals(expectedIndices, indices(state), entry.getKey() + "/" + part);
                    var vertexCount = 0;
                    var translucentCount = 0;
                    var info = fixture.model.getBoneInfo(0, fixture.names.size());
                    for (var index : expectedIndices) {
                        var bone = info[index];
                        vertexCount += bone.cutout().vertexCountAfterCulling() + bone.cutoutNoCulling().vertexCount()
                            + bone.translucent().vertexCount() + bone.translucentCulling().vertexCountAfterCulling();
                        translucentCount +=
                            bone.translucent().vertexCount() + bone.translucentCulling().vertexCountAfterCulling();
                    }
                    assertEquals(vertexCount, state.getTotalVertexCount());
                    assertEquals(translucentCount, state.getTranslucentVertexCount());
                    for (var poseEntry : expected.getAsJsonObject("poses").entrySet()) {
                        var rows = poseEntry.getValue().getAsJsonArray();
                        var independent = new Matrix4f();
                        var index = fixture.index(poseEntry.getKey());
                        var actual = state.getBonePoses().getPose(index, new Matrix4f());
                        for (var row = 0; row < 4; row++) {
                            for (var column = 0; column < 4; column++) {
                                var value = rows.get(row).getAsJsonArray().get(column).getAsFloat();
                                independent.set(column, row, value);
                                assertEquals(value, actual.get(column, row), 0.00001f,
                                    entry.getKey() + "/" + part + "/" + poseEntry.getKey());
                            }
                        }
                        var normal = new Matrix3f(independent).invert().transpose();
                        var actualNormal = state.getBonePoses().getNormal(index, new Matrix3f());
                        // Compact uniform normals omit a common positive scale; directions must agree.
                        for (var column = 0; column < 3; column++) {
                            var direction = normal.getColumn(column, new org.joml.Vector3f()).normalize();
                            var observed = actualNormal.getColumn(column, new org.joml.Vector3f()).normalize();
                            assertEquals(direction.x, observed.x, 0.00002f);
                            assertEquals(direction.y, observed.y, 0.00002f);
                            assertEquals(direction.z, observed.z, 0.00002f);
                        }
                    }
                }
            }
        }
    }

    @Test
    void failedExtractReplacementAndExplicitInvalidationDoNotPublishOldViews() throws Exception {
        try (var a = new Fixture("parts-a"); var b = new Fixture("parts-b"); var state = NativeModelState.create()) {
            assertTrue(state.extractSubtree(a.model, a.attributes(new JsonObject()), a.index("LeftArm")));
            for (var root : new int[] {-1, a.names.size(), Integer.MAX_VALUE}) {
                assertFalse(state.extractSubtree(a.model, a.attributes(new JsonObject()), root));
                assertCleared(state);
                assertTrue(state.extractSubtree(a.model, a.attributes(new JsonObject()), a.index("RightArm")));
                assertEquals(List.of(a.index("RightArm"), a.index("RightArmChild1")), indices(state));
            }
            assertFalse(state.extractSubtree(a.model, new float[1], a.index("LeftArm")));
            assertCleared(state);
            var invalid = a.attributes(new JsonObject());
            invalid[a.index("LeftArm") * STRIDE + 12] = -1;
            assertFalse(state.extractSubtree(a.model, invalid, a.index("LeftArm")));
            assertCleared(state);
            assertTrue(state.extractSubtree(b.model, b.attributes(new JsonObject()), b.index("LeftArm")));
            try (var output = new Probe(false, 0)) {
                assertTrue(draw(state, output));
                assertTrue(output.vertices.stream().anyMatch(vertex -> Math.abs(vertex[2] - 0.3f) < 0.00002f),
                    "replacement must render B's declared depth, not A's 0.25 depth");
            }
            state.invalidate();
            assertCleared(state);
            assertTrue(state.extractSubtree(b.model, b.attributes(new JsonObject()), b.index("RightArm")));
            assertThrows(NullPointerException.class, () -> state.extractSubtree(b.model, null, 0));
            assertCleared(state);
        }
    }

    @Test
    void realJniPostwriteFailureRevokesNativeConsumption() throws Exception {
        var entry = NativeModelState.class.getDeclaredMethod("nExtractSubtree", long.class, long.class, float[].class,
            short[].class, long[].class, int.class);
        entry.setAccessible(true);
        try (var fixture = new Fixture("parts-a"); var state = NativeModelState.create();
            var direct = new Probe(true, 8192)) {
            configureDirect();
            assertTrue(
                state.extractSubtree(fixture.model, fixture.attributes(new JsonObject()), fixture.index("LeftArm")));
            // A short output carrier fails after native extraction and locator publication.
            assertEquals(false,
                entry.invoke(null, state.get(), fixture.model.get(), fixture.attributes(new JsonObject()),
                    new short[fixture.names.size()], new long[3], fixture.index("RightArm")));
            assertFalse(draw(state, direct));
            assertEquals(0, direct.advanced);
            assertEquals(0, direct.vertices.size());
            assertCleared(state);
            var hidden = fixture.attributes(new JsonObject());
            hidden[fixture.index("LeftArm") * STRIDE + 9] = 1;
            hidden[fixture.index("LeftArmChild1") * STRIDE + 9] = 1;
            assertTrue(state.extractSubtree(fixture.model, hidden, fixture.index("LeftArm")));
            assertEquals(0, state.getTotalVertexCount());
            assertEquals(false, entry.invoke(null, state.get(), fixture.model.get(), hidden,
                new short[fixture.names.size()], new long[3], fixture.index("LeftArm")));
            assertFalse(draw(state, direct), "zero Java count cannot hide a failed native publication");
            assertCleared(state);
        }
    }

    @Test
    void directFallbackNativeFailureEmptyAndConsumerExceptionUseRealOutput() throws Exception {
        configureDirect();
        try (var fixture = new Fixture("parts-a"); var state = NativeModelState.create();
            var direct = new Probe(true, 8192); var fallback = new Probe(false, 0)) {
            var attributes = fixture.attributes(new JsonObject());
            assertTrue(state.extractSubtree(fixture.model, attributes, fixture.index("LeftArm")));
            var count = state.getTotalVertexCount();
            assertTrue(draw(state, direct));
            assertEquals(count, direct.advanced);
            assertTrue(draw(state, fallback));
            assertEquals(count, fallback.vertices.size());
            for (var i = 0; i < count; i++) {
                var bytes = direct.region.nio().order(ByteOrder.nativeOrder());
                var vertex = fallback.vertices.get(i);
                var offset = i * DefaultVertexFormat.NEW_ENTITY.getVertexSize();
                for (var axis = 0; axis < 3; axis++)
                    assertEquals(bytes.getFloat(offset + axis * 4), vertex[axis], 0.00002f);
                assertEquals(bytes.getFloat(offset + 16), vertex[3], 0.00002f);
                assertEquals(bytes.getFloat(offset + 20), vertex[4], 0.00002f);
                for (var axis = 0; axis < 3; axis++)
                    assertEquals(bytes.get(offset + 32 + axis) / 127f, vertex[5 + axis], 0.00002f);
            }
            var known = new IllegalStateException("known consumer replay failure");
            fallback.failure = known;
            assertSame(known, assertThrows(IllegalStateException.class, () -> draw(state, fallback)));
            fallback.failure = null;

            var dataField = BonePoseView.class.getDeclaredField("data");
            dataField.setAccessible(true);
            var data = (ByteBuffer)dataField.get(state.getBonePoses());
            var poseOffset = fixture.index("LeftArm") * BonePoseView.STRIDE;
            var original = data.getFloat(poseOffset);
            data.putFloat(poseOffset, Float.POSITIVE_INFINITY);
            var advanced = direct.advanced;
            assertFalse(draw(state, direct));
            assertEquals(advanced, direct.advanced);
            assertCleared(state);
            assertTrue(state.extractSubtree(fixture.model, attributes, fixture.index("LeftArm")));
            data = (ByteBuffer)dataField.get(state.getBonePoses());
            data.putFloat(poseOffset, Float.POSITIVE_INFINITY);
            var replayed = fallback.vertices.size();
            assertFalse(draw(state, fallback));
            assertEquals(replayed, fallback.vertices.size());
            assertCleared(state);
            data.putFloat(poseOffset, original);

            assertTrue(state.extractSubtree(fixture.model, attributes, fixture.index("RightArm")));
            var reserved = direct.reserved;
            assertFalse(NativeRenderer.render(direct.consumer, new PoseStack().last(), state,
                state.getTotalVertexCount() - 1, 0, 0, -1, RenderContextType.LEVEL));
            assertEquals(reserved, direct.reserved);
            assertCleared(state);
            try (var tooSmall = new Probe(true, 1)) {
                assertTrue(state.extractSubtree(fixture.model, attributes, fixture.index("RightArm")));
                assertFalse(draw(state, tooSmall));
                assertEquals(1, tooSmall.reserved);
                assertEquals(0, tooSmall.advanced);
                assertTrue(state.isValid());
                assertTrue(draw(state, direct));
            }
            reserved = direct.reserved;
            attributes[fixture.index("LeftArm") * STRIDE + 9] = 1;
            attributes[fixture.index("LeftArmChild1") * STRIDE + 9] = 1;
            assertTrue(state.extractSubtree(fixture.model, attributes, fixture.index("LeftArm")));
            assertEquals(0, state.getTotalVertexCount());
            assertTrue(draw(state, direct));
            assertEquals(reserved, direct.reserved);
        }
    }

    @Test
    void hostTransformOverflowPreservesPublicationForLegalRetry() throws Exception {
        configureDirect();
        try (var fixture = new Fixture("parts-a"); var state = NativeModelState.create();
            var direct = new Probe(true, 8192); var fallback = new Probe(false, 0)) {
            var attributes = fixture.attributes(new JsonObject());
            attributes[fixture.index("LeftArm") * STRIDE + 6] = 4;
            for (var fullModel : List.of(false, true)) {
                assertTrue(fullModel ? state.extract(fixture.model, attributes)
                    : state.extractSubtree(fixture.model, attributes, fixture.index("LeftArm")));
                var published = state.getBonePoses();
                var count = state.getTotalVertexCount();
                assertTrue(draw(state, direct));
                var advanced = direct.advanced;
                var host = new PoseStack();
                host.last().pose().m00(Float.MAX_VALUE / 2);
                host.last().normal().m00(1 / (Float.MAX_VALUE / 2));
                // The published pose remains finite; only this host composition overflows.
                assertFalse(NativeRenderer.render(direct.consumer, host.last(), state, count, 0x00F000F0, 0, -1,
                    RenderContextType.LEVEL));
                assertEquals(advanced, direct.advanced);
                assertTrue(state.isValid(), "C-04: output-only overflow must retain the published pose");
                assertSame(published, state.getBonePoses());
                var replayed = fallback.vertices.size();
                assertFalse(NativeRenderer.render(fallback.consumer, host.last(), state, count, 0x00F000F0, 0, -1,
                    RenderContextType.LEVEL));
                assertEquals(replayed, fallback.vertices.size());
                assertTrue(state.isValid());
                assertSame(published, state.getBonePoses());
                assertTrue(draw(state, direct));
                assertEquals(advanced + count, direct.advanced);
                assertTrue(draw(state, fallback));
                assertEquals(replayed + count, fallback.vertices.size());
            }
        }
    }

    private static void configureDirect() {
        var builder = new ForgeConfigSpec.Builder();
        ClientConfig.USE_COMPATIBILITY_RENDERER = builder.define("compatibility", false);
        builder.build().setConfig(CommentedConfig.inMemory());
    }

    @Test
    void supportedSurfacesHaveDeclaredCornersNormalsAndTransparentTailOnBothPaths() throws Exception {
        configureDirect();
        try (var fixture = new Fixture("surfaces-supported"); var state = NativeModelState.create();
            var direct = new Probe(true, 8192); var fallback = new Probe(false, 0)) {
            var attributes = fixture.attributes(new JsonObject());
            attributes[6] = 2;
            attributes[7] = 3;
            attributes[8] = 4;
            assertTrue(state.extractSubtree(fixture.model, attributes, 0));
            assertTrue(state.getTranslucentVertexCount() > 0);
            assertTrue(draw(state, direct));
            assertTrue(draw(state, fallback));
            var declaration = json("surfaces-supported.json").getAsJsonArray("bones").get(0).getAsJsonObject()
                .getAsJsonArray("cubes");
            var counts = new int[40];
            var pose = new Matrix4f().translation(0, 1, 0).scale(2, 3, 4).translate(0, -1, 0);
            var bytes = direct.region.nio().order(ByteOrder.nativeOrder());
            var outsideVertex = false;
            for (var i = 0; i < state.getTotalVertexCount(); i++) {
                var offset = i * DefaultVertexFormat.NEW_ENTITY.getVertexSize();
                var observed = fallback.vertices.get(i);
                for (var axis = 0; axis < 3; axis++)
                    assertEquals(bytes.getFloat(offset + axis * 4), observed[axis], 0.00002f);
                assertEquals(bytes.getFloat(offset + 16), observed[3], 0.00002f);
                assertEquals(bytes.getFloat(offset + 20), observed[4], 0.00002f);
                for (var axis = 0; axis < 3; axis++)
                    assertEquals(bytes.get(offset + 32 + axis) / 127f, observed[5 + axis], 0.00002f);
                if (bytes.getInt(offset + 12) == 0)
                    continue;
                var marker =
                    (int)Math.floor(observed[3] * 8 + 0.000001f) + 8 * (int)Math.floor(observed[4] * 8 + 0.000001f);
                counts[marker]++;
                JsonObject primitive = null;
                for (var candidate : declaration)
                    if (candidate.getAsJsonObject().get("marker").getAsInt() == marker)
                        primitive = candidate.getAsJsonObject();
                assertNotNull(primitive, "unexpected UV marker " + marker);
                var matchesCorner = false;
                for (var corner = 0; corner < 8; corner++) {
                    var point = new org.joml.Vector3f();
                    for (var axis = 0; axis < 3; axis++)
                        point.setComponent(axis, primitive.getAsJsonArray("origin").get(axis).getAsFloat()
                            + ((corner >> axis) & 1) * primitive.getAsJsonArray("size").get(axis).getAsFloat());
                    pose.transformPosition(point);
                    matchesCorner |= Math.abs(point.x - observed[0]) < 0.00002f
                        && Math.abs(point.y - observed[1]) < 0.00002f && Math.abs(point.z - observed[2]) < 0.00002f;
                }
                assertTrue(matchesCorner, "position differs from independently transformed declaration");
                assertEquals(1, Math.abs(observed[5]) + Math.abs(observed[6]) + Math.abs(observed[7]), 0.00002f);
                if (marker == 38 && observed[0] > 1)
                    outsideVertex = true;
            }
            // Identity projection sees one face of each complete culling shell; double-sided faces remain.
            assertEquals(4, counts[0]);
            assertEquals(4, counts[1]);
            assertEquals(4, counts[2]);
            assertEquals(24, counts[37]);
            assertEquals(24, counts[38]);
            assertTrue(outsideVertex);
            assertEquals(state.getTotalVertexCount(), direct.advanced);
            assertEquals(state.getTotalVertexCount(), fallback.vertices.size());
        }
    }

    private static boolean draw(NativeModelState state, Probe probe) {
        return NativeRenderer.render(probe.consumer, new PoseStack().last(), state, state.getTotalVertexCount(),
            0x00F000F0, 0, -1, RenderContextType.LEVEL);
    }

    private static void assertCleared(NativeModelState state) {
        assertFalse(state.isValid());
        assertNull(state.getBonePoses());
        assertNull(state.getRenderBoneIndices());
        assertEquals(0, state.getTotalVertexCount());
        assertEquals(0, state.getTranslucentVertexCount());
        assertTrue(state.getLocatorBoneIndices().isEmpty());
    }

    private static List<Integer> indices(NativeModelState state) {
        var indices = state.getRenderBoneIndices();
        var result = new ArrayList<Integer>();
        for (var i = 0; i < indices.capacity(); i++)
            result.add(Short.toUnsignedInt(indices.get(i)));
        return result;
    }

    private static JsonObject json(String name) throws Exception {
        return JsonParser.parseString(Files.readString(FIXTURES.resolve(name))).getAsJsonObject();
    }

    private static final class Fixture implements AutoCloseable {
        final NativeBakedModel model;
        final List<String> names = new ArrayList<>();
        final float[] initial;

        Fixture(String name) throws Exception {
            var source = GeoModelProto.parseFrom(Files.readAllBytes(FIXTURES.resolve(name + ".pb")));
            var image = ImageIO.read(FIXTURES.resolve("markers-a.png").toFile());
            try (var input = ArrayBuffer.move(Files.readAllBytes(FIXTURES.resolve(name + ".pb")));
                var texture = NativeBuffer.allocate(image.getWidth() * image.getHeight() * 4)) {
                var pixels = texture.nio();
                for (var y = 0; y < image.getHeight(); y++)
                    for (var x = 0; x < image.getWidth(); x++) {
                        var color = image.getRGB(x, y);
                        pixels.put((byte)(color >> 16)).put((byte)(color >> 8)).put((byte)color)
                            .put((byte)(color >> 24));
                    }
                var baked = NativeBakedModel.bake(input, source.bones().size(), texture, image.getWidth(),
                    image.getHeight(), 29, false);
                model = baked.bakedModel();
                initial = new float[source.bones().size() * STRIDE];
                for (var index = 0; index < source.bones().size(); index++) {
                    var bone = source.bones().get(Short.toUnsignedInt(baked.sortedBoneIndices()[index]));
                    names.add(bone.name());
                    for (var axis = 0; axis < 3; axis++)
                        initial[index * STRIDE + axis] = bone.rotate().get(axis);
                    initial[index * STRIDE + 6] = initial[index * STRIDE + 7] = initial[index * STRIDE + 8] = 1;
                    initial[index * STRIDE + 12] = 0xFFFFFF;
                    initial[index * STRIDE + 13] = 0xFFFF;
                }
            }
        }

        int index(String name) {
            return names.indexOf(name);
        }

        float[] attributes(JsonObject changes) {
            var result = initial.clone();
            for (var entry : changes.entrySet()) {
                var offset = index(entry.getKey()) * STRIDE;
                for (var attribute : entry.getValue().getAsJsonObject().entrySet()) {
                    var channel = switch (attribute.getKey()) {
                        case "rotation" -> 0;
                        case "position" -> 3;
                        case "scale" -> 6;
                        case "cubes_hidden" -> 9;
                        case "children_hidden" -> 10;
                        default -> throw new AssertionError(attribute.getKey());
                    };
                    if (attribute.getValue().isJsonArray()) {
                        for (var axis = 0; axis < 3; axis++)
                            result[offset + channel + axis] =
                                attribute.getValue().getAsJsonArray().get(axis).getAsFloat();
                    } else
                        result[offset + channel] = attribute.getValue().getAsBoolean() ? 1 : 0;
                }
            }
            return result;
        }

        @Override
        public void close() {
            model.close();
        }
    }

    private static final class Probe implements AutoCloseable {
        final NativeStorage region;
        final VertexConsumer consumer;
        final List<float[]> vertices = new ArrayList<>();
        int advanced;
        int reserved;
        RuntimeException failure;

        Probe(boolean direct, int bytes) {
            region = direct ? NativeBuffer.allocate(bytes) : null;
            consumer = (VertexConsumer)Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                direct ? new Class<?>[] {VertexConsumer.class, VertexBufferAccessor.class}
                    : new Class<?>[] {VertexConsumer.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "ysm$ok" -> true;
                        case "ysm$vertexFormat" -> DefaultVertexFormat.NEW_ENTITY;
                        case "ysm$reserve" -> {
                            reserved++;
                            yield region.borrow();
                        }
                        case "ysm$advance" -> {
                            advanced += (int)args[0];
                            yield null;
                        }
                        case "vertex" -> {
                            if (failure != null)
                                throw failure;
                            assertEquals(14, args.length);
                            var data = new float[8];
                            var selected = new int[] {0, 1, 2, 7, 8, 11, 12, 13};
                            for (var i = 0; i < selected.length; i++)
                                data[i] = ((Number)args[selected[i]]).floatValue();
                            vertices.add(data);
                            yield null;
                        }
                        default -> throw new AssertionError(method.getName());
                    };
                });
        }

        @Override
        public void close() {
            if (region != null)
                region.close();
        }
    }
}
