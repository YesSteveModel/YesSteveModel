package com.elfmcys.ysm.expression.oracle;

import com.elfmcys.ysm.expression.host.*;
import dev.semaweave.BuiltinType;
import dev.semaweave.RuntimeValue;
import dev.semaweave.RuntimeValues;
import dev.semaweave.SymbolKey;
import dev.semaweave.ValueTag;
import dev.semaweave.ValueType;
import dev.semaweave.registry.FunctionCallShape;
import dev.semaweave.registry.FunctionDescriptor;
import dev.semaweave.registry.InvocationKind;
import dev.semaweave.registry.JavaMemberBinding;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binding-seam oracle for the argument, value-label and effect-payload contract of all 31 migrated callables (Frozen
 * O-01, O-03; A-02).
 *
 * <p>
 * It exercises the frozen registry's declared Java binding with exactly the ABI the generated code uses — fixed
 * carriers, explicit {@code argc} for optional shapes and an argument view for variadic shapes. Omitted optional slots
 * are filled with distinctive sentinel values, so a binding that replaced an omission with a default would be caught.
 *
 * <p>
 * Evidence boundary: engine-side padding of omitted optional arguments and scalar conversions of variadic arguments
 * happen inside generated code and are recorded as deferred boundaries in the frozen dataset; T03 owns that execution
 * evidence.
 */
final class BuiltinCallableBindingOracleTest {
    private static final BuiltinCapabilityOracle ORACLE = BuiltinCapabilityOracle.load();
    private static final ExpressionHost HOST = ExpressionHost.create(HostAvailability.all());
    private static final EntityView ENTITY_VIEW = new EntityView() {
        /**
         * No borrowed entity: the binding oracle drives the declaration ABI, so an entity-owned business decision takes
         * its "not applicable" path exactly as it would for a host that has no entity.
         */
        @Override
        public net.minecraft.world.entity.Entity entity() {
            return null;
        }

        /**
         * A capability that implements its own business reads the borrowed inputs; this oracle supplies the same
         * production source the real host does, so a business-owned capability is exercised rather than skipped.
         */
        @Override
        public InvocationInputs productionInputs() {
            return PRODUCTION_INPUTS;
        }
    };
    private static final InvocationInputs PRODUCTION_INPUTS = new InvocationInputs() {
        /** The same "no borrowed entity" state the surrounding view reports. */
        @Override
        public net.minecraft.world.entity.Entity entity() {
            return null;
        }

        @Override
        public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {}
    };
    private static final RenderView RENDER_VIEW = new RenderView() {};
    private static final InvocationProvenance PROVENANCE = new InvocationProvenance("t01-oracle", "binding", 1, 2, 3);

    @Test
    void everyCallableBindingReceivesExactlyTheAuthoredArguments() throws Throwable {
        for (var capability : ORACLE.invokeCapabilities()) {
            if (capability.stableId().equals(HostSymbol.QUERY_POSITION.stableId())
                || capability.stableId().equals(HostSymbol.QUERY_POSITION_DELTA.stableId())) {
                continue;
            }
            for (var count : List.of(capability.minimum(), capability.maximum() == 255
                ? Math.min(capability.maximum(), capability.minimum() + 1) : capability.maximum())) {
                var expected = authoredArguments(capability, count);
                var descriptor = descriptor(capability.sourceSpelling(), expected);
                assertEquals(capability.minimum(), descriptor.minArgs(), capability.label());
                assertEquals(capability.maximum(), descriptor.maxArgs(), capability.label());
                if (!capability.staged()) {
                    assertEquals(InvocationKind.STATIC, descriptor.method().invocationKind(), capability.label());
                    assertTrue(descriptor.method().providerId().isEmpty(), capability.label());
                    continue;
                }
                var recorder = new OracleRecorder();
                var context = logical(recorder);
                EffectTransaction.execute(context, recorder,
                    invocation -> invoke(capability, invocation, expected, count));

                var commands = recorder.committedCommands();
                assertEquals(1, commands.size(), capability.label() + " count " + count);
                assertEquals(padded(capability.sourceSpelling(), expected), commandArguments(commands.get(0)),
                    capability.label() + " count " + count + " must not receive a padded default");
            }
        }
    }

