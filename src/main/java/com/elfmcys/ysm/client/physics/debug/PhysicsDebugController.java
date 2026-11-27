package com.elfmcys.ysm.client.physics.debug;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.entity.PhysicsRenderPass;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.model.resource.client.PhysicsAssetMetadata;
import com.elfmcys.ysm.natives.physics.PhysicsDebugSnapshot;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/** Main-thread selection and a copied body sample shared by the world overlay and HUD. */
public final class PhysicsDebugController {
    private static final Selection ALL = new Selection(Kind.ALL, -1, -1);
    private static @Nullable AnimatableEntity<?> target;
    private static @Nullable Choice choice;
    private static Layer layer = Layer.ALL;
    private static @Nullable Captured captured;
    private static int page = 1;
    private static int pageCount = 1;

    private PhysicsDebugController() {}

    public enum Kind {
        ALL, NODE, PAIR, CONSTRAINT
    }

    public enum Layer {
        ALL, SHAPES, ANCHORS, AXES, LIMITS
    }

    public record Selection(Kind kind, int first, int second) {
    }

    public record View(String targetName, PhysicsAssetMetadata metadata, @Nullable PhysicsDebugSnapshot snapshot,
        @Nullable String failure, Selection selection, Layer layer, int page) {
    }

    public static boolean isTarget(AnimatableEntity<?> entity) {
        return target == entity;
    }

    public static @Nullable AnimatableEntity<?> target() {
        return target;
    }

    public static void enable(AnimatableEntity<?> entity) {
        if (target == entity) {
            resetPage();
            return;
        }
        disable();
        entity.setPhysicsDebugContactCapture(true);
        target = entity;
    }

    public static void disable() {
        var previous = target;
        if (previous != null)
            previous.setPhysicsDebugContactCapture(false);
        target = null;
        choice = null;
        captured = null;
        layer = Layer.ALL;
        resetPage();
    }

    public static void retire(AnimatableEntity<?> entity) {
        if (target == entity) {
            // Retirement already owns the world's close, including after a deferred draw-borrow exit.
            target = null;
            choice = null;
            captured = null;
            layer = Layer.ALL;
            resetPage();
        }
    }

    public static void beginWorldRender() {
        // A culled or vetoed body has no sample to share with the HUD this frame.
        captured = null;
    }

    public static @Nullable PhysicsAssetMetadata metadata() {
        var entity = target;
        if (entity == null)
            return null;
        entity.waitForAsyncUpdate();
        var model = entity.getModelRenderTarget();
        return model == null ? null : model.physicsMetadata();
    }

    public static boolean select(Kind kind, String first, @Nullable String second) {
        var metadata = metadata();
        var next = new Choice(kind, first, second);
        if (metadata == null || next.resolve(metadata) == ALL)
            return false;
        if (!next.equals(choice))
            resetPage();
        choice = next;
        return true;
    }

    public static void setLayer(Layer value) {
        layer = value;
    }

    public static int pageCount() {
        return pageCount;
    }

    public static void setPage(int requested) {
        page = Mth.clamp(requested, 1, pageCount);
    }

    public static void stepPage(int delta) {
        setPage(page + delta);
    }

    static void publishPageCount(int count) {
        pageCount = count;
        setPage(page);
    }

    private static void resetPage() {
        page = 1;
        pageCount = 1;
    }

    /** Captures only inside the accepted third-person body borrow. */
    public static @Nullable View capture(AnimatableEntity<?> entity, GeoRenderData data) {
        if (entity != target || PhysicsRenderPass.classify(data.ctx) != PhysicsRenderPass.CANONICAL)
            return null;
        if (!entity.visitPhysicsSample(data, (world, metadata) -> {
            if (captured != null && captured.matches(data.installation, data.physicsSampleEpoch))
                return;
            var bones = entity.getLoadedGeoModel().getModel().sortedBones();
            var sample = world.readDebugSnapshot(metadata.nodes().size(), metadata.constraints().size(),
                index -> bones.get(index).name(), index -> metadata.constraints().get(index).id());
            captured =
                sample == null ? null : new Captured(data.installation, data.physicsSampleEpoch, metadata, sample);
        })) {
            captured = null;
        }
        var accepted = captured;
        return accepted == null ? null : view(entity, accepted.metadata(), accepted.sample(), null);
    }

    public static @Nullable View hudView() {
        var entity = target;
        if (entity == null)
            return null;
        var status = entity.physicsDebugStatus();
        if (status == null) {
            captured = null;
            return null;
        }
        if (captured != null && !captured.matches(status.installation(), status.sampleEpoch()))
            captured = null;
        return view(entity, status.metadata(), captured == null ? null : captured.sample(), status.failure());
    }

    private static View view(AnimatableEntity<?> entity, PhysicsAssetMetadata metadata,
        @Nullable PhysicsDebugSnapshot snapshot, @Nullable String failure) {
        var selection = choice == null ? ALL : choice.resolve(metadata);
        return new View(entity.getEntity().getName().getString(), metadata, snapshot, failure, selection, layer, page);
    }

    private record Captured(Object installation, long epoch, PhysicsAssetMetadata metadata,
        PhysicsDebugSnapshot sample) {
        boolean matches(Object currentInstallation, long currentEpoch) {
            return installation == currentInstallation && epoch == currentEpoch;
        }
    }

    private record Choice(Kind kind, String first, @Nullable String second) {
        Selection resolve(PhysicsAssetMetadata metadata) {
            if (kind == Kind.CONSTRAINT) {
                for (int index = 0; index < metadata.constraints().size(); index++) {
                    if (metadata.constraints().get(index).id().equals(first))
                        return new Selection(kind, index, -1);
                }
                return ALL;
            }
            int a = nodeIndex(metadata, first);
            if (a < 0)
                return ALL;
            if (kind == Kind.NODE)
                return new Selection(kind, a, -1);
            int b = nodeIndex(metadata, second);
            return b < 0 ? ALL : new Selection(kind, a, b);
        }

        private static int nodeIndex(PhysicsAssetMetadata metadata, @Nullable String id) {
            for (int index = 0; index < metadata.nodes().size(); index++) {
                if (metadata.nodes().get(index).id().equals(id))
                    return index;
            }
            return -1;
        }
    }
}
