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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExpressionHostTest {
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
        assertEquals(125, expectedFunctions.size());
    }

    @Test
    void registryAndManifestAreDeterministicAndComplete() {
        var first = ExpressionHost.create(HostAvailability.all());
        var second = ExpressionHost.create(HostAvailability.all());

        assertEquals(first.registry().fingerprint(), second.registry().fingerprint());
        assertSame(first.registry().contextType(), first.contextType());
        assertEquals(first.hostAbi(), second.hostAbi());
        assertEquals(first.manifest(), second.manifest());
        assertEquals(NonFinitePolicy.FAIL_EXECUTION, first.compilationPolicy().numericPolicy().nonFinite());
        assertEquals(ArityMismatchPolicy.UNSET_CALL, first.compilationPolicy().arityMismatch());
        assertEquals(ObjectLanguageRules.YSM_STATIC_OBJECTS, first.compilationPolicy().objectLanguageRules());
        assertEquals(HostSymbol.values().length + 14,
            first.registry().variables().size() + first.registry().functions().size());
        assertEquals(HostSymbol.values().length + 18, first.manifest().size());
        assertTrue(first.registry().conversionPath(BuiltinType.UNSET, BuiltinType.F32).isEmpty());
        assertTrue(first.registry().conversionPath(BuiltinType.UNSET, BuiltinType.BOOL).isEmpty());
        assertTrue(first.registry().conversionPath(BuiltinType.STRING, BuiltinType.F32).isEmpty());
        assertTrue(first.registry().conversionPath(BuiltinType.STRING, BuiltinType.BOOL).isEmpty());
        for (var symbol : HostSymbol.values()) {
            assertManifestProjection(first, symbol);
        }
        var random = first.manifest().stream()
            .filter(entry -> entry.stableId().equals(HostSymbol.MATH_RANDOM.stableId())).findFirst().orElseThrow();
        assertEquals("OPTIONAL", random.callShape());
        assertEquals("LEFT_TO_RIGHT_EAGER", random.evaluationOrder());
        assertTrue(first.registry().function(new SymbolKey("math:abs")).isPresent());
        assertTrue(first.registry().function(new SymbolKey("math:pi")).isEmpty());

        var betterCombatAnimation = first.manifest().stream()
            .filter(entry -> entry.stableId().equals(HostSymbol.CTRL_BCOMBAT_ATTACK_ANIMATION.stableId())).findFirst()
            .orElseThrow();
        assertEquals("builtin:string", betterCombatAnimation.resultType());
        assertTrue(betterCombatAnimation.javaMember().contains("MolangBindings"));

        var playSound = first.manifest().stream()
            .filter(entry -> entry.stableId().equals(HostSymbol.YSM_PLAY_SOUND.stableId()))
            .filter(entry -> entry.argumentTypes().get(0).equals(BuiltinType.F32.stableId())).findFirst().orElseThrow();
        assertEquals(List.of(BuiltinType.F32.stableId(), BuiltinType.STRING.stableId(), BuiltinType.F32.stableId(),
            BuiltinType.F32.stableId(), BuiltinType.F32.stableId()), playSound.argumentTypes());

    }

    @Test
    void optionalAvailabilityAndLimitsChangeExactIdentity() {
        var absent = ExpressionHost.create(HostAvailability.none());
        var present = ExpressionHost.create(HostAvailability.all());
        var changedLimits = ExpressionHost.create(HostAvailability.none(), new ExecutionLimits(11, 12, 13, 14, 15));

        var optional = new SymbolKey(HostSymbol.YSM_HAS_ANY_CURIOS.stableId());
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

    private static void assertManifestProjection(ExpressionHost host, HostSymbol symbol) {
        var entry = host.manifest().stream().filter(candidate -> candidate.stableId().equals(symbol.stableId()))
            .findFirst().orElseThrow();
        var key = new SymbolKey(symbol.stableId());
        assertEquals(HostSymbol.AccessKind.INVOKE, symbol.accessKind());
        assertEquals("INVOKE", entry.accessKind());

        for (var descriptor : host.registry().function(key).orElseThrow().methods()) {
            entry = host.manifest().stream().filter(candidate -> candidate.stableId().equals(symbol.stableId()))
                .filter(candidate -> candidate.javaMember().startsWith(descriptor.method().toString() + ":"))
                .findFirst().orElseThrow();
            assertEquals(descriptor.callShape().name(), entry.callShape());
            assertEquals(descriptor.minArgs(), entry.minimumArguments());
            assertEquals(descriptor.maxArgs(), entry.maximumArguments());
            assertEquals(descriptor.parameterTypes().stream().map(ValueType::stableId).toList(), entry.argumentTypes());
            assertEquals(descriptor.returnType().stableId(), entry.resultType());
            var javaType = descriptor.method().methodType();
            assertCarrier(descriptor.returnType(), descriptor.nullableReturn(), javaType.returnType());
            assertEquals(MolangContext.class, javaType.parameterType(0));
            int offset = descriptor.callShape() == FunctionCallShape.FIXED ? 1 : 2;
            if (offset == 2)
                assertEquals(int.class, javaType.parameterType(1));
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
        else if (type == BuiltinType.UNSET)
            assertEquals(void.class, actual);
        else
            throw new AssertionError(type);
    }

    private static Map<String, ExpectedFunction> expectedZeroArgumentFunctions() {
        var result = new TreeMap<String, ExpectedFunction>();
        putZeroArgumentFunctions(result, "ctrl:", false, BuiltinType.BOOL, "attacked", "death", "elytra_fly", "fly",
            "idle", "jump", "ladder_down", "ladder_stillness", "ladder_up", "playing_extra_animation", "run", "sleep",
            "sneak", "sneaking", "swim_stand", "swim", "walk");
        putZeroArgumentFunctions(result, "ctrl:", true, BuiltinType.F32, "state_bypass", "state_continue",
            "state_stop");
        putZeroArgumentFunctions(result, "ctrl:", false, BuiltinType.STRING, "bcombat_attack_animation",
            "parcool_state", "slashblade_animation");
        putZeroArgumentFunctions(result, "ctrl:", false, BuiltinType.BOOL, "carryon_is_princess",
            "has_sophisticated_backpack", "tac_hold_gun");
        putZeroArgumentFunctions(result, "ctrl:", true, BuiltinType.F32, "im_delta", "im_pitch", "im_time");
        putZeroArgumentFunctions(result, "global:", true, BuiltinType.F32, "o");
        putZeroArgumentFunctions(result, "ysm:", true, BuiltinType.F32, "armor_value", "elytra_rot_z", "food_level",
            "fps", "ground_speed2", "head_pitch", "head_yaw", "input_horizontal", "input_vertical", "weather", "yya");
        putZeroArgumentFunctions(result, "ysm:", false, BuiltinType.BOOL, "has_boots", "has_chest_plate", "has_elytra",
            "has_helmet", "has_leggings", "has_mainhand", "has_offhand", "in_shield_block_cooldown", "is_close_eyes",
            "is_fishing", "is_open_air", "mainhand_charged_crossbow", "offhand_charged_crossbow",
            "rendering_in_inventory");
        putZeroArgumentFunctions(result, "ysm:", false, BuiltinType.STRING, "shoot_item_id", "texture_name");
        putZeroArgumentFunctions(result, "query:", false, BuiltinType.BOOL, "all_animations_finished",
            "any_animation_finished", "is_eating", "is_in_water", "is_jumping", "is_on_ground", "is_sneaking",
            "is_swimming");
        putZeroArgumentFunctions(result, "query:", true, BuiltinType.F32, "anim_time", "body_y_rotation",
            "cardinal_facing_2d", "eye_target_y_rotation", "ground_speed", "head_x_rotation", "head_y_rotation",
            "health", "life_time", "max_health", "time_stamp", "vertical_speed", "yaw_speed");
        return result;
    }

    private static Map<String, ExpectedFunction> expectedFunctions() {
        var result = new TreeMap<>(expectedZeroArgumentFunctions());
        putFunctions(result,
            optionalFunction(true, 2, 3, List.of(BuiltinType.STRING, BuiltinType.STRING, BuiltinType.BOOL),
                List.of(DefaultValue.ofBoolean(false)), BuiltinType.BOOL),
            "ctrl:armor", "ctrl:hold", "ctrl:swing", "ctrl:use");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET), "ctrl:indicate_reload", "ctrl:reset");
        putFunctions(result, optionalFunction(false, 1, 2, List.of(BuiltinType.STRING, BuiltinType.F32),
            List.of(DefaultValue.ofFloat(0)), BuiltinType.UNSET), "ctrl:set_animation");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET, BuiltinType.F32),
            "ctrl:set_beginning_transition_length");
        putFunctions(result, fixedFunction(true, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
            "math:random_integer");
        putFunctions(result, optionalFunction(true, 2, 3, List.of(BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
            List.of(DefaultValue.ofFloat(0)), BuiltinType.F32), "math:random");
        putFunctions(result, fixedFunction(false, ExpressionHost.VECTOR, BuiltinType.STRING), "ysm:bone_pivot_abs",
            "ysm:bone_rot");
        putFunctions(result,
            variadicFunction(true, 1, 255, List.of(BuiltinType.STRING), BuiltinType.STRING, BuiltinType.BOOL),
            "ysm:has_any_curios");
        putFunctions(result, variadicFunction(false, 1, 255, List.of(), BuiltinType.F32, BuiltinType.BOOL),
            "ysm:keyboard");
        putFunctions(result, fixedFunction(false, BuiltinType.STRING, BuiltinType.STRING), "ysm:mod_version");
        putFunctions(result,
            variadicFunction(false, 1, 255, List.of(BuiltinType.STRING), BuiltinType.F32, BuiltinType.UNSET),
            "ysm:particle", "ysm:abs_particle");
        for (var id : List.of(BuiltinType.F32, BuiltinType.STRING)) {
            putFunctions(result, optionalFunction(false, 2, 5,
                List.of(id, BuiltinType.STRING, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
                List.of(DefaultValue.ofFloat(0), DefaultValue.ofFloat(1), DefaultValue.ofFloat(1)), BuiltinType.UNSET),
                "ysm:play_sound");
            putFunctions(result, optionalFunction(false, 1, 2, List.of(id, BuiltinType.BOOL),
                List.of(DefaultValue.ofBoolean(false)), BuiltinType.UNSET), "ysm:stop_sound");
        }
        putFunctions(result, variadicFunction(true, 2, 255, List.of(BuiltinType.STRING, BuiltinType.F32),
            BuiltinType.F32, BuiltinType.F32), "ysm:first_order", "ysm:second_order");
        putFunctions(result, optionalFunction(false, 0, 1, List.of(BuiltinType.BOOL),
            List.of(DefaultValue.ofBoolean(false)), BuiltinType.UNSET), "ysm:stop_all_sounds");
        putFunctions(result, variadicFunction(false, 0, 16, List.of(), BuiltinType.F32, BuiltinType.UNSET), "ysm:sync");
        putFunctions(result,
            variadicFunction(false, 1, 255, List.of(BuiltinType.F32), BuiltinType.F32, BuiltinType.UNSET), "ysm:defer");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET, BuiltinType.STRING, BuiltinType.F32,
            BuiltinType.F32, BuiltinType.F32), "ysm:bone_color");
        putFunctions(result, fixedFunction(false, BuiltinType.UNSET, BuiltinType.STRING, BuiltinType.F32),
            "ysm:bone_glow", "ysm:bone_transparency");
        putFunctions(result,
            variadicFunction(true, 2, 255, List.of(BuiltinType.STRING), BuiltinType.STRING, BuiltinType.BOOL),
            "query:is_item_name_any");
        putFunctions(result, fixedFunction(true, BuiltinType.F32, BuiltinType.F32), "query:position",
            "query:position_delta");
        putFunctions(result, variadicFunction(true, 4, 255, List.of(BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
            BuiltinType.STRING, BuiltinType.BOOL), "query:relative_block_has_any_tag");
        putFunctions(result, fixedFunction(true, BuiltinType.F32, BuiltinType.F32), "math:abs", "math:cos", "math:exp",
            "math:floor", "math:min_angle", "math:round", "math:sin", "math:sqrt");
        putFunctions(result, fixedFunction(true, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32), "math:max",
            "math:min", "math:mod", "math:pow");
        putFunctions(result, fixedFunction(true, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32, BuiltinType.F32),
            "math:clamp", "math:lerp");
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
