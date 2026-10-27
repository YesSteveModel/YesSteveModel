package com.elfmcys.ysm.expression.host;

import java.util.Objects;
import java.util.Set;

/**
 * Stable addressing tokens retained for command identity and diagnostics.
 *
 * <p>
 * Registration, signatures, result types and availability come only from the generated built-in surfaces. Adding a
 * declaration does not require adding a token unless runtime command transport needs one.
 */
public enum HostSymbol {
    CTRL_ATTACKED("ctrl:attacked"), CTRL_BCOMBAT_ATTACK_ANIMATION("ctrl:bcombat_attack_animation"),
    CTRL_CARRYON_IS_PRINCESS("ctrl:carryon_is_princess"), CTRL_DEATH("ctrl:death"), CTRL_ELYTRA_FLY("ctrl:elytra_fly"),
    CTRL_FLY("ctrl:fly"), CTRL_HAS_SOPHISTICATED_BACKPACK("ctrl:has_sophisticated_backpack"), CTRL_IDLE("ctrl:idle"),
    CTRL_IM_DELTA("ctrl:im_delta"), CTRL_IM_PITCH("ctrl:im_pitch"), CTRL_IM_TIME("ctrl:im_time"),
    CTRL_JUMP("ctrl:jump"), CTRL_LADDER_DOWN("ctrl:ladder_down"), CTRL_LADDER_STILLNESS("ctrl:ladder_stillness"),
    CTRL_LADDER_UP("ctrl:ladder_up"), CTRL_PARCOOL_STATE("ctrl:parcool_state"),
    CTRL_PLAYING_EXTRA_ANIMATION("ctrl:playing_extra_animation"), CTRL_RUN("ctrl:run"),
    CTRL_SLASHBLADE_ANIMATION("ctrl:slashblade_animation"), CTRL_SLEEP("ctrl:sleep"), CTRL_SNEAK("ctrl:sneak"),
    CTRL_SNEAKING("ctrl:sneaking"), CTRL_STATE_BYPASS("ctrl:state_bypass"), CTRL_STATE_CONTINUE("ctrl:state_continue"),
    CTRL_STATE_STOP("ctrl:state_stop"), CTRL_SWIM_STAND("ctrl:swim_stand"), CTRL_SWIM("ctrl:swim"),
    CTRL_TAC_HOLD_GUN("ctrl:tac_hold_gun"), CTRL_WALK("ctrl:walk"), GLOBAL_O("global:o"),
    YSM_ARMOR_VALUE("ysm:armor_value"), YSM_ELYTRA_ROT_Z("ysm:elytra_rot_z"), YSM_FOOD_LEVEL("ysm:food_level"),
    YSM_FPS("ysm:fps"), YSM_GROUND_SPEED_2("ysm:ground_speed2"), YSM_HAS_BOOTS("ysm:has_boots"),
    YSM_HAS_CHEST_PLATE("ysm:has_chest_plate"), YSM_HAS_ELYTRA("ysm:has_elytra"), YSM_HAS_HELMET("ysm:has_helmet"),
    YSM_HAS_LEGGINGS("ysm:has_leggings"), YSM_HAS_MAINHAND("ysm:has_mainhand"), YSM_HAS_OFFHAND("ysm:has_offhand"),
    YSM_HEAD_PITCH("ysm:head_pitch"), YSM_HEAD_YAW("ysm:head_yaw"),
    YSM_IN_SHIELD_BLOCK_COOLDOWN("ysm:in_shield_block_cooldown"), YSM_INPUT_HORIZONTAL("ysm:input_horizontal"),
    YSM_INPUT_VERTICAL("ysm:input_vertical"), YSM_IS_CLOSE_EYES("ysm:is_close_eyes"), YSM_IS_FISHING("ysm:is_fishing"),
    YSM_IS_OPEN_AIR("ysm:is_open_air"), YSM_MAINHAND_CHARGED_CROSSBOW("ysm:mainhand_charged_crossbow"),
    YSM_OFFHAND_CHARGED_CROSSBOW("ysm:offhand_charged_crossbow"),
    YSM_RENDERING_IN_INVENTORY("ysm:rendering_in_inventory"), YSM_SHOOT_ITEM_ID("ysm:shoot_item_id"),
    YSM_TEXTURE_NAME("ysm:texture_name"), YSM_WEATHER("ysm:weather"), YSM_YYA("ysm:yya"),
    QUERY_ALL_ANIMATIONS_FINISHED("query:all_animations_finished"), QUERY_ANIM_TIME("query:anim_time"),
    QUERY_ANY_ANIMATION_FINISHED("query:any_animation_finished"), QUERY_BODY_Y_ROTATION("query:body_y_rotation"),
    QUERY_CARDINAL_FACING_2D("query:cardinal_facing_2d"), QUERY_EYE_TARGET_Y_ROTATION("query:eye_target_y_rotation"),
    QUERY_GROUND_SPEED("query:ground_speed"), QUERY_HEAD_X_ROTATION("query:head_x_rotation"),
    QUERY_HEAD_Y_ROTATION("query:head_y_rotation"), QUERY_HEALTH("query:health"), QUERY_IS_EATING("query:is_eating"),
    QUERY_IS_IN_WATER("query:is_in_water"), QUERY_IS_JUMPING("query:is_jumping"),
    QUERY_IS_ON_GROUND("query:is_on_ground"), QUERY_IS_SNEAKING("query:is_sneaking"),
    QUERY_IS_SWIMMING("query:is_swimming"), QUERY_LIFE_TIME("query:life_time"), QUERY_MAX_HEALTH("query:max_health"),
    QUERY_TIME_STAMP("query:time_stamp"), QUERY_VERTICAL_SPEED("query:vertical_speed"),
    QUERY_YAW_SPEED("query:yaw_speed"), CTRL_ARMOR("ctrl:armor"), CTRL_HOLD("ctrl:hold"),
    CTRL_INDICATE_RELOAD("ctrl:indicate_reload"), CTRL_SET_ANIMATION("ctrl:set_animation"),
    CTRL_SET_BEGINNING_TRANSITION_LENGTH("ctrl:set_beginning_transition_length"), CTRL_SWING("ctrl:swing"),
    CTRL_USE("ctrl:use"), MATH_RANDOM_INTEGER("math:random_integer"), MATH_RANDOM("math:random"),
    YSM_BONE_PIVOT_ABS("ysm:bone_pivot_abs"), YSM_BONE_ROT("ysm:bone_rot"), YSM_HAS_ANY_CURIOS("ysm:has_any_curios"),
    YSM_KEYBOARD("ysm:keyboard"), YSM_MOD_VERSION("ysm:mod_version"), YSM_PARTICLE("ysm:particle"),
    YSM_PLAY_SOUND("ysm:play_sound"), YSM_SECOND_ORDER("ysm:second_order"), YSM_STOP_SOUND("ysm:stop_sound"),
    YSM_SYNC("ysm:sync"), QUERY_IS_ITEM_NAME_ANY("query:is_item_name_any"),
    QUERY_POSITION_DELTA("query:position_delta"), QUERY_POSITION("query:position"),
    QUERY_RELATIVE_BLOCK_HAS_ANY_TAG("query:relative_block_has_any_tag"), CTRL_RESET("ctrl:reset"),
    YSM_ABS_PARTICLE("ysm:abs_particle"), YSM_BONE_COLOR("ysm:bone_color"), YSM_BONE_GLOW("ysm:bone_glow"),
    YSM_BONE_TRANSPARENCY("ysm:bone_transparency"), YSM_DEFER("ysm:defer"), YSM_FIRST_ORDER("ysm:first_order"),
    YSM_STOP_ALL_SOUNDS("ysm:stop_all_sounds");

