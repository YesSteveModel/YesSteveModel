package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.geckolib3.core.AnimationState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.Entity;
import org.apache.commons.lang3.StringUtils;

@BuiltinComponent("ctrl")
public final class ControllerActionBuiltins implements EntityComponent<Entity> {
    private static final int CTRL_STATE_CONTINUE_VALUE = 2;
    private static final int CTRL_STATE_STOP_VALUE = 3;
    private static final int CTRL_STATE_BYPASS_VALUE = 5;

    private final AnimatableEntity<?> owner;
    private AnimationEvent<?> event;

    public ControllerActionBuiltins(AnimatableEntity<Entity> owner) {
        this.owner = owner;
    }

    @Override
    public void beginEval(AnimationEvent<?> event) {
        this.event = event;
    }

    @Override
    public void endEval() {
        event = null;
    }

    @BuiltinFunction(value = "state_pause", alwaysOrigin = true)
    public static float statePause(MolangContextView<Entity> context) {
        return 4;
    }

    @BuiltinFunction(value = "loop", alwaysOrigin = true)
    public static float loop(MolangContextView<Entity> context) {
        return 10;
    }

    @BuiltinFunction(value = "play_once", alwaysOrigin = true)
    public static float playOnce(MolangContextView<Entity> context) {
        return 11;
    }

    @BuiltinFunction(value = "hold_on_last_frame", alwaysOrigin = true)
    public static float holdOnLastFrame(MolangContextView<Entity> context) {
        return 12;
    }

    @BuiltinFunction(value = "playing_extra_animation", alwaysOrigin = true)
    public boolean playingExtraAnimation(MolangContextView<Entity> context) {
        return owner instanceof CustomPlayerEntity p && p.isPlayingExtraAnimation() && p.getCodedAnimationStates(
            com.elfmcys.ysm.client.controller.collections.PlayerControllerCollection.CAP_CONTROLLER) != AnimationState.IDLE;
    }

    @BuiltinFunction(value = "state_bypass", alwaysOrigin = true)
    public static float stateBypass(MolangContextView<Entity> context) {

        return CTRL_STATE_BYPASS_VALUE;
    }

    @BuiltinFunction(value = "state_continue", alwaysOrigin = true)
    public static float stateContinue(MolangContextView<Entity> context) {

        return CTRL_STATE_CONTINUE_VALUE;
    }

    @BuiltinFunction(value = "state_stop", alwaysOrigin = true)
    public static float stateStop(MolangContextView<Entity> context) {

        return CTRL_STATE_STOP_VALUE;
    }

    @BuiltinFunction(value = "indicate_reload", alwaysOrigin = true, action = true)
    public void indicateReload(MolangContextView<Entity> context) {
        var target = controller();
        if (target != null)
            target.indicateReload();
    }

    @BuiltinFunction(value = "reset", alwaysOrigin = true, action = true)
    public void reset(MolangContextView<Entity> context) {
        var target = controller();
        if (target != null)
            target.reset();
    }

    @BuiltinFunction(value = "set_animation", alwaysOrigin = true, action = true)
    public void setAnimation(MolangContextView<Entity> context, int argc, String name,
        @com.elfmcys.ysm.api.annotation.DefaultValue("0") float loop) {
        var target = controller();
        var type = switch ((int)loop) {
            case 10 -> LoopType.LOOP;
            case 11 -> LoopType.PLAY_ONCE;
            case 12 -> LoopType.HOLD_ON_LAST_FRAME;
            default -> null;
        };
        if (target != null && StringUtils.isNotEmpty(name))
            target.setAnimation(name, type);
    }

    @BuiltinFunction(value = "set_beginning_transition_length", alwaysOrigin = true, action = true)
    public void setBeginningTransitionLength(MolangContextView<Entity> context, float length) {
        var target = controller();
        if (target != null && length >= 0)
            target.setBeginningTransitionLength(length);
    }

    private CodedAnimationController<?> controller() {
        return event == null ? null : event.getCodedController();
    }
}
