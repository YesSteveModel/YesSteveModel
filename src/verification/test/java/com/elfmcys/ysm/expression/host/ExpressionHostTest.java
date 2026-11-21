package com.elfmcys.ysm.expression.host;

import dev.semaweave.ArityMismatchPolicy;
import dev.semaweave.ExecutionLimits;
import dev.semaweave.NonFinitePolicy;
import dev.semaweave.ObjectLanguageRules;
import dev.semaweave.SymbolKey;
import dev.semaweave.BuiltinType;
import dev.semaweave.ValueType;
import dev.semaweave.registry.DefaultValue;
import dev.semaweave.registry.FunctionCallShape;
import dev.semaweave.registry.InvocationKind;
import dev.semaweave.registry.JavaMemberBinding;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExpressionHostTest {
    @Test
    void everyAcceptedDeclarationHasFrozenScopeIncludingOptionalProviders() throws Exception {
        var host = ExpressionHost.create(HostAvailability.all());
        int subject = 0;
        int origin = 0;
        int optional = 0;
        for (var declaration : host.declarations()) {
            var groups = new TreeMap<String, String>();
            for (var member : declaration.members()) {
                var schema = member.schema();
                assertTrue(java.util.Set.of("SUBJECT", "ORIGIN").contains(schema.scope()));
                var previous = groups.putIfAbsent(schema.namespace() + ":" + schema.symbol(), schema.scope());
                if (previous != null)
                    assertEquals(previous, schema.scope());
                if (schema.scope().equals("SUBJECT")) {
                    assertTrue(schema.isStatic(), schema.javaName());
                    subject++;
                } else {
                    origin++;
                }
                if (declaration.owner().getName().contains(".compat."))
                    optional++;
                assertEquals(schema,
                    com.elfmcys.ysm.api.molang.DeclarationSchema
                        .decode(new java.io.ByteArrayInputStream(declaration.schema().encode())).members().stream()
                        .filter(value -> value.adapter().equals(schema.adapter())).findFirst().orElseThrow());
            }
        }
        assertEquals(263, subject + origin);
        assertTrue(subject > 0);
        assertTrue(origin > 0);
        assertTrue(optional > 0);
        System.out.println("DECLARATION_SCOPE subject=" + subject + " origin=" + origin + " optional=" + optional);
    }

    @Test
    void rejectedReasonsAndVersionsDoNotChangeTheSamePublishedIdentity() {
        var missing = ExpressionHost.create(HostAvailability.none());
        var unrelated = ExpressionHost
            .create(new HostAvailability(java.util.Set.of(), Map.of("absent_optional", "changed-version")));
        assertEquals(missing.registry().fingerprint(), unrelated.registry().fingerprint());
        assertEquals(missing.hostAbi(), unrelated.hostAbi());
        assertEquals(missing.expressionBinding().fingerprint(), unrelated.expressionBinding().fingerprint());
        var sample = com.elfmcys.ysm.expression.inventory.ProgramRole.ANIMATION_SAMPLE.entryPermissions();
        assertFalse(sample.actionAllowed());
        assertEquals(dev.semaweave.EntryPermissions.CONTEXT, sample);
    }

    @Test
    void frozenHostCatalogHasExactTypesShapesDefaultsAndTails() {
        var host = ExpressionHost.create(HostAvailability.all());
        assertTrue(host.registry().variables().isEmpty());

        var expectedFunctions = expectedFunctions();
        var actualFunctions = new TreeMap<String, ExpectedFunction>();
        host.registry().functions().forEach((key, group) -> group.methods().forEach(value -> {
            actualFunctions.put(signatureKey(key.canonical(), value.parameterTypes()),
                new ExpectedFunction(value.callShape(), value.minArgs(), value.maxArgs(), value.parameterTypes(),
                    value.defaults(), value.variadicElementType().orElse(null), value.returnType(),
                    value.nullableReturn()));
        }));
        assertEquals(expectedFunctions, actualFunctions);
        assertEquals(277, expectedFunctions.size());
    }

    @Test
    void registryAndManifestAreDeterministicAndComplete() {
        var first = ExpressionHost.create(HostAvailability.all());
        var second = ExpressionHost.create(HostAvailability.all());

        assertEquals(first.registry().fingerprint(), second.registry().fingerprint());
        assertSame(first.registry().contextType(), first.contextType());
        assertEquals(first.hostAbi(), second.hostAbi());
        assertEquals(first.manifest(), second.manifest());
        assertEquals(NonFinitePolicy.ZERO_NAN_ARITHMETIC, first.compilationPolicy().numericPolicy().nonFinite());
        assertEquals(ArityMismatchPolicy.UNSET_CALL, first.compilationPolicy().arityMismatch());
        assertEquals(ObjectLanguageRules.YSM_STATIC_OBJECTS, first.compilationPolicy().objectLanguageRules());
        assertEquals(273, first.registry().variables().size() + first.registry().functions().size());
        assertEquals(279, first.manifest().size());
        assertTrue(first.registry().conversionPath(BuiltinType.UNSET, BuiltinType.F32).isEmpty());
        assertTrue(first.registry().conversionPath(BuiltinType.UNSET, BuiltinType.BOOL).isEmpty());
        assertTrue(first.registry().conversionPath(BuiltinType.STRING, BuiltinType.F32).isEmpty());
        assertTrue(first.registry().conversionPath(BuiltinType.STRING, BuiltinType.BOOL).isEmpty());
        for (var symbol : first.builtins().stream().map(BuiltinDescriptor::stableId).distinct().toList()) {
            assertManifestProjection(first, symbol);
        }
        var random =
            first.manifest().stream().filter(entry -> entry.stableId().equals("math:random")).findFirst().orElseThrow();
        assertEquals("OPTIONAL", random.callShape());
        assertEquals("LEFT_TO_RIGHT_EAGER", random.evaluationOrder());
        assertTrue(first.registry().function(new SymbolKey("math:abs")).isPresent());
        assertTrue(first.registry().function(new SymbolKey("math:pi")).isEmpty());

        var betterCombatAnimation = first.manifest().stream()
            .filter(entry -> entry.stableId().equals("ctrl:bcombat_attack_animation")).findFirst().orElseThrow();
        assertEquals("builtin:string", betterCombatAnimation.resultType());
        assertTrue(betterCombatAnimation.javaMember().contains("MolangBindings"));

        var playSound = first.manifest().stream().filter(entry -> entry.stableId().equals("ysm:play_sound"))
            .filter(entry -> entry.argumentTypes().get(0).equals(BuiltinType.F32.stableId())).findFirst().orElseThrow();
        assertEquals(List.of(BuiltinType.F32.stableId(), BuiltinType.STRING.stableId(), BuiltinType.F32.stableId(),
            BuiltinType.F32.stableId(), BuiltinType.F32.stableId()), playSound.argumentTypes());

    }

    @Test
    void optionalAvailabilityAndLimitsChangeExactIdentity() {
        var absent = ExpressionHost.create(HostAvailability.none());
        var present = ExpressionHost.create(HostAvailability.all());
        var changedLimits = ExpressionHost.create(HostAvailability.none(), new ExecutionLimits(12));

        var optional = new SymbolKey("ysm:has_any_curios");
        assertFalse(absent.registry().function(optional).isPresent());
        assertTrue(present.registry().function(optional).isPresent());
        assertNotEquals(absent.registry().fingerprint(), present.registry().fingerprint());
        assertEquals(absent.registry().fingerprint(), changedLimits.registry().fingerprint());
        assertNotEquals(absent.hostAbi().fingerprint(), changedLimits.hostAbi().fingerprint());
    }

    @Test
    void returnPresenceAndFloatListAccessAreFrozenWithoutValueWrappers() {
        var registry = ExpressionHost.create(HostAvailability.all()).registry();
        assertTrue(registry.variables().isEmpty());
        assertEquals(
            expectedFunctions().entrySet().stream().filter(entry -> entry.getValue().mayReturnMissing())
                .map(entry -> entry.getKey().substring(0, entry.getKey().indexOf('[')))
                .collect(java.util.stream.Collectors.toSet()),
            registry.functions().entrySet().stream().filter(entry -> entry.getValue().nullableReturn())
                .map(entry -> entry.getKey().canonical()).collect(java.util.stream.Collectors.toSet()));
        var list = (dev.semaweave.registry.IndexedListTypeDescriptor)registry.types()
            .get(ExpressionHost.FLOAT_LIST.stableId());
        assertEquals(it.unimi.dsi.fastutil.floats.FloatList.class, list.carrierClass());
        assertEquals(InvocationKind.RECEIVER, list.length().invocationKind());
        assertEquals(InvocationKind.RECEIVER, list.indexedRead().invocationKind());
        assertEquals("getFloat", list.indexedRead().methodName());
        assertEquals(it.unimi.dsi.fastutil.floats.FloatList.class, list.indexedRead().owner());
        assertEquals(MethodType.methodType(float.class, int.class), list.indexedRead().methodType());
    }

    private static void assertManifestProjection(ExpressionHost host, String symbol) {
        var entry =
            host.manifest().stream().filter(candidate -> candidate.stableId().equals(symbol)).findFirst().orElseThrow();
        var key = new SymbolKey(symbol);
        assertEquals("INVOKE", entry.accessKind());

        for (var descriptor : host.registry().function(key).orElseThrow().methods()) {
            entry = host.manifest().stream().filter(candidate -> candidate.stableId().equals(symbol))
                .filter(candidate -> candidate.javaMember().startsWith(descriptor.method().toString() + ":"))
                .findFirst().orElseThrow();
            assertEquals(descriptor.callShape().name(), entry.callShape());
            assertEquals(descriptor.minArgs(), entry.minimumArguments());
            assertEquals(descriptor.maxArgs(), entry.maximumArguments());
            assertEquals(descriptor.parameterTypes().stream().map(ValueType::stableId).toList(), entry.argumentTypes());
            assertEquals(descriptor.returnType().stableId(), entry.resultType());
            var javaType = descriptor.method().methodType();
            assertCarrier(descriptor.returnType(), descriptor.nullableReturn(), javaType.returnType());
            int offset = 0;
            if (descriptor.injectContext())
                assertEquals(MolangContext.class, javaType.parameterType(offset++));
            if (descriptor.callShape() != FunctionCallShape.FIXED)
                assertEquals(int.class, javaType.parameterType(offset++));
            for (int i = 0; i < descriptor.parameterTypes().size(); i++)
                assertCarrier(descriptor.parameterTypes().get(i), false, javaType.parameterType(offset + i));
            descriptor.variadicElementType()
                .ifPresent(type -> assertCarrier(type, false, javaType.lastParameterType().getComponentType()));
            assertEquals(
                offset + descriptor.parameterTypes().size() + (descriptor.variadicElementType().isPresent() ? 1 : 0),
                javaType.parameterCount());
            assertBindingProjection(entry, descriptor.method());
        }
    }

    /**
     * The manifest must project the exact binding the registry froze for this capability.
     *
     * <p>
     * A built-in capability binds a static declaration method and names no provider instance; an instance binding
     * (public extension) names the provider that carries it.
     */
    private static void assertBindingProjection(ExpressionHost.ManifestEntry entry, JavaMemberBinding binding) {
        assertTrue(entry.javaMember().startsWith(binding.toString() + ":"));
        if (binding.invocationKind() == InvocationKind.INSTANCE) {
            assertEquals(binding.providerId().orElseThrow(), entry.providerId());
        }
    }

    private static void assertCarrier(ValueType type, boolean nullable, Class<?> actual) {
        if (type == BuiltinType.F32) {
            assertTrue(nullable ? actual == float.class || actual == Float.class || actual == Integer.class
                : actual == float.class || actual == int.class);
        } else if (type == BuiltinType.BOOL)
            assertEquals(nullable ? Boolean.class : boolean.class, actual);
        else if (type == BuiltinType.STRING)
            assertEquals(String.class, actual);
        else if (type.equals(ExpressionHost.VECTOR))
            assertEquals(MolangVector.class, actual);
        else if (type.equals(ExpressionHost.ENTITY))
            assertEquals(net.minecraft.world.entity.Entity.class, actual);
        else if (type == BuiltinType.UNSET)
            assertEquals(void.class, actual);
        else
            throw new AssertionError(type);
    }

    private static Map<String, ExpectedFunction> expectedZeroArgumentFunctions() {
        var result = new TreeMap<String, ExpectedFunction>();
        putZeroArgumentFunctions(result, "ctrl:", false, BuiltinType.BOOL, "playing_extra_animation");
        putZeroArgumentFunctions(result, "ctrl:", true, BuiltinType.BOOL, "attacked", "death", "elytra_fly", "fly",
            "idle", "jump", "ladder_down", "ladder_stillness", "ladder_up", "run", "sleep", "sneak", "sneaking",
            "swim_stand", "swim", "walk");
        putZeroArgumentFunctions(result, "ctrl:", false, BuiltinType.F32, "state_bypass", "state_continue",
            "state_stop");
        putZeroArgumentFunctions(result, "ctrl:", false, BuiltinType.STRING, "bcombat_attack_animation",
            "parcool_state", "slashblade_animation");
        putZeroArgumentFunctions(result, "ctrl:", false, BuiltinType.BOOL, "carryon_is_princess",
            "has_sophisticated_backpack", "tac_hold_gun");
        putZeroArgumentFunctions(result, "ctrl:", true, BuiltinType.F32, "im_delta", "im_time");
        putZeroArgumentFunctions(result, "global:", false, BuiltinType.F32, "o");
        putZeroArgumentFunctions(result, "ysm:", false, BuiltinType.F32, "armor_value", "elytra_rot_z", "fps",
            "weather");
        putZeroArgumentFunctions(result, "ysm:", true, BuiltinType.F32, "food_level", "head_pitch", "head_yaw",
            "input_horizontal", "input_vertical", "yya");
        putZeroArgumentFunctions(result, "ysm:", false, BuiltinType.BOOL, "has_boots", "has_chest_plate", "has_elytra",
            "has_helmet", "has_leggings", "has_mainhand", "has_offhand", "is_fishing", "is_open_air",
            "mainhand_charged_crossbow", "offhand_charged_crossbow", "rendering_in_inventory");
        putZeroArgumentFunctions(result, "ysm:", true, BuiltinType.BOOL, "in_shield_block_cooldown", "is_close_eyes");
        putZeroArgumentFunctions(result, "ysm:", false, BuiltinType.STRING, "shoot_item_id", "texture_name");
        putZeroArgumentFunctions(result, "query:", false, BuiltinType.BOOL, "all_animations_finished",
            "any_animation_finished", "is_eating", "is_in_water", "is_on_ground", "is_sneaking", "is_swimming");
        putZeroArgumentFunctions(result, "query:", true, BuiltinType.BOOL, "is_jumping");
        putZeroArgumentFunctions(result, "query:", false, BuiltinType.F32, "anim_time", "cardinal_facing_2d",
            "life_time", "time_stamp");
        putZeroArgumentFunctions(result, "query:", true, BuiltinType.F32, "body_y_rotation", "eye_target_y_rotation",
            "head_x_rotation", "head_y_rotation", "health", "max_health");
        putZeroArgumentFunctions(result, "ysm:", true, BuiltinType.F32, "ground_speed2");
        putZeroArgumentFunctions(result, "ctrl:", true, BuiltinType.F32, "im_pitch");
        putZeroArgumentFunctions(result, "query:", true, BuiltinType.F32, "ground_speed", "vertical_speed",
            "yaw_speed");
        putZeroArgumentFunctions(result, "ctrl:", false, BuiltinType.BOOL, "create_hanging_skyhook", "swem_is_ride",
            "tac_is_fire", "tac_is_aim", "tac_is_reload", "tac_is_melee", "tac_is_draw");
        putZeroArgumentFunctions(result, "ctrl:", true, BuiltinType.BOOL, "riptide", "climb", "climbing");
        putZeroArgumentFunctions(result, "ctrl:", false, BuiltinType.F32, "state_pause", "loop", "play_once",
            "hold_on_last_frame");
        putZeroArgumentFunctions(result, "ctrl:", false, BuiltinType.STRING, "carryon_type", "iss_animation",
            "swem_state", "tac_gun_type", "tac_gun_id", "tac_fire_mode");
        putZeroArgumentFunctions(result, "ctrl:", true, BuiltinType.F32, "im_volume", "im_current");
        putZeroArgumentFunctions(result, "query:", false, BuiltinType.F32, "actor_count", "moon_phase", "time_of_day",
            "modified_distance_moved", "walk_distance", "hurt_time", "item_in_use_duration", "item_max_use_duration",
            "item_remaining_use_duration", "equipment_count");
        putZeroArgumentFunctions(result, "query:", true, BuiltinType.F32, "eye_target_x_rotation", "delta_time",
            "body_x_rotation", "player_level");
        putZeroArgumentFunctions(result, "query:", false, BuiltinType.BOOL, "has_rider", "is_in_water_or_rain",
            "is_on_fire", "is_riding", "is_spectator", "is_sprinting", "is_playing_dead", "is_sleeping",
            "is_using_item", "has_cape", "is_first_person");
        putZeroArgumentFunctions(result, "query:", false, BuiltinType.F32, "distance_from_camera", "cape_flap_amount");
        putZeroArgumentFunctions(result, "ysm:", false, BuiltinType.F32, "air_supply", "frozen_ticks", "block_light",
            "sky_light", "arrow_count", "stinger_count", "hurt_time", "swing_time", "swinging_arm", "attack_time",
            "elytra_rot_x", "elytra_rot_y", "on_ground_time", "ladder_facing", "person_view");
        putZeroArgumentFunctions(result, "ysm:", true, BuiltinType.F32, "time_delta", "xxa", "zza");
        putZeroArgumentFunctions(result, "ysm:", true, BuiltinType.F32, "delta_movement_length", "attack_damage",
            "attack_speed", "attack_knockback", "movement_speed", "knockback_resistance", "luck", "block_reach",
            "entity_reach", "swim_speed", "entity_gravity", "step_height_addition", "nametag_distance");
        putZeroArgumentFunctions(result, "ysm:", false, BuiltinType.BOOL, "eye_in_water", "is_passenger", "is_sleep",
            "is_sneak", "is_riptide", "on_ladder", "is_player", "swinging", "has_left_shoulder_parrot",
            "has_right_shoulder_parrot", "in_ground", "is_spectral_arrow", "is_biting", "first_person_mod_hide",
            "rendering_in_paperdoll");
        putZeroArgumentFunctions(result, "ysm:", false, BuiltinType.STRING, "dimension_name", "entity_type",
            "hit_target_type", "hit_target_id", "left_shoulder_parrot_variant", "right_shoulder_parrot_variant",
            "hooked_in", "throwable_item");
        putZeroArgumentFunctions(result, "ysm:", true, ExpressionHost.ENTITY, "projectile_owner");
        return result;
    }

    private static Map<String, ExpectedFunction> expectedFunctions() {
        var result = new TreeMap<>(expectedZeroArgumentFunctions());
        putFunctions(result,
            optionalFunction(true, 2, 3, List.of(BuiltinType.STRING, BuiltinType.STRING, BuiltinType.BOOL),
                List.of(DefaultValue.ofBoolean(false)), BuiltinType.BOOL),
            "ctrl:armor", "ctrl:hold", "ctrl:swing", "ctrl:use");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET), "ctrl:indicate_reload", "ctrl:reset");
        putFunctions(result, fixedFunction(false, BuiltinType.BOOL), "query:debug_output_enabled");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET), "query:debug_output_begin",
            "query:debug_output_end");
        for (var type : List.of(BuiltinType.STRING, BuiltinType.F32, BuiltinType.BOOL))
            putFunctions(result, fixedFunction(false, BuiltinType.UNSET, type), "query:debug_output_append");
        putFunctions(result,
            variadicFunction(false, 1, Integer.MAX_VALUE, List.of(), BuiltinType.STRING, BuiltinType.UNSET),
            "query:debug_output");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET), "ysm:biome_category");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET), "ysm:dump_mods", "ysm:dump_effects",
            "ysm:dump_biome", "ysm:dump_curios");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET, BuiltinType.STRING), "ysm:dump_equipped_item");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
            "ysm:dump_relative_block");
        putFunctions(result, optionalFunction(false, 1, 2, List.of(BuiltinType.STRING, BuiltinType.F32),
            List.of(DefaultValue.ofFloat(0)), BuiltinType.UNSET), "ctrl:set_animation");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET, BuiltinType.F32),
            "ctrl:set_beginning_transition_length");
        putFunctions(result, fixedFunction(false, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
            "math:random_integer");
        putFunctions(result, optionalFunction(false, 2, 3, List.of(BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
            List.of(DefaultValue.ofFloat(0)), BuiltinType.F32), "math:random");
        putFunctions(result, fixedFunction(false, ExpressionHost.VECTOR, BuiltinType.STRING), "ysm:bone_rot");
        putFunctions(result, variadicFunction(true, 1, Integer.MAX_VALUE, List.of(BuiltinType.STRING),
            BuiltinType.STRING, BuiltinType.BOOL), "ysm:has_any_curios");
        putFunctions(result,
            variadicFunction(false, 1, Integer.MAX_VALUE, List.of(), BuiltinType.F32, BuiltinType.BOOL),
            "ysm:keyboard");
        putFunctions(result, fixedFunction(false, BuiltinType.STRING, BuiltinType.STRING), "ysm:mod_version");
        putFunctions(result, variadicFunction(false, 1, Integer.MAX_VALUE, List.of(BuiltinType.STRING), BuiltinType.F32,
            BuiltinType.UNSET), "ysm:particle", "ysm:abs_particle");
        for (var id : List.of(BuiltinType.F32, BuiltinType.STRING)) {
            putFunctions(result, optionalFunction(false, 2, 5,
                List.of(id, BuiltinType.STRING, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
                List.of(DefaultValue.ofFloat(0), DefaultValue.ofFloat(1), DefaultValue.ofFloat(1)), BuiltinType.UNSET),
                "ysm:play_sound");
            putFunctions(result, optionalFunction(false, 1, 2, List.of(id, BuiltinType.BOOL),
                List.of(DefaultValue.ofBoolean(false)), BuiltinType.UNSET), "ysm:stop_sound");
        }
        putFunctions(result, variadicFunction(true, 2, Integer.MAX_VALUE, List.of(BuiltinType.STRING, BuiltinType.F32),
            BuiltinType.F32, BuiltinType.F32), "ysm:first_order", "ysm:second_order");
        putFunctions(result, optionalFunction(false, 0, 1, List.of(BuiltinType.BOOL),
            List.of(DefaultValue.ofBoolean(false)), BuiltinType.UNSET), "ysm:stop_all_sounds");
        putFunctions(result, variadicFunction(false, 0, 16, List.of(), BuiltinType.F32, BuiltinType.UNSET), "ysm:sync");
        putFunctions(result,
            variadicFunction(false, 1, Integer.MAX_VALUE, List.of(BuiltinType.F32), BuiltinType.F32, BuiltinType.UNSET),
            "ysm:defer");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET, BuiltinType.STRING, BuiltinType.F32,
            BuiltinType.F32, BuiltinType.F32), "ysm:bone_color");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET, BuiltinType.STRING, BuiltinType.F32),
            "ysm:bone_glow", "ysm:bone_transparency");
        putFunctions(result, variadicFunction(true, 2, Integer.MAX_VALUE, List.of(BuiltinType.STRING),
            BuiltinType.STRING, BuiltinType.BOOL), "query:is_item_name_any");
        putFunctions(result, fixedFunction(true, BuiltinType.F32, BuiltinType.F32), "query:position",
            "query:position_delta");
        putFunctions(
            result, variadicFunction(true, 4, Integer.MAX_VALUE,
                List.of(BuiltinType.F32, BuiltinType.F32, BuiltinType.F32), BuiltinType.STRING, BuiltinType.BOOL),
            "query:relative_block_has_any_tag");
        putFunctions(result, fixedFunction(false, BuiltinType.F32, BuiltinType.F32), "math:abs", "math:cos", "math:exp",
            "math:floor", "math:min_angle", "math:round", "math:sin", "math:sqrt");
        putFunctions(result, fixedFunction(false, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32), "math:max",
            "math:min", "math:mod", "math:pow");
        putFunctions(result, fixedFunction(false, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
            "math:clamp", "math:lerp");
        putFunctions(result,
            optionalFunction(false, 2, 3, List.of(BuiltinType.STRING, BuiltinType.STRING, BuiltinType.BOOL),
                List.of(DefaultValue.ofBoolean(false)), BuiltinType.BOOL),
            "ctrl:ride");
        putFunctions(result, fixedFunction(false, BuiltinType.F32, BuiltinType.F32), "math:ceil", "math:trunc",
            "math:ln", "math:acos", "math:asin", "math:atan", "math:hermite", "math:hermite_blend");
        putFunctions(result, fixedFunction(false, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32), "math:atan2",
            "math:randomi");
        putFunctions(result, fixedFunction(false, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
            "math:lerprotate", "math:die_roll", "math:die_roll_integer", "math:roll", "math:rolli");
        putFunctions(result, fixedFunction(true, ExpressionHost.VECTOR, BuiltinType.STRING), "ysm:bone_pos",
            "ysm:bone_scale", "ysm:bone_pivot_abs");
        putFunctions(result,
            fixedFunction(false, BuiltinType.STRING, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
            "ysm:relative_block_name");
        putFunctions(result,
            variadicFunction(true, 4, Integer.MAX_VALUE, List.of(BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
                BuiltinType.STRING, BuiltinType.BOOL),
            "ysm:relative_block_name_any", "query:relative_block_has_all_tags");
        putFunctions(result,
            variadicFunction(true, 1, Integer.MAX_VALUE, List.of(), BuiltinType.STRING, BuiltinType.F32),
            "ysm:effect_level");
        putFunctions(result, variadicFunction(true, 2, Integer.MAX_VALUE, List.of(BuiltinType.STRING),
            BuiltinType.STRING, BuiltinType.F32), "ysm:equipped_enchantment_level");
        putFunctions(result, variadicFunction(true, 2, Integer.MAX_VALUE, List.of(BuiltinType.F32, BuiltinType.F32),
            BuiltinType.F32, BuiltinType.F32), "ysm:perlin_noise");
        putFunctions(result, fixedFunction(false, BuiltinType.BOOL, BuiltinType.F32), "ysm:mouse");
        putFunctions(result, fixedFunction(false, BuiltinType.F32, BuiltinType.F32), "query:rotation_to_camera");
        putFunctions(result,
            variadicFunction(true, 1, Integer.MAX_VALUE, List.of(), BuiltinType.STRING, BuiltinType.BOOL),
            "query:biome_has_all_tags", "query:biome_has_any_tag");
        putFunctions(result,
            variadicFunction(true, 2, Integer.MAX_VALUE, List.of(BuiltinType.STRING), BuiltinType.STRING,
                BuiltinType.BOOL),
            "query:equipped_item_all_tags", "query:equipped_item_any_tag", "ysm:has_any_curios_with_all_tags",
            "ysm:has_any_curios_with_any_tag");
        putFunctions(result, fixedFunction(true, BuiltinType.F32, BuiltinType.STRING), "query:max_durability",
            "query:remaining_durability");
        return result;
    }

    private static String signatureKey(String symbol, List<ValueType> arguments) {
        return symbol + arguments.stream().map(ValueType::stableId).toList();
    }

    private static void putZeroArgumentFunctions(Map<String, ExpectedFunction> result, String prefix,
        boolean mayReturnMissing, ValueType type, String... names) {
        for (var name : names)
            putFunctions(result, fixedFunction(mayReturnMissing, type), prefix + name);
    }

    private static void putFunctions(Map<String, ExpectedFunction> result, ExpectedFunction function, String... names) {
        for (var name : names)
            assertEquals(null, result.put(signatureKey(name, function.arguments()), function));
    }

    private static ExpectedFunction fixedFunction(boolean mayReturnMissing, ValueType result, ValueType... arguments) {
        return new ExpectedFunction(FunctionCallShape.FIXED, arguments.length, arguments.length, List.of(arguments),
            List.of(), null, result, mayReturnMissing);
    }

    private static ExpectedFunction optionalFunction(boolean mayReturnMissing, int minimum, int maximum,
        List<ValueType> arguments, List<DefaultValue> defaults, ValueType result) {
        return new ExpectedFunction(FunctionCallShape.OPTIONAL, minimum, maximum, arguments, defaults, null, result,
            mayReturnMissing);
    }

    private static ExpectedFunction variadicFunction(boolean mayReturnMissing, int minimum, int maximum,
        List<ValueType> fixedArguments, ValueType tail, ValueType result) {
        return new ExpectedFunction(FunctionCallShape.VARIADIC, minimum, maximum, fixedArguments, List.of(), tail,
            result, mayReturnMissing);
    }

    private record ExpectedFunction(FunctionCallShape shape, int minimum, int maximum, List<ValueType> arguments,
        List<DefaultValue> defaults, ValueType variadic, ValueType result, boolean mayReturnMissing) {
        private ExpectedFunction {
            arguments = List.copyOf(arguments);
            defaults = List.copyOf(defaults);
        }
    }
}
