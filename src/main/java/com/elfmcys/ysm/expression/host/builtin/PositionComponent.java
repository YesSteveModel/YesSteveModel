package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Position history that survives target replacement for one animatable owner. */
@BuiltinComponent(value = "query")
public final class PositionComponent implements EntityComponent<Entity> {
    private final AnimatableEntity<Entity> owner;
    private final Entity subject;
    private Vec3 position;
    private Vec3 delta = Vec3.ZERO;
    private float renderTickDelta;
    private float partialTick;
    private float sampleTime;
    private AnimationEvent<?> event;
    private Vec3 observedPosition;

    public PositionComponent(AnimatableEntity<Entity> owner) {
        this.owner = owner;
        this.subject = owner.getEntity();
    }

    public void copyHistoryFrom(PositionComponent previous) {
        if (owner != previous.owner || subject != previous.subject)
            throw new IllegalArgumentException("Position history belongs to another owner or subject");
        position = previous.position;
        delta = previous.delta;
        renderTickDelta = previous.renderTickDelta;
        partialTick = previous.partialTick;
        sampleTime = previous.sampleTime;
    }

    @Override
    public void beginEval(AnimationEvent<?> event) {
        // Calculate before retaining the borrow so a failed begin cannot leak its event.
        var current = interpolated(event.getPartialTick());
        this.event = event;
        observedPosition = current;
    }

    @Override
    public void endEval() {
        observedPosition = null;
        event = null;
    }

    @Override
    public void update(float previousTime, float currentTime, float partialTicks) {
        // A new holder's zero-span initialization preserves explicitly inherited healthy history.
        if (position != null && previousTime == currentTime)
            return;
        var current = interpolated(partialTicks);
        renderTickDelta = position == null ? 0 : Math.max(0, currentTime - sampleTime);
        delta = position == null || renderTickDelta == 0 ? Vec3.ZERO : current.subtract(position);
        position = current;
        sampleTime = currentTime;
        partialTick = partialTicks;
    }

    private Vec3 interpolated(float partialTicks) {
        return new Vec3(Mth.lerp(partialTicks, subject.xo, subject.getX()),
            Mth.lerp(partialTicks, subject.yo, subject.getY()), Mth.lerp(partialTicks, subject.zo, subject.getZ()));
    }

    @BuiltinFunction(value = "position", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 1,
        maxArgs = 1)
    public Float position(MolangContextView<Entity> context, float axis) {
        return positionValue((int)axis, false);
    }

    public Float positionValue(int index, boolean positionDelta) {
        if (index < 0 || index > 2) {
            return null;
        }
        if (positionDelta) {
            return (float)component(delta, index);
        }
        var value = observedPosition != null ? observedPosition : position;
        if (value == null) {
            value = subject.position();
        }
        return (float)component(value, index);
    }

    @BuiltinFunction(value = "position_delta", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 1,
        maxArgs = 1)
    public float positionDelta(MolangContextView<Entity> context, float axis) {
        int index = (int)axis;
        return index < 0 || index > 2 ? Float.NaN : (float)component(delta, index);
    }

    @BuiltinFunction(value = "ground_speed2", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public float trackedGroundSpeed(MolangContextView<Entity> context) {
        return trackedGroundSpeed();
    }

    public float trackedGroundSpeed() {
        return renderTickDelta == 0 ? 0
            : 20 * Mth.sqrt((float)(delta.x * delta.x + delta.z * delta.z)) / renderTickDelta;
    }

    @BuiltinFunction(value = "vertical_speed", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public float verticalSpeed(MolangContextView<Entity> context) {
        return verticalSpeed();
    }

    public float verticalSpeed() {
        return renderTickDelta == 0 ? 0 : 20 * (float)delta.y / renderTickDelta;
    }

    @BuiltinFunction(value = "input_horizontal", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public float inputHorizontal(MolangContextView<Entity> context) {
        return relativeMoveInput(false, context.inputs().partialTick());
    }

    public float inputHorizontal() {
        return relativeMoveInput(false, partialTick());
    }

    @BuiltinFunction(value = "input_vertical", namespace = "ysm",
        effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public float inputVertical(MolangContextView<Entity> context) {
        return relativeMoveInput(true, context.inputs().partialTick());
    }

    public float inputVertical() {
        return relativeMoveInput(true, partialTick());
    }

    public Vec3 delta() {
        return delta;
    }

    public float renderTickDelta() {
        return renderTickDelta;
    }

    public float partialTick() {
        return event == null ? partialTick : event.getPartialTick();
    }

    private float relativeMoveInput(boolean vertical, float partialTick) {
        if (Math.sqrt(delta.x * delta.x + delta.z * delta.z) < 1.0E-4) {
            return 0;
        }
        var moveAngle =
            com.elfmcys.ysm.geckolib3.core.util.MathUtil.radiansToDegrees((float)Mth.atan2(delta.z, delta.x));
        var entityYaw = 90 - Mth.wrapDegrees(-subject.getViewYRot(partialTick));
        var relative = Mth.wrapDegrees(moveAngle - entityYaw);
        return vertical ? Mth.cos(com.elfmcys.ysm.geckolib3.core.util.MathUtil.degreesToRadians(relative))
            : Mth.sin(com.elfmcys.ysm.geckolib3.core.util.MathUtil.degreesToRadians(relative));
    }

    private static double component(Vec3 value, int axis) {
        return axis == 0 ? value.x : axis == 1 ? value.y : value.z;
    }
}
