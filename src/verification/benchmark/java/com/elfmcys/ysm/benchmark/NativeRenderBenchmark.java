package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.accessor.VertexBufferAccessor;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import com.elfmcys.ysm.natives.render.RenderContextType;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.resources.ResourceLocation;
import org.openjdk.jmh.annotations.*;

import javax.imageio.ImageIO;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class NativeRenderBenchmark {
    private static final GeoLocatorType LOCATOR = new GeoLocatorType(new ResourceLocation("ysm", "benchmark")) {};

    @State(Scope.Thread)
    public static class Workload {
        @Param({"body-4", "body-96"})
        public String model;
        @Param({"direct", "fallback"})
        public String path;
        GeoModel geometry;
        float[] attributes;
        NativeModelState state;
        Sink sink;
        PoseStack.Pose pose;
        int frame;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            initializeNative();
            geometry = geometry(model);
            attributes = new AnimatedGeoModel(geometry).getBoneAttributes();
            state = NativeModelState.create();
            sink = new Sink(path.equals("direct"), geometry.sortedBones().size() * 1024);
            pose = new PoseStack().last();
            extract();
            draw();
        }

        void extract() {
            if (!state.extract(geometry.bakedModel(), attributes))
                throw new IllegalStateException("extract failed");
        }

        void nextPose() {
            frame++;
            // A fixed eight-sample sequence changes pose without changing visible bone order.
            attributes[3] = (frame & 7) * 0.02f;
        }

        void draw() {
            if (!state.isValid())
                throw new IllegalStateException("invalid publication");
            var expectedCount = state.getTotalVertexCount();
            var before = sink.vertices;
            var rendered =
                NativeRenderer.render(sink, pose, state, expectedCount, 0x00F000F0, 0, -1, RenderContextType.LEVEL);
            // A failed render can clear publication counts, so the oracle must precede it.
            if (!rendered || !state.isValid() || sink.vertices - before != expectedCount)
                throw new IllegalStateException("render failed or incomplete output");
        }

        @TearDown(Level.Trial)
        public void close() {
            if (state != null)
                state.close();
            if (sink != null)
                sink.close();
            if (geometry != null)
                geometry.close();
        }
    }

    @Benchmark
    public int extract(Workload state) {
        state.nextPose();
        state.extract();
        return state.state.getTotalVertexCount();
    }

    @Benchmark
    public int extractAndDraw(Workload state) {
        state.nextPose();
        state.extract();
        state.draw();
        return state.state.getTotalVertexCount();
    }

    /** One operation consumes the same publication twice without another extraction. */
    @Benchmark
    public long repeatedDraw(Workload state) {
        state.draw();
        state.draw();
        return state.sink.vertices;
    }

    @Benchmark
    public int changingVisibility(Workload state) {
        state.nextPose();
        for (int i = 0; i < state.geometry.sortedBones().size(); i++) {
            state.attributes[i * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT + 9] = (i + state.frame) % 2;
        }
        state.extract();
        state.draw();
        return state.state.getTotalVertexCount();
    }

    @State(Scope.Thread)
    public static class EmptyWorkload extends Workload {
        @Override
        @Setup(Level.Trial)
        public void setup() throws Exception {
            super.setup();
            for (int i = 0; i < geometry.sortedBones().size(); i++)
                attributes[i * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT + 9] = 1;
            extract();
            if (state.getTotalVertexCount() != 0)
                throw new IllegalStateException("expected valid empty publication");
        }
    }

    @Benchmark
    public boolean emptyDraw(EmptyWorkload state) {
        state.draw();
        state.draw();
        return state.state.isValid();
    }

    static GeoModel geometry(String name) throws Exception {
        byte[] bytes;
        try (var input = NativeRenderBenchmark.class.getResourceAsStream("/workloads/" + name + ".pb")) {
            if (input == null)
                throw new IllegalArgumentException("unknown model " + name);
            bytes = input.readAllBytes();
        }
        var source = GeoModelProto.parseFrom(bytes);
        var image = ImageIO.read(NativeRenderBenchmark.class.getResourceAsStream("/workloads/markers-a.png"));
        try (var input = ArrayBuffer.move(bytes);
            var texture = NativeBuffer.allocate(image.getWidth() * image.getHeight() * 4)) {
            var pixels = texture.nio();
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) {
                    int color = image.getRGB(x, y);
                    pixels.put((byte)(color >> 16)).put((byte)(color >> 8)).put((byte)color).put((byte)(color >> 24));
                }
            var baked = NativeBakedModel.bake(input, source.bones().size(), texture, image.getWidth(),
                image.getHeight(), 29, false);
            return new GeoModel(name, source, LOCATOR, baked);
        }
    }

    private static boolean initialized;

    static synchronized void initializeNative() throws Exception {
        if (!initialized) {
            NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
            NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
            var config = new net.minecraftforge.common.ForgeConfigSpec.Builder();
            ClientConfig.USE_COMPATIBILITY_RENDERER = config.define("compatibilityRenderer", false);
            config.build().setConfig(com.electronwill.nightconfig.core.CommentedConfig.inMemory());
            initialized = true;
        }
        var renderThread = RenderSystem.class.getDeclaredField("renderThread");
        renderThread.setAccessible(true);
        renderThread.set(null, Thread.currentThread());
    }

    static final class Sink implements VertexConsumer, VertexBufferAccessor, AutoCloseable {
        final boolean direct;
        final NativeBuffer buffer;
        long vertices;
        float checksum;

        Sink(boolean direct, int capacity) {
            this.direct = direct;
            buffer = NativeBuffer.allocate(capacity, 64);
        }

        @Override
        public boolean ysm$ok() {
            return direct;
        }

        @Override
        public VertexFormat ysm$vertexFormat() {
            return DefaultVertexFormat.NEW_ENTITY;
        }

        @Override
        public NativeBuffer ysm$reserve(int count) {
            if ((long)count * 36 > buffer.size())
                throw new IllegalStateException("capacity");
            return buffer;
        }

        @Override
        public void ysm$advance(int count) {
            vertices += count;
            checksum = buffer.nio().getFloat(0);
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            checksum += (float)x;
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
            vertices++;
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {}

        @Override
        public void unsetDefaultColor() {}

        @Override
        public void vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v, int overlay,
            int light, float nx, float ny, float nz) {
            vertices++;
            checksum += x + y + z + u + v + nx + ny + nz;
        }

        @Override
        public void close() {
            buffer.close();
        }
    }
}