    @Test
    void soundIdsDeferValuesAndMissingVersionKeepTheirLabels() throws Throwable {
        var numeric = new OracleRecorder();
        invokePlain("ysm.play_sound", List.of(RuntimeValues.ofFloat(1), RuntimeValues.ofString("o")), numeric);
        assertEquals(
            List.of(RuntimeValues.ofFloat(1), RuntimeValues.ofString("o"), RuntimeValues.ofFloat(0),
                RuntimeValues.ofFloat(1), RuntimeValues.ofFloat(1)),
            commandArguments(numeric.committedCommands().get(0)));
        assertEquals(new ModelSoundId.Numeric(1),
            assertInstanceOf(EffectCommand.Sound.class, numeric.committedCommands().get(0)).soundId());
        assertEquals(HostSymbol.YSM_PLAY_SOUND, ((EffectCommand.Sound)numeric.committedCommands().get(0)).action());

        var string = new OracleRecorder();
        invokePlain("ysm.play_sound", List.of(RuntimeValues.ofString("a"), RuntimeValues.ofString("o")), string);
        assertEquals(
            List.of(RuntimeValues.ofString("a"), RuntimeValues.ofString("o"), RuntimeValues.ofFloat(0),
                RuntimeValues.ofFloat(1), RuntimeValues.ofFloat(1)),
            commandArguments(string.committedCommands().get(0)),
            "a string sound id keeps the STRING tag instead of being scalarised");
        assertEquals(new ModelSoundId.StringKey("a"),
            assertInstanceOf(EffectCommand.Sound.class, string.committedCommands().get(0)).soundId());

        var defer = new OracleRecorder();
        invokePlain("ysm.defer", List.of(RuntimeValues.ofFloat(2.5f), RuntimeValues.ofFloat(1)), defer);
        assertEquals(List.of(2.5f, 1.0f),
            assertInstanceOf(EffectCommand.Deferred.class, defer.committedCommands().get(0)).arguments(),
            "defer keeps argument order and values");

        var missing = new OracleRecorder();
        var missingResult = invokePlain("ysm.mod_version", List.of(RuntimeValues.ofString("x")), missing);
        assertEquals("", missingResult.asString(), "an absent mod version uses the canonical empty String carrier");

        var position = new OracleRecorder();
        assertEquals(ValueTag.UNSET, invokePlain("query.position", List.of(RuntimeValues.ofFloat(3)), position).tag());

        var delta = invokePlain("query.position_delta", List.of(RuntimeValues.ofFloat(0)), new OracleRecorder());
        assertEquals(0.0f, delta.asFloat());
    }

    @Test
    void nonFiniteDeferArgumentsAreRejectedAtTheStagingBoundary() {
        var recorder = new OracleRecorder();
        var context = logical(recorder);
        var failure = assertThrows(Throwable.class, () -> EffectTransaction.execute(context, recorder,
            invocation -> invokePlain("ysm.defer", List.of(RuntimeValues.ofFloat(Float.NaN)), recorder)));
        assertTrue(String.valueOf(failure.getMessage()).contains("finite"), failure::toString);
        assertEquals(List.of(), recorder.committedCommands());
        assertEquals(EffectBatch.State.DISCARDED, context.effects().orElseThrow().state());
    }

    @Test
    void optionalAndVariadicBindingsRejectMismatchedArityShapes() throws Throwable {
        var optional = descriptor("ysm.play_sound", List.of(RuntimeValues.ofFloat(1)));
        assertEquals(FunctionCallShape.OPTIONAL, optional.callShape());
        assertEquals(2, optional.minArgs());
        assertEquals(5, optional.maxArgs());
        var variadic = descriptor("ysm.defer", List.of());
        assertEquals(FunctionCallShape.VARIADIC, variadic.callShape());
        assertEquals(float[].class, variadic.method().methodType().lastParameterType());
    }

