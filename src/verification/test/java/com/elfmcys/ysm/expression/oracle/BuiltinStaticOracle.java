package com.elfmcys.ysm.expression.oracle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Hand-authored Contract 3 types; deliberately independent of host declarations. */
final class BuiltinStaticOracle {
    enum Type {
        UNSET, BOOL, F32, STRING, VECTOR, SOUND_ID, OPTIONAL_BOOL, OPTIONAL_F32, OPTIONAL_STRING
    }

    record Signature(Type result, List<Type> fixed, Type variadic, int defaultCount) {
        Signature {
            fixed = List.copyOf(fixed);
        }

        boolean hasVariadic() {
            return variadic != null;
        }
    }

    private static final Map<String, Type> READ_RESULTS = readResults();
    private static final Map<String, Signature> INVOKE_SIGNATURES =
        Map.ofEntries(optional("ctrl.armor", Type.OPTIONAL_BOOL, 1, Type.STRING, Type.STRING, Type.BOOL),
            optional("ctrl.hold", Type.OPTIONAL_BOOL, 1, Type.STRING, Type.STRING, Type.BOOL),
            invoke("ctrl.indicate_reload", Type.UNSET),
            optional("ctrl.set_animation", Type.UNSET, 1, Type.STRING, Type.F32),
            invoke("ctrl.set_beginning_transition_length", Type.UNSET, Type.F32),
            optional("ctrl.swing", Type.OPTIONAL_BOOL, 1, Type.STRING, Type.STRING, Type.BOOL),
            optional("ctrl.use", Type.OPTIONAL_BOOL, 1, Type.STRING, Type.STRING, Type.BOOL),
            invoke("math.random_integer", Type.F32, Type.F32, Type.F32),
            optional("math.random", Type.F32, 1, Type.F32, Type.F32, Type.F32),
            invoke("ysm.bone_pivot_abs", Type.VECTOR, Type.STRING), invoke("ysm.bone_rot", Type.VECTOR, Type.STRING),
            variadic("ysm.has_any_curios", Type.OPTIONAL_BOOL, Type.STRING, List.of(Type.STRING)),
            variadic("ysm.keyboard", Type.BOOL, Type.F32, List.of()),
            invoke("ysm.mod_version", Type.OPTIONAL_STRING, Type.STRING),
            variadic("ysm.particle", Type.UNSET, Type.F32, List.of(Type.STRING)),
            optional("ysm.play_sound", Type.UNSET, 3, Type.SOUND_ID, Type.STRING, Type.F32, Type.F32, Type.F32),
            variadic("ysm.second_order", Type.F32, Type.F32, List.of(Type.STRING, Type.F32)),
            optional("ysm.stop_sound", Type.UNSET, 1, Type.SOUND_ID, Type.BOOL),
            variadic("ysm.sync", Type.UNSET, Type.F32, List.of()),
            variadic("query.is_item_name_any", Type.OPTIONAL_BOOL, Type.STRING, List.of(Type.STRING)),
            invoke("query.position_delta", Type.OPTIONAL_F32, Type.F32),
            invoke("query.position", Type.OPTIONAL_F32, Type.F32),
            variadic("query.relative_block_has_any_tag", Type.OPTIONAL_BOOL, Type.STRING,
                List.of(Type.F32, Type.F32, Type.F32)),
            invoke("ctrl.reset", Type.UNSET), variadic("ysm.abs_particle", Type.UNSET, Type.F32, List.of(Type.STRING)),
            invoke("ysm.bone_color", Type.UNSET, Type.STRING, Type.F32, Type.F32, Type.F32),
            invoke("ysm.bone_glow", Type.UNSET, Type.STRING, Type.F32),
            invoke("ysm.bone_transparency", Type.UNSET, Type.STRING, Type.F32),
            variadic("ysm.defer", Type.UNSET, Type.F32, List.of(Type.F32)),
            variadic("ysm.first_order", Type.F32, Type.F32, List.of(Type.STRING, Type.F32)),
            optional("ysm.stop_all_sounds", Type.UNSET, 1, Type.BOOL));

    private BuiltinStaticOracle() {}

