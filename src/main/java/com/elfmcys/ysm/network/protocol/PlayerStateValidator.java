package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.proto.network.*;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** Validates packet shape. Selection owners decode roaming against their current dictionary. */
public final class PlayerStateValidator {
    private static final int MAX_TEXTURE_BYTES = 256;
    private static final int MAX_ANIMATION_BYTES = 256;
    private static final int MAX_EFFECTS = 64;
    private static final int MAX_EFFECT_ID_BYTES = 128;
    private static final int MAX_GAMEPLAY_VALUE = 1_000_000;

    private PlayerStateValidator() {}

    public static boolean validFlags(PlayerFlagsProto message) {
        return message.entityId() >= 0 && message.hasState();
    }

    public static boolean validSelectionState(SelectionStateProto state, boolean full) {
        return validSelectionStateShape(state, full) && (!state.hasRoaming() || validRoaming(state.roamingUnsafe()));
    }

    /** Leaves roaming validation to the decoder that has the current selection dictionary. */
    public static boolean validSelectionStateShape(SelectionStateProto state, boolean full) {
        if (full && (!state.hasTextureId() || !state.hasAnimation() || !state.hasRoaming()))
            return false;
        if (!full && !state.hasTextureId() && !state.hasAnimation() && !state.hasRoaming())
            return false;
        return (!state.hasTextureId() || utf8Length(state.textureIdUnsafe()) <= MAX_TEXTURE_BYTES)
            && (!state.hasAnimation() || validAnimation(state.animationUnsafe()));
    }

    public static boolean validEntityState(EntityStateProto state, boolean full) {
        if (full && (!state.hasGameplay() || !state.hasEffects()))
            return false;
        if (!full && !state.hasGameplay() && !state.hasEffects())
            return false;
        if (state.hasGameplay() && !validGameplay(state.gameplayUnsafe())
            || state.hasEffects() && !validEffects(state.effectsUnsafe()))
            return false;
        if (!full)
            return true;
        var g = state.gameplayUnsafe();
        return g.hasFlying() && g.hasExperienceLevel() && g.hasFoodLevel() && g.hasHealth() && g.hasMaxHealth()
            && g.hasMoveXQ7() && g.hasMoveYQ7() && g.hasMoveZQ7() && g.hasShieldCooldown();
    }

    public static boolean validSync(SelectionStateSyncProto message) {
        return validSyncShape(message)
            && (!message.hasState() || !message.state().hasRoaming() || validRoaming(message.state().roamingUnsafe()));
    }

    /** Leaves state roaming to the selection decoder; event arguments are validated here. */
    public static boolean validSyncShape(SelectionStateSyncProto message) {
        if (message.entityId() < 0 || message.intentId() < 0)
            return false;
        if (message.hasState())
            return validSelectionStateShape(message.state(), false);
        if (message.intentId() == 0 || !message.hasSync() || message.sync().arguments().size() > 16)
            return false;
        for (float value : message.sync().arguments())
            if (!Float.isFinite(value))
                return false;
        return true;
    }

    private static boolean validGameplay(GameplayStateProto state) {
        if (state.hasExperienceLevel() && !between(state.experienceLevelUnsafe(), 0, MAX_GAMEPLAY_VALUE)
            || state.hasFoodLevel() && !between(state.foodLevelUnsafe(), 0, 20)
            || state.hasHealth() && !between(state.healthUnsafe(), 0, MAX_GAMEPLAY_VALUE)
            || state.hasMaxHealth() && !between(state.maxHealthUnsafe(), 0, MAX_GAMEPLAY_VALUE)
            || state.hasMoveXQ7() && !between(state.moveXQ7Unsafe(), -127, 127)
            || state.hasMoveYQ7() && !between(state.moveYQ7Unsafe(), -127, 127)
            || state.hasMoveZQ7() && !between(state.moveZQ7Unsafe(), -127, 127)) {
            return false;
        }
        return !state.hasHealth() || !state.hasMaxHealth() || state.healthUnsafe() <= state.maxHealthUnsafe();
    }

    private static boolean validEffects(EffectStateSetProto state) {
        if (state.effects().size() > MAX_EFFECTS) {
            return false;
        }
        var ids = new HashSet<String>();
        for (var effect : state.effects()) {
            if (effect.effectId().isBlank() || utf8Length(effect.effectId()) > MAX_EFFECT_ID_BYTES || effect.level() < 0
                || effect.level() > 255 || !ids.add(effect.effectId())) {
                return false;
            }
        }
        return true;
    }

    private static boolean validAnimation(AnimationStateProto state) {
        if (state.hasStopped()) {
            return state.stopped();
        }
        return state.hasAnimationId() && !state.animationId().isBlank()
            && utf8Length(state.animationId()) <= MAX_ANIMATION_BYTES;
    }

    public static boolean validRoaming(RoamingStateProto state) {
        try {
            SelectionCodec.read(state);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean between(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }
}