    @Test
    void vectorCarrierAndMemberBindingsAreDeclaredAndNotScalarised() {
        assertTrue(HOST.registry().types().containsKey(ExpressionHost.VECTOR.stableId()));
        for (var member : List.of("vectorX", "vectorY", "vectorZ")) {
            var expected = new JavaMemberBinding(HostBindings.class, member,
                MethodType.methodType(float.class, MolangVector.class), InvocationKind.STATIC, Optional.empty());
            assertTrue(HOST.registry().allowedMembers().contains(expected),
                member + " must stay a declared vector member binding");
        }
        var vector = new MolangVector(1.5f, 2.5f, 3.5f);
        assertEquals(1.5f, HostBindings.vectorX(vector));
        assertEquals(2.5f, HostBindings.vectorY(vector));
        assertEquals(3.5f, HostBindings.vectorZ(vector));
        assertEquals(vector, HostBindings.copyVector(vector));
    }

    private static RuntimeValue invokePlain(String spelling, List<RuntimeValue> authored, OracleRecorder recorder)
        throws Throwable {
        var capability = ORACLE.require(spelling);
        var context = logical(recorder);
        var result = EffectTransaction.execute(context, recorder,
            invocation -> invoke(capability, invocation, authored, authored.size()));
        if (result == null)
            return RuntimeValues.unset();
        if (result instanceof Float value)
            return RuntimeValues.ofFloat(value);
        if (result instanceof Boolean value)
            return RuntimeValues.ofBoolean(value);
        if (result instanceof String value)
            return RuntimeValues.ofString(value);
        throw new AssertionError("unexpected carrier " + result.getClass());
    }

    private static Object invoke(BuiltinCapabilityOracle.Capability capability, MolangContext<Entity> context,
        List<RuntimeValue> authored, int argc) throws Throwable {
        return invoke(descriptor(capability.sourceSpelling(), authored), context, authored, argc);
    }

    private static Object invoke(FunctionDescriptor<MolangContext> descriptor, MolangContext<Entity> context,
        List<RuntimeValue> authored, int argc) throws Throwable {
        var method = descriptor.method();
        // Built-in capabilities are declared as annotated static methods, so the frozen registry
        // binds them statically with no provider instance in the path.
        var handle = bind(method);
        var arguments = new ArrayList<Object>();
        arguments.add(context);
        if (descriptor.callShape() != FunctionCallShape.FIXED)
            arguments.add(argc);
        var padded = padded(descriptor.key().canonical(), authored);
        int offset = descriptor.callShape() == FunctionCallShape.FIXED ? 1 : 2;
        for (int i = 0; i < descriptor.parameterTypes().size(); i++)
            arguments.add(carrier(method.methodType().parameterType(offset + i), padded.get(i)));
        if (descriptor.callShape() == FunctionCallShape.VARIADIC) {
            var element = descriptor.variadicElementType().orElseThrow();
            Class<?> component = method.methodType().lastParameterType().getComponentType();
            Object tail =
                java.lang.reflect.Array.newInstance(component, authored.size() - descriptor.parameterTypes().size());
            for (int i = descriptor.parameterTypes().size(); i < authored.size(); i++)
                java.lang.reflect.Array.set(tail, i - descriptor.parameterTypes().size(),
                    carrier(component, authored.get(i)));
            arguments.add(tail);
        }
        context.bindExpression(PROVENANCE.programLocation(), PROVENANCE.programId());
        context.beginTopLevel(true);
        try {
            return handle.invokeWithArguments(arguments);
        } finally {
            context.endTopLevel();
            context.releaseExpression();
        }
    }