    public enum AccessKind {
        READ, INVOKE
    }

    private final String stableId;

    HostSymbol(String stableId) {
        this.stableId = Objects.requireNonNull(stableId, "stableId");
    }

    public String stableId() {
        return stableId;
    }

    public AccessKind accessKind() {
        return surface().accessKind();
    }

    public dev.semaweave.ValueType resultType() {
        return surface().resultType();
    }

    public int minimumArguments() {
        return surface().minimumArguments();
    }

    public int maximumArguments() {
        return surface().maximumArguments();
    }

    public OptionalCapability optionalCapability() {
        return surface().optionalCapability();
    }

    public Set<EffectKind> effects() {
        return surface().effects();
    }

    public boolean stagedEffect() {
        return surface().staged();
    }

    public String reference() {
        return "EXTERNAL:" + stableId + ':' + accessKind();
    }

    public static HostSymbol fromReference(String reference) {
        for (var value : values()) {
            if (value.reference().equals(reference)) {
                return value;
            }
        }
        return null;
    }

    public static HostSymbol fromStableId(String stableId) {
        for (var value : values()) {
            if (value.stableId.equals(stableId)) {
                return value;
            }
        }
        return null;
    }

    private BuiltinDeclarations.Group surface() {
        var surface = BuiltinDeclarations.group(stableId);
        if (surface == null) {
            throw new IllegalStateException("addressing token has no accepted built-in declaration " + stableId);
        }
        return surface;
    }
}
