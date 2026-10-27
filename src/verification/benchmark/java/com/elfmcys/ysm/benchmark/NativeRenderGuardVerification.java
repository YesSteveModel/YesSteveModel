package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.natives.render.BonePoseView;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import com.elfmcys.ysm.natives.render.RenderContextType;

import java.nio.ByteBuffer;

/** Exercises failure guards against the loaded JNI implementation, outside JMH timing. */
public final class NativeRenderGuardVerification {
    public static void main(String[] args) throws Exception {
        for (var path : new String[] {"direct", "fallback"}) {
            var workload = new NativeRenderBenchmark.Workload();
            workload.model = "body-4";
            workload.path = path;
            try {
                workload.setup();
                new NativeRenderBenchmark().repeatedDraw(workload);
                require(workload.state.isValid(), "successful repeated draw must preserve publication");

                var expectedCount = workload.state.getTotalVertexCount();
                var before = workload.sink.vertices;
                damagePose(workload.state);
                require(!NativeRenderer.render(workload.sink, workload.pose, workload.state, expectedCount, 0x00F000F0,
                    0, -1, RenderContextType.LEVEL), "real JNI failure must report rejection");
                require(expectedCount > 0 && workload.sink.vertices == before,
                    "real JNI failure must submit no vertices");
                require(!workload.state.isValid() && workload.state.getTotalVertexCount() == 0,
                    "real JNI failure must invalidate publication");

                workload.extract();
                before = workload.sink.vertices;
                damagePose(workload.state);
                expectRejected(workload::draw, "real nonempty JNI failure");
                require(workload.sink.vertices == before, "failed guard run must not commit output");

                workload.extract();
                workload.state.invalidate();
                expectRejected(() -> new NativeRenderBenchmark().repeatedDraw(workload),
                    "invalid repeated publication");
                for (int i = 0; i < workload.geometry.sortedBones().size(); i++) {
                    workload.attributes[i * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT + 9] = 1;
                }
                workload.extract();
                require(workload.state.isValid() && workload.state.getTotalVertexCount() == 0,
                    "valid empty publication");
                workload.draw();
                workload.draw();

                // Leave Java's publication intact so the zero-output JNI failure reaches draw.
                var invalidate = NativeModelState.class.getDeclaredMethod("nInvalidate", long.class);
                invalidate.setAccessible(true);
                invalidate.invoke(null, workload.state.get());
                require(workload.state.isValid(), "Java publication must still be valid before native rejection");
                expectRejected(workload::draw, "real empty JNI failure");
                require(!workload.state.isValid(), "native rejection must clear Java publication");
                System.out.println("GUARD_PASS path=" + path);
            } finally {
                workload.close();
            }
        }
    }

    private static void damagePose(NativeModelState state) throws Exception {
        var field = BonePoseView.class.getDeclaredField("data");
        field.setAccessible(true);
        ((ByteBuffer)field.get(state.getBonePoses())).putFloat(0, Float.POSITIVE_INFINITY);
    }

    private static void expectRejected(Runnable operation, String scenario) {
        try {
            operation.run();
        } catch (IllegalStateException rejected) {
            require(
                rejected.getMessage().equals("invalid publication")
                    || rejected.getMessage().equals("render failed or incomplete output"),
                "failure must come from the benchmark guard");
            return;
        }
        throw new AssertionError("guard accepted " + scenario);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