    /** Binds the declared method exactly as the frozen registry declares it. */
    private static java.lang.invoke.MethodHandle bind(JavaMemberBinding method)
        throws NoSuchMethodException, IllegalAccessException {
        var lookup = MethodHandles.publicLookup();
        if (method.invocationKind() == InvocationKind.STATIC) {
            return lookup.findStatic(method.owner(), method.methodName(), method.methodType());
        }
        return lookup.findVirtual(method.owner(), method.methodName(), method.methodType())
            .bindTo(providerInstance(method));
    }

    private static Object providerInstance(JavaMemberBinding method) {
        return TestComponentHolders.provider(HOST, method);
    }

    @SuppressWarnings("unchecked")
    private static FunctionDescriptor<MolangContext> descriptor(String spelling, List<RuntimeValue> authored) {
        var methods =
            HOST.registry().function(new SymbolKey(ORACLE.require(spelling).stableId())).orElseThrow().methods();
        if (methods.size() == 1)
            return methods.get(0);
        var first = authored.get(0).tag() == ValueTag.STRING ? BuiltinType.STRING : BuiltinType.F32;
        var candidates = methods.stream().filter(method -> method.parameterTypes().get(0) == first).toList();
        assertEquals(1, candidates.size(), spelling);
        return candidates.get(0);
    }

    private static Object carrier(Class<?> type, RuntimeValue value) {
        if (type == float.class)
            return value.asFloat();
        if (type == int.class)
            return (int)value.asFloat();
        if (type == boolean.class)
            return value.asBoolean();
        if (type == String.class)
            return value.asString();
        throw new AssertionError(type);
    }

    private static List<RuntimeValue> padded(String spelling, List<RuntimeValue> authored) {
        var result = new ArrayList<>(authored);
        if (spelling.equals("ysm.play_sound") || spelling.equals("ysm:play_sound")) {
            while (result.size() < 5)
                result.add(RuntimeValues.ofFloat(result.size() == 2 ? 0 : 1));
        } else if (spelling.equals("ctrl.set_animation") || spelling.equals("ctrl:set_animation")) {
            if (result.size() < 2)
                result.add(RuntimeValues.ofFloat(0));
        } else if (spelling.endsWith("stop_all_sounds") && result.isEmpty())
            result.add(RuntimeValues.ofBoolean(false));
        else if (spelling.endsWith("stop_sound") && result.size() == 1)
            result.add(RuntimeValues.ofBoolean(false));
        return List.copyOf(result);
    }

    private static Object sentinel(ValueType type) {
        if (type == BuiltinType.F32)
            return -999.0f;
        if (type == BuiltinType.BOOL)
            return true;
        if (type == BuiltinType.STRING)
            return "<default>";
        return RuntimeValues.ofFloat(-999);
    }

    private static List<RuntimeValue> authoredArguments(BuiltinCapabilityOracle.Capability capability, int count) {
        var result = new ArrayList<RuntimeValue>(count);
        var signature = BuiltinStaticOracle.signature(capability.sourceSpelling());
        for (var index = 0; index < count; index++) {
            var type = index < signature.fixed().size() ? signature.fixed().get(index) : signature.variadic();
            result.add(authored(type, index));
        }
        return List.copyOf(result);
    }

    private static RuntimeValue authored(BuiltinStaticOracle.Type type, int index) {
        return switch (type) {
            case STRING -> RuntimeValues.ofString("s" + index);
            case BOOL -> RuntimeValues.ofBoolean(index % 2 == 0);
            case SOUND_ID -> RuntimeValues.ofFloat(10 + index);
            case F32 -> RuntimeValues.ofFloat(10 + index);
            default -> throw new IllegalArgumentException("invalid argument type " + type);
        };
    }

    private static List<RuntimeValue> commandArguments(EffectCommand command) {
        return OracleCommandValues.arguments(command);
    }

    private static MolangContext<Entity> logical(OracleRecorder recorder) {
        return MolangContext.logical(ENTITY_VIEW, RENDER_VIEW, MolangContext.LogicalPhase.UPDATE, recorder,
            com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.PRODUCER), PROVENANCE,
            new AtomicBoolean()::get, () -> true, EffectBatch.DEFAULT_MAX_COMMANDS);
    }

}
