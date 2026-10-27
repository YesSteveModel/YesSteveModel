package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import com.elfmcys.ysm.natives.render.RenderContextType;
import com.mojang.blaze3d.vertex.PoseStack;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/** Final-only measurements of three native outputs; this is not an animation owner benchmark. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class NativePartBenchmark {
    @State(Scope.Thread)
    public static class Parts {
        @Param({"direct", "fallback"})
        public String path;
        GeoModel geometry;
        float[] arms, background;
        final NativeModelState[] outputs = new NativeModelState[3];
        final int[] roots = new int[3];
        NativeRenderBenchmark.Sink sink;
        PoseStack.Pose pose;
        int frame;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            NativeRenderBenchmark.initializeNative();
            geometry = NativeRenderBenchmark.geometry("parts-a");
            arms = new AnimatedGeoModel(geometry).getBoneAttributes();
            background = arms.clone();
            for (int i = 0; i < geometry.sortedBones().size(); i++) {
                switch (geometry.sortedBones().get(i).name()) {
                    case "LeftArm" -> roots[0] = i;
                    case "RightArm" -> roots[1] = i;
                    case "Background" -> roots[2] = i;
                }
            }
            sink = new NativeRenderBenchmark.Sink(path.equals("direct"), 8192);
            pose = new PoseStack().last();
            for (int i = 0; i < 3; i++)
                outputs[i] = NativeModelState.create();
            extract();
            alternating();
        }

        void extract() {
            for (int i = 0; i < 3; i++) {
                if (!outputs[i].extractSubtree(geometry.bakedModel(), i == 2 ? background : arms, roots[i]))
                    throw new IllegalStateException("part extract failed");
            }
        }

        void draw(int part) {
            var output = outputs[part];
            if (!NativeRenderer.render(sink, pose, output, output.getTotalVertexCount(), 0x00F000F0, 0, -1,
                RenderContextType.LEVEL))
                throw new IllegalStateException("part render failed");
        }

        void alternating() {
            draw(0);
            draw(1);
            draw(2);
            draw(0);
            draw(1);
        }

        void nextPose() {
            frame++;
            for (int i = 0; i < roots.length - 1; i++)
                arms[roots[i] * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT + 3] = (frame & 7) * 0.02f;
        }

        @TearDown(Level.Trial)
        public void close() {
            for (var output : outputs)
                if (output != null)
                    output.close();
            if (sink != null)
                sink.close();
            if (geometry != null)
                geometry.close();
        }
    }

    @Benchmark
    public long repeated(Parts state) {
        state.draw(0);
        state.draw(0);
        return state.sink.vertices;
    }

    @Benchmark
    public long alternating(Parts state) {
        state.alternating();
        return state.sink.vertices;
    }

    @Benchmark
    public long nextPoseStable(Parts state) {
        state.nextPose();
        state.extract();
        state.alternating();
        return state.sink.vertices;
    }

    @Benchmark
    public long visibilityChanged(Parts state) {
        state.nextPose();
        state.arms[state.roots[0] * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT + 9] = state.frame & 1;
        state.extract();
        state.alternating();
        return state.sink.vertices;
    }
}