    static Type result(String sourceSpelling) {
        Type read = READ_RESULTS.get(sourceSpelling);
        if (read != null) {
            return read;
        }
        Signature invoke = INVOKE_SIGNATURES.get(sourceSpelling);
        if (invoke != null) {
            return invoke.result();
        }
        throw new IllegalArgumentException("missing Contract 3 type for " + sourceSpelling);
    }

    static Signature signature(String sourceSpelling) {
        Signature result = INVOKE_SIGNATURES.get(sourceSpelling);
        if (result == null) {
            throw new IllegalArgumentException("missing Contract 3 signature for " + sourceSpelling);
        }
        return result;
    }

    static Set<String> sources() {
        var result = new java.util.LinkedHashSet<>(READ_RESULTS.keySet());
        result.addAll(INVOKE_SIGNATURES.keySet());
        return Set.copyOf(result);
    }

    private static Map.Entry<String, Signature> invoke(String source, Type result, Type... fixed) {
        return Map.entry(source, new Signature(result, List.of(fixed), null, 0));
    }

    private static Map.Entry<String, Signature> optional(String source, Type result, int defaultCount, Type... fixed) {
        return Map.entry(source, new Signature(result, List.of(fixed), null, defaultCount));
    }

    private static Map.Entry<String, Signature> variadic(String source, Type result, Type tail, List<Type> fixed) {
        return Map.entry(source, new Signature(result, fixed, tail, 0));
    }

    private static Map<String, Type> readResults() {
        var result = new LinkedHashMap<String, Type>();
        add(result, Type.OPTIONAL_STRING, "ctrl.bcombat_attack_animation", "ctrl.parcool_state",
            "ctrl.slashblade_animation");
        add(result, Type.OPTIONAL_BOOL, "ctrl.carryon_is_princess", "ctrl.has_sophisticated_backpack",
            "ctrl.tac_hold_gun");
        add(result, Type.OPTIONAL_F32, "ctrl.im_delta", "ctrl.im_pitch", "ctrl.im_time");
        add(result, Type.STRING, "ysm.shoot_item_id", "ysm.texture_name");
        add(result, Type.BOOL, "ctrl.attacked", "ctrl.death", "ctrl.elytra_fly", "ctrl.fly", "ctrl.idle", "ctrl.jump",
            "ctrl.ladder_down", "ctrl.ladder_stillness", "ctrl.ladder_up", "ctrl.playing_extra_animation", "ctrl.run",
            "ctrl.sleep", "ctrl.sneak", "ctrl.sneaking", "ctrl.swim_stand", "ctrl.swim", "ctrl.walk", "ysm.has_boots",
            "ysm.has_chest_plate", "ysm.has_elytra", "ysm.has_helmet", "ysm.has_leggings", "ysm.has_mainhand",
            "ysm.has_offhand", "ysm.in_shield_block_cooldown", "ysm.is_close_eyes", "ysm.is_fishing", "ysm.is_open_air",
            "ysm.mainhand_charged_crossbow", "ysm.offhand_charged_crossbow", "ysm.rendering_in_inventory",
            "query.all_animations_finished", "query.any_animation_finished", "query.is_eating", "query.is_in_water",
            "query.is_jumping", "query.is_on_ground", "query.is_sneaking", "query.is_swimming");
        add(result, Type.F32, "ctrl.state_bypass", "ctrl.state_continue", "ctrl.state_stop", "o", "ysm.armor_value",
            "ysm.elytra_rot_z", "ysm.food_level", "ysm.fps", "ysm.ground_speed2", "ysm.head_pitch", "ysm.head_yaw",
            "ysm.input_horizontal", "ysm.input_vertical", "ysm.weather", "ysm.yya", "query.anim_time",
            "query.body_y_rotation", "query.cardinal_facing_2d", "query.eye_target_y_rotation", "query.ground_speed",
            "query.head_x_rotation", "query.head_y_rotation", "query.health", "query.life_time", "query.max_health",
            "query.time_stamp", "query.vertical_speed", "query.yaw_speed");
        return Map.copyOf(result);
    }

    private static void add(Map<String, Type> target, Type type, String... sources) {
        for (String source : sources) {
            if (target.put(source, type) != null) {
                throw new IllegalArgumentException("duplicate Contract 3 source " + source);
            }
        }
    }
}
