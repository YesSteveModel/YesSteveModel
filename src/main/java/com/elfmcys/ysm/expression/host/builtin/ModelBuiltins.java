package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.annotation.MolangNullable;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.entity.CustomFirstPersonArmEntity;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.expression.host.MolangVector;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.util.StringPool;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

@BuiltinComponent("ysm")
public final class ModelBuiltins implements EntityComponent<Entity> {
    private static final MolangVector ZERO = new MolangVector(0, 0, 0);

    private final AnimatableEntity<?> owner;
    private final Int2ReferenceOpenHashMap<MolangVector> absPivotCache = new Int2ReferenceOpenHashMap<>(0);

    public ModelBuiltins(AnimatableEntity<Entity> owner) {
        this.owner = owner;
    }

    private AnimationEvent<?> event;

    @Override
    public void beginEval(AnimationEvent<?> event) {
        this.event = event;
    }

    @Override
    public void update(float previousTime, float currentTime, float partialTicks) {
        absPivotCache.clear();
    }

    @Override
    public void setRenderTarget(ModelRenderTarget target) {
        absPivotCache.clear();
    }

    @Override
    public void endEval() {
        event = null;
    }

    @BuiltinFunction(value = "bone_pos", alwaysOrigin = true)
    @MolangNullable
    public MolangVector position(MolangContextView<Entity> context, String name) {
        var bone = owner.getBone(StringPool.computeIfAbsent(name));
        return bone == null ? null : new MolangVector(bone.getPositionX(), bone.getPositionY(), bone.getPositionZ());
    }

    @BuiltinFunction(value = "bone_scale", alwaysOrigin = true)
    @MolangNullable
    public MolangVector scale(MolangContextView<Entity> context, String name) {
        var bone = owner.getBone(StringPool.computeIfAbsent(name));
        return bone == null ? null : new MolangVector(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
    }

    @BuiltinFunction(value = "bone_rot", alwaysOrigin = true)
    public MolangVector boneRotation(MolangContextView<Entity> context, String name) {
        var bone = owner.getBone(StringPool.computeIfAbsent(name));
        return bone == null ? new MolangVector(0, 0, 0) : new MolangVector(-(float)Math.toDegrees(bone.getRotationX()),
            -(float)Math.toDegrees(bone.getRotationY()), (float)Math.toDegrees(bone.getRotationZ()));
    }

    @BuiltinFunction(value = "bone_pivot_abs", alwaysOrigin = true)
    @MolangNullable
    public MolangVector bonePivotAbsolute(MolangContextView<Entity> context, String name) {
        if (owner instanceof CustomFirstPersonArmEntity || event != null && event.getRenderContext().firstPersonMod()) {
            return null;
        }
        if (event == null) {
            return ZERO;
        }

        var model = owner.getLoadedGeoModel();
        var state = owner.getModelState();
        if (model == null || state == null) {
            return ZERO;
        }

        var boneName = StringPool.computeIfAbsent(name);
        var bone = model.getBoneMap().get(boneName);
        if (bone == null) {
            return ZERO;
        }

        return absPivotCache.computeIfAbsent(boneName, n -> {
            var pivot = new Vector3f(bone.getPivot()).mul(1f / 16f);
            state.getNativeState().getBonePoses().getPose(bone.getBoneIndex(), new Matrix4f()).transformPosition(pivot);
            if (pivot.isFinite()) {
                return new MolangVector(-pivot.x * 16f, pivot.y * 16f, pivot.z * 16f);
            } else {
                return ZERO;
            }
        });
    }

    @BuiltinFunction(value = "first_order", alwaysOrigin = true)
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public float firstOrder(MolangContextView<Entity> context, int argc, String name, float input,
        float... coefficients) {
        return physics(name, input, true, coefficients);
    }

    @BuiltinFunction(value = "second_order", alwaysOrigin = true)
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public float secondOrder(MolangContextView<Entity> context, int argc, String name, float input,
        float... coefficients) {
        return physics(name, input, false, coefficients);
    }

    private float physics(String name, float value, boolean first, float[] args) {
        if (name.isEmpty())
            return 0;
        int key = com.elfmcys.ysm.util.StringPool.computeIfAbsent(name);
        var manager = owner.getPhysicsManager(event);
        var current = manager.get(key);
        float a = args.length > 0 ? args[0] : 1, b = args.length > 1 ? args[1] : 1, c = args.length > 2 ? args[2] : 1;
        if (current == null) {
            current = first ? new com.elfmcys.ysm.client.animation.physics.FirstOrder(value, a)
                : new com.elfmcys.ysm.client.animation.physics.SecondOrder(value, a, b, c);
            manager.put(key, current);
            return value;
        }
        current.setArgs(value, a, b, c);
        return current.getValue();
    }
}
