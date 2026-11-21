package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.expression.host.ComponentScope;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.format.schema.baked.asset.BakedAnimationSet;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import dev.semaweave.ProgramKey;
import dev.semaweave.RoamingRole;
import dev.semaweave.SymbolKey;
import dev.semaweave.compilation.CompilerOptions;
import dev.semaweave.compilation.DiagnosticLocation;
import dev.semaweave.interpreter.InterpreterDiagnostics;
import dev.semaweave.interpreter.InterpreterEnvironment;
import dev.semaweave.interpreter.InterpreterFunctions;
import dev.semaweave.interpreter.InterpreterInstance;
import dev.semaweave.interpreter.InterpreterProgram;
import dev.semaweave.interpreter.InterpreterRoamingState;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Frozen source/declaration generation; only AnimationStore owns the bound program table. */
final class InterpretedExpressionPrograms implements AutoCloseable {
    private final Object generation = new Object();
    private final ExpressionHost host;
    private final ExpressionBinding binding;
    private final BakedAnimationSet inputs;
    private final ModelProgramInventory.Prepared sources;
    private final Map<ModelProgramInventory.SemanticLocation, Integer> locations;
    private final Map<ProgramKey, Integer> keys;
    private final Map<SymbolKey, Integer> functions;
    private final InterpreterEnvironment<MolangContext> environment;
    private final java.util.concurrent.ConcurrentMap<Integer, InterpreterProgram> programs =
        new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int preparationCount;
    private long preparationNanos;

    InterpretedExpressionPrograms(ExpressionHost host, ExpressionBinding binding, BakedAnimationSet inputs,
        ModelProgramInventory.Prepared sources, CompilerOptions options) {
        this.host = host;
        this.binding = binding;
        this.inputs = inputs;
        this.sources = sources;
        var locations = new HashMap<ModelProgramInventory.SemanticLocation, Integer>();
        var keys = new HashMap<ProgramKey, Integer>();
        for (var descriptor : inputs.descriptors()) {
            locations.put(descriptor.location(), descriptor.id());
            keys.put(descriptor.key(), descriptor.id());
        }
        this.locations = Map.copyOf(locations);
        this.keys = Map.copyOf(keys);
        this.functions = sources.callableInputs();
        environment = new InterpreterEnvironment<>(host.registry(), binding.bindingPlan(), options,
            host.compilationPolicy().numericPolicy().nonFinite(), new InterpreterFunctions() {
                @Override
                public Reference bind(ProgramKey caller, SymbolKey name, int argumentCount) {
                    var id = functions.get(name);
                    if (id == null)
                        return new Missing();
                    var descriptor = inputs.descriptors().get(id);
                    return new Known(descriptor.key(),
                        new DiagnosticLocation(
                            descriptor.location().targetId() + '/' + descriptor.location().location()),
                        Optional.empty());
                }

                @Override
                public Result acquire(Known reference) throws Exception {
                    return new Available(program(Objects.requireNonNull(keys.get(reference.key()))));
                }
            });
    }

    Object generation() {
        return generation;
    }

    int boundProgramCount() {
        return programs.size();
    }

    ProgramBinding.Interpreted reference(ModelProgramInventory.SemanticLocation location) {
        var id = locations.get(location);
        if (id == null)
            throw new IllegalArgumentException("Unknown interpreter input at " + location);
        return new ProgramBinding.Interpreted(generation, id, location.role().shape());
    }

    private InterpreterProgram program(int id) throws Exception {
        var cached = programs.get(id);
        if (cached != null)
            return cached;
        synchronized (programs) {
            cached = programs.get(id);
            if (cached != null)
                return cached;
            boolean observed = ResourceDiagnostics.isEnabled();
            long started = observed ? System.nanoTime() : 0;
            try {
                // Installation already paired this retained source with the admitted animation input descriptors.
                cached = environment.prepare(sources.interpreterSource(id), () -> false);
                programs.put(id, cached);
                return cached;
            } finally {
                if (observed) {
                    preparationNanos += System.nanoTime() - started;
                    preparationCount++;
                }
            }
        }
    }

    void reportPreparation() {
        if (preparationCount == 0)
            return;
        final int count;
        final long nanos;
        // Preparation already serializes on this collection; reporting consumes only completed observations.
        synchronized (programs) {
            count = preparationCount;
            nanos = preparationNanos;
            preparationCount = 0;
            preparationNanos = 0;
        }
        if (count > 0 && ResourceDiagnostics.isEnabled())
            ResourceDiagnostics.log("Molang interpreter rebind: target={} programs={} elapsedMs={}",
                inputs.descriptors().get(0).location().targetId(), count, nanos / 1_000_000.0);
    }

    InterpreterRuntimeUse open(ComponentScope components, RoamingRole role) {
        var roaming = environment.newRoamingState(role);
        var use = new Use(this, roaming);
        use.instance = environment.newInstance(components.providers(host, binding.bindingPlan()), roaming,
            InterpreterDiagnostics.NONE);
        return use;
    }

    private static final class Use implements InterpreterRuntimeUse {
        private InterpretedExpressionPrograms source;
        private InterpreterInstance<MolangContext> instance;
        private InterpreterRoamingState roaming;

        private Use(InterpretedExpressionPrograms source, InterpreterRoamingState roaming) {
            this.source = source;
            this.roaming = roaming;
        }

        @Override
        public InterpreterInstance<MolangContext> instance() {
            return Objects.requireNonNull(instance, "Interpreter use is closed");
        }

        @Override
        public InterpreterEnvironment<MolangContext> environment() {
            return Objects.requireNonNull(source, "Interpreter use is closed").environment;
        }

        @Override
        public InterpreterProgram program(int sourceId) throws Exception {
            return Objects.requireNonNull(source, "Interpreter use is closed").program(sourceId);
        }

        @Override
        public InterpreterRoamingState roamingState() {
            return Objects.requireNonNull(roaming, "Interpreter use is closed");
        }

        @Override
        public void close() {
            if (instance == null)
                return;
            try {
                instance.close();
            } finally {
                instance = null;
                var previous = source;
                source = null;
                roaming = null;
                previous.reportPreparation();
            }
        }
    }

    @Override
    public void close() {
        try {
            programs.clear();
        } finally {
            environment.close();
        }
    }
}
