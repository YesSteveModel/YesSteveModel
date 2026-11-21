package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.aot.BooleanProgram;
import com.elfmcys.ysm.expression.aot.EventProgram;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.FloatExpression;
import com.elfmcys.ysm.expression.aot.InterpreterRuntimeUse;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.expression.aot.TransientExpressionSession;
import com.elfmcys.ysm.expression.aot.UserFunctionProgram;
import com.elfmcys.ysm.expression.aot.VoidProgram;
import com.elfmcys.ysm.expression.host.ComponentScope;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.host.HostLinkage;
import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.expression.host.RoamingState;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.format.schema.file.ChannelReadPhase;
import com.elfmcys.ysm.format.schema.file.ReadPhaseSet;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.file.AnimationControllerFile;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.resource.client.render.AnimationProtoMapper;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import dev.semaweave.CompiledModuleInstance;
import dev.semaweave.ExecutionLimits;
import dev.semaweave.RoamingRole;
import dev.semaweave.RuntimeLinkage;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Owns one exact animation generation; consumers borrow queries and private execution instances. */
public final class AnimationStore implements AutoCloseable {
    private final Object lifecycle = new Object();
    private final Metadata metadata;
    private RenderTargetExpressionModule backend;
    private Index local;
    private Index fallback;
    private Map<String, AnimationControllerFile> controllers = Map.of();
    private Object2ReferenceMap<String,
        com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData> controllerDefinitions =
            Object2ReferenceMaps.emptyMap();
    private Object2ReferenceMap<String, UserFunctionProgram> functions = Object2ReferenceMaps.emptyMap();
    private Object2ReferenceMap<String, List<EventProgram>> eventHandlers = Object2ReferenceMaps.emptyMap();
    private Function<String, ModelResourceFailureGate> failureGates = ignored -> ModelResourceFailureGate.none();
    private int uses;
    private boolean retiring;
    private boolean cleanupClaimed;
    private boolean closed;
    private boolean initialized;

    public AnimationStore() {
        this(null, null, Index.eager(Map.of()), null);
    }

    /** Construction transfers the backend's sole shared close obligation to this store. */
    public AnimationStore(RenderTargetExpressionModule backend, ModelResourcePurpose purpose) {
        this(Objects.requireNonNull(backend), Objects.requireNonNull(purpose), Index.eager(Map.of()), null);
    }

    private AnimationStore(RenderTargetExpressionModule backend, ModelResourcePurpose purpose, Index local,
        Index fallback) {
        this.backend = backend;
        Index.claim(local, fallback);
        this.local = local;
        this.fallback = fallback;
        metadata = backend == null ? null
            : new Metadata(backend.containerId(), backend.targetId(), backend.set(), purpose,
                backend.expressionBinding(), backend.executionGeneration(), backend.generationId(),
                backend.productionLinkage(), backend.executionLimits(), backend.roamingFields(),
                backend.publishedSymbols());
    }

    public static AnimationStore eager(Map<String, Animation> animations) {
        return new AnimationStore(null, null, Index.eager(animations), null);
    }

    public static AnimationStore fromIndices(Index local, Index fallback) {
        return new AnimationStore(null, null, Objects.requireNonNull(local), fallback);
    }

    public static View emptyView() {
        return new AnimationStore().view();
    }

    public static AnimationStore lazy(Iterable<String> names, AnimationSource source, AnimationBinder binder) {
        return lazy(names, source, binder, null);
    }

    public static AnimationStore lazy(Iterable<String> names, AnimationSource source, AnimationBinder binder,
        Index fallback) {
        return lazy(names, source, binder, fallback, ignored -> ModelResourceFailureGate.none());
    }

    public static AnimationStore lazy(Iterable<String> names, AnimationSource source, AnimationBinder binder,
        Index fallback, Function<String, ModelResourceFailureGate> failureGates) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(binder, "binder");
        return lazyBound(names, name -> {
            var proto = source.load(name);
            return proto == null ? null : Objects.requireNonNull(binder.bind(proto), "bound animation");
        }, fallback, failureGates);
    }

    /** Standalone input fixture; production assembly uses Preparation and unowned indices. */
    public static AnimationStore lazyBound(Iterable<String> names, AnimationLoader source, Index fallback,
        Function<String, ModelResourceFailureGate> failureGates) {
        var result = new AnimationStore(null, null, Index.lazy(names, source), fallback);
        result.failureGates = Objects.requireNonNull(failureGates);
        return result;
    }

    public AnimationStore shared() {
        synchronized (local) {
            local.shared = true;
        }
        if (fallback != null)
            synchronized (fallback) {
                fallback.shared = true;
            }
        return this;
    }

    public Metadata metadata() {
        return Objects.requireNonNull(metadata, "This animation store has no execution backend");
    }

    public boolean interpreted() {
        return backend().interpreted();
    }

    public ExpressionBinding expressionBinding() {
        return metadata().expressionBinding();
    }

    public List<String> roamingFields() {
        return metadata().roamingFields();
    }

    public ExpressionArtifactStore.Source source() {
        return backend().source();
    }

    public com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics sharedSemantics(ModelId modelId) {
        return backend().sharedSemantics(modelId);
    }

    public RenderTargetExpressionModule.Diagnostics diagnostics() {
        return backend().diagnostics();
    }

    public Map<String, AnimationControllerFile> controllers() {
        return controllers;
    }

    public Object2ReferenceMap<String, UserFunctionProgram> functions() {
        return functions;
    }

    public Object2ReferenceMap<String, List<EventProgram>> eventHandlers() {
        return eventHandlers;
    }

    public View view() {
        return view(failureGates);
    }

    public View view(Function<String, ModelResourceFailureGate> gates) {
        return new View(this, Objects.requireNonNull(gates), null);
    }

    public Animation get(String name) {
        return view().get(name);
    }

    public Resolution resolve(String name) {
        return view().resolve(name);
    }

    public boolean containsKey(String name) {
        return view().containsKey(name);
    }

    public ObjectSet<String> keySet() {
        return view().keySet();
    }

    public State state(String name) {
        return view().state(name);
    }

    public boolean hasFailures() {
        return view().hasFailures();
    }

    public int size() {
        return view().size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public ReadBatch openReadPhase() throws IOException {
        return view().openReadPhase();
    }

    public Preparation prepare() {
        var use = acquire();
        try {
            return new Preparation(use);
        } catch (Throwable failure) {
            releaseAfterFailure(use, failure);
            throw failure;
        }
    }

    public ComponentScope prepareComponents(AnimatableEntity<?> owner, ComponentScope existing) {
        try (var ignored = prepare()) {
            return backend().prepareComponents(owner, existing);
        }
    }

    public Execution openExecution(ComponentScope components, RoamingRole role) {
        var use = acquire();
        Execution execution = null;
        try {
            execution = new Execution(use, null, null, null);
            var backend = backend();
            if (backend.interpreted()) {
                execution.interpreter = backend.openInterpreter(components, role);
                execution.roaming = new RoamingState.Interpreted(execution.interpreter.roamingState());
            } else {
                var roaming = backend.newRoamingState(role);
                execution.roaming = new RoamingState.Aot(roaming);
                execution.aot = backend.newInstance(components, roaming);
            }
            return execution;
        } catch (Throwable failure) {
            releaseAfterFailure(execution == null ? use : execution, failure);
            throw failure;
        }
    }

    /** Explicit linkage is retained for isolated execution fixtures. */
    public Execution openExecution(RuntimeLinkage<MolangContext> linkage, RoamingRole role) {
        var use = acquire();
        Execution execution = null;
        try {
            execution = new Execution(use, null, null, null);
            var backend = backend();
            if (backend.interpreted())
                throw new IllegalStateException("Interpreter executions require a component scope");
            var roaming = backend.newRoamingState(role);
            execution.roaming = new RoamingState.Aot(roaming);
            execution.aot = backend.newInstance(linkage, roaming);
            return execution;
        } catch (Throwable failure) {
            releaseAfterFailure(execution == null ? use : execution, failure);
            throw failure;
        }
    }

    private RenderTargetExpressionModule backend() {
        return Objects.requireNonNull(backend, "Animation execution resource is unavailable");
    }

    private Use acquire() {
        var use = new Use(this);
        synchronized (lifecycle) {
            if (retiring)
                throw new IllegalStateException("Animation store is retiring");
            uses++;
            return use;
        }
    }

    public int activeUses() {
        synchronized (lifecycle) {
            return uses;
        }
    }

    public boolean isClosed() {
        synchronized (lifecycle) {
            return closed;
        }
    }

    public int boundProgramCount() {
        var current = backend;
        return current == null ? 0 : current.boundInterpreterProgramCount();
    }

    private void release() {
        boolean cleanup;
        synchronized (lifecycle) {
            uses--;
            cleanup = claimCleanup();
        }
        if (cleanup)
            cleanup();
    }

    private boolean claimCleanup() {
        if (!retiring || uses != 0 || cleanupClaimed)
            return false;
        cleanupClaimed = true;
        return true;
    }

    @Override
    public void close() {
        boolean cleanup;
        synchronized (lifecycle) {
            retiring = true;
            cleanup = claimCleanup();
        }
        if (cleanup)
            cleanup();
    }

    private void cleanup() {
        try {
            var retired = backend;
            backend = null;
            local.clear();
            if (fallback != null)
                fallback.clear();
            fallback = null;
            controllers = Map.of();
            controllerDefinitions = Object2ReferenceMaps.emptyMap();
            functions = Object2ReferenceMaps.emptyMap();
            eventHandlers = Object2ReferenceMaps.emptyMap();
            failureGates = ignored -> ModelResourceFailureGate.none();
            if (retired != null)
                retired.close();
        } finally {
            synchronized (lifecycle) {
                closed = true;
            }
        }
    }

    private static void releaseAfterFailure(AutoCloseable use, Throwable failure) {
        try {
            use.close();
        } catch (Throwable cleanup) {
            if (cleanup != failure)
                failure.addSuppressed(cleanup);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime)
            throw runtime;
        if (failure instanceof Error error)
            throw error;
        if (failure != null)
            throw new IllegalStateException("Animation resource cleanup failed", failure);
    }

    public record Metadata(Hash256 containerId, String targetId, ExpressionSet set, ModelResourcePurpose purpose,
        ExpressionBinding expressionBinding, Object executionGeneration, long generationId,
        HostLinkage productionLinkage, ExecutionLimits executionLimits, List<String> roamingFields,
        List<String> publishedSymbols) {
        public Metadata {
            roamingFields = List.copyOf(roamingFields);
            publishedSymbols = List.copyOf(publishedSymbols);
        }

        public ExpressionBinding binding() {
            return expressionBinding;
        }

        public Object environment() {
            return expressionBinding.environment();
        }

        public Object generation() {
            return executionGeneration;
        }
    }

    private static final class Use implements AutoCloseable {
        private AnimationStore store;

        private Use(AnimationStore store) {
            this.store = store;
        }

        private AnimationStore store() {
            return Objects.requireNonNull(store, "Animation use is closed");
        }

        @Override
        public void close() {
            AnimationStore retired;
            synchronized (this) {
                retired = store;
                store = null;
            }
            if (retired != null)
                retired.release();
        }
    }

    /** Assembly belongs to an accepted preparation and may finish after admission is sealed. */
    public static final class Preparation implements AutoCloseable {
        private Use use;

        private Preparation(Use use) {
            this.use = use;
        }

        private RenderTargetExpressionModule backend() {
            return Objects.requireNonNull(use, "Animation preparation is closed").store().backend();
        }

        public Index animations(Iterable<String> files) {
            return backend().animationIndex(files);
        }

        public FloatExpression.Program requireFloatProgram(ModelProgramInventory.SemanticLocation location) {
            return backend().requireFloatProgram(location);
        }

        public BooleanProgram requireBooleanProgram(ModelProgramInventory.SemanticLocation location) {
            return backend().requireBooleanProgram(location);
        }

        public VoidProgram requireVoidProgram(ModelProgramInventory.SemanticLocation location) {
            return backend().requireVoidProgram(location);
        }

        public EventProgram requireEventProgram(ModelProgramInventory.SemanticLocation location) {
            return backend().requireEventProgram(location);
        }

        public UserFunctionProgram requireUserFunctionProgram(ModelProgramInventory.SemanticLocation location) {
            return backend().requireUserFunctionProgram(location);
        }

        public AnimationProtoMapper.ProgramBinder programBinder() {
            var metadata = Objects.requireNonNull(use, "Animation preparation is closed").store().metadata();
            return new AnimationProtoMapper.ProgramBinder() {
                private ModelProgramInventory.SemanticLocation location(String location, ProgramRole role,
                    String discriminator) {
                    return new ModelProgramInventory.SemanticLocation(metadata.targetId(), metadata.set(), location,
                        role, discriminator);
                }

                @Override
                public FloatExpression.Program bindFloat(String location, ProgramRole role, String discriminator) {
                    return requireFloatProgram(location(location, role, discriminator));
                }

                @Override
                public BooleanProgram bindBoolean(String location, ProgramRole role, String discriminator) {
                    return requireBooleanProgram(location(location, role, discriminator));
                }

                @Override
                public VoidProgram bindVoid(String location, ProgramRole role, String discriminator) {
                    return requireVoidProgram(location(location, role, discriminator));
                }
            };
        }

        public void initialize(Index local, Index fallback, Map<String, AnimationControllerFile> controllers,
            Map<String, UserFunctionProgram> functions, Map<String, ? extends List<? extends EventProgram>> handlers) {
            var store = Objects.requireNonNull(use, "Animation preparation is closed").store();
            var copiedHandlers = new Object2ReferenceOpenHashMap<String, List<EventProgram>>();
            handlers.forEach((name, values) -> copiedHandlers.put(name, List.copyOf(values)));
            var copiedControllers = Collections.unmodifiableMap(new LinkedHashMap<>(controllers));
            var copiedFunctions = Object2ReferenceMaps.unmodifiable(new Object2ReferenceOpenHashMap<>(functions));
            var definitions = new Object2ReferenceOpenHashMap<String,
                com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData>();
            copiedControllers.values().forEach(file -> definitions.putAll(file.animationControllers()));
            synchronized (store) {
                if (store.initialized)
                    throw new IllegalStateException("Animation store was already initialized");
                Index.claim(Objects.requireNonNull(local), fallback);
                store.local = local;
                store.fallback = fallback;
                store.controllers = copiedControllers;
                store.controllerDefinitions = Object2ReferenceMaps.unmodifiable(definitions);
                store.functions = copiedFunctions;
                store.eventHandlers = Object2ReferenceMaps.unmodifiable(copiedHandlers);
                store.shared();
                store.initialized = true;
            }
        }

        @Override
        public void close() {
            var retired = use;
            use = null;
            if (retired != null)
                retired.close();
        }
    }

    /** One private instance and one coarse shared-resource protection. */
    public static final class Execution implements AutoCloseable {
        private Use use;
        private CompiledModuleInstance<MolangContext> aot;
        private InterpreterRuntimeUse interpreter;
        private RoamingState roaming;

        private Execution(Use use, CompiledModuleInstance<MolangContext> aot, InterpreterRuntimeUse interpreter,
            RoamingState roaming) {
            this.use = use;
            this.aot = aot;
            this.interpreter = interpreter;
            this.roaming = roaming;
        }

        public AnimationStore store() {
            return Objects.requireNonNull(use, "Animation execution is closed").store();
        }

        public Metadata metadata() {
            return store().metadata();
        }

        public boolean interpreted() {
            store();
            return interpreter != null;
        }

        public CompiledModuleInstance<MolangContext> aotInstance() {
            store();
            return Objects.requireNonNull(aot, "This execution requires the AOT backend");
        }

        public InterpreterRuntimeUse interpreterUse() {
            store();
            return Objects.requireNonNull(interpreter, "This execution requires the interpreter backend");
        }

        public RoamingState roamingState() {
            store();
            return roaming;
        }

        public FloatExpression.Program requireFloatProgram(ModelProgramInventory.SemanticLocation location) {
            return store().backend().requireFloatProgram(location);
        }

        public BooleanProgram requireBooleanProgram(ModelProgramInventory.SemanticLocation location) {
            return store().backend().requireBooleanProgram(location);
        }

        public VoidProgram requireVoidProgram(ModelProgramInventory.SemanticLocation location) {
            return store().backend().requireVoidProgram(location);
        }

        public void reportPreparation() {
            store().backend().reportInterpreterPreparation();
        }

        public ReadBatch openReadPhase(View view) throws IOException {
            if (view.store() != store())
                throw new IllegalArgumentException("Animation view belongs to another store");
            return new ReadBatch(view, null);
        }

        public Work openWork() {
            var use = store().acquire();
            try {
                return new Work(use);
            } catch (Throwable failure) {
                releaseAfterFailure(use, failure);
                throw failure;
            }
        }

        @Override
        public void close() {
            var retired = use;
            if (retired == null)
                return;
            use = null;
            var instance = aot;
            var interpreted = interpreter;
            aot = null;
            interpreter = null;
            roaming = null;
            try {
                if (instance != null)
                    instance.close();
                if (interpreted != null)
                    interpreted.close();
            } catch (Throwable failure) {
                releaseAfterFailure(retired, failure);
                throw failure;
            }
            retired.close();
        }
    }

    /** Independent work can outlive the installation that accepted it. */
    public static final class Work implements AutoCloseable {
        private Use use;

        private Work(Use use) {
            this.use = use;
        }

        public TransientExpressionSession.CompilationInputs
            transientInputs(TransientExpressionSession.Privilege privilege) {
            return Objects.requireNonNull(use, "Animation work is closed").store().backend()
                .prepareTransientInputs(privilege);
        }

        @Override
        public void close() {
            var retired = use;
            use = null;
            if (retired != null)
                retired.close();
        }
    }

    /** A query projection has no close authority or independent retirement state. */
    public static final class View {
        private final AnimationStore store;
        private final Function<String, ModelResourceFailureGate> gates;
        private final ReadBatch batch;

        private View(AnimationStore store, Function<String, ModelResourceFailureGate> gates, ReadBatch batch) {
            this.store = batch == null ? store : null;
            this.gates = batch == null ? gates : null;
            this.batch = batch;
        }

        private AnimationStore store() {
            return batch == null ? store : batch.source();
        }

        private Function<String, ModelResourceFailureGate> gates() {
            return batch == null ? gates : batch.gates();
        }

        public Object2ReferenceMap<String, com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData>
            controllers() {
            return store().controllerDefinitions;
        }

        public Object2ReferenceMap<String, UserFunctionProgram> functions() {
            return store().functions;
        }

        public Object2ReferenceMap<String, List<EventProgram>> eventHandlers() {
            return store().eventHandlers;
        }

        public Animation get(String name) {
            return resolve(name).animation();
        }

        public Animation getOrDefault(String name, Animation defaultValue) {
            var value = get(name);
            return value == null ? defaultValue : value;
        }

        public Resolution resolve(String name) {
            Objects.requireNonNull(name, "animation name");
            if (batch != null)
                return batch.resolve(name);
            try (var read = openReadPhase()) {
                return read.animations().resolve(name);
            } catch (IOException failure) {
                throw new ReadBatchCloseFailure(failure);
            }
        }

        public boolean containsKey(String name) {
            var store = store();
            return store.local.contains(name) || store.fallback != null && store.fallback.contains(name);
        }

        public ObjectSet<String> keySet() {
            var store = store();
            var names = new ObjectOpenHashSet<>(store.local.names());
            if (store.fallback != null)
                names.addAll(store.fallback.names());
            return ObjectSets.unmodifiable(names);
        }

        public State state(String name) {
            return store().local.state(name);
        }

        public boolean hasFailures() {
            return store().local.hasFailures();
        }

        public int size() {
            return store().local.names().size();
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        public ReadBatch openReadPhase() throws IOException {
            var store = store();
            var use = store.acquire();
            try {
                return new ReadBatch(this, use);
            } catch (Throwable failure) {
                releaseAfterFailure(use, failure);
                throw failure;
            }
        }
    }

    /** The read stage and entered callbacks jointly own the batch's one protection. */
    public static final class ReadBatch implements AutoCloseable {
        private final ReadPhaseSet phases = new ReadPhaseSet();
        private final View animations;
        private final ReadContext local;
        private final ReadContext fallback;
        private AnimationStore source;
        private Function<String, ModelResourceFailureGate> gates;
        private Use use;
        private int callbacks;
        private boolean sealed;

        private ReadBatch(View view, Use use) {
            this.use = use;
            source = view.store();
            gates = view.gates();
            animations = new View(null, null, this);
            local = new ReadContext(source.local.source, phases);
            fallback = source.fallback == null ? null : new ReadContext(source.fallback.source, phases);
        }

        public View animations() {
            return animations;
        }

        public View view() {
            return animations;
        }

        private synchronized AnimationStore source() {
            if (sealed)
                throw new IllegalStateException("Animation read batch is closed");
            return source;
        }

        private synchronized Function<String, ModelResourceFailureGate> gates() {
            if (sealed)
                throw new IllegalStateException("Animation read batch is closed");
            return gates;
        }

        private Resolution resolve(String name) {
            final AnimationStore store;
            final Function<String, ModelResourceFailureGate> failureGates;
            synchronized (this) {
                if (sealed)
                    throw new IllegalStateException("Animation read batch is closed");
                callbacks++;
                store = source;
                failureGates = gates;
            }
            Throwable failure = null;
            try {
                var gate = Objects.requireNonNull(failureGates.apply(name), "failure gate");
                if (gate.failure().isPresent())
                    return fallback(store, name).unstable();
                var value = store.local.resolve(name, local, gate);
                if (value == null)
                    return fallback(store, name);
                return value.animation() == null ? fallback(store, name).unstable() : value;
            } catch (Throwable original) {
                failure = original;
                throw original;
            } finally {
                boolean close;
                synchronized (this) {
                    callbacks--;
                    close = sealed && callbacks == 0;
                }
                if (close) {
                    if (failure != null)
                        releaseAfterFailure(this::closePhases, failure);
                    else {
                        try {
                            closePhases();
                        } catch (IOException cleanup) {
                            throw new ReadBatchCloseFailure(cleanup);
                        }
                    }
                }
            }
        }

        private Resolution fallback(AnimationStore store, String name) {
            var index = store.fallback;
            if (index == null)
                return new Resolution(null, false, true);
            var result = index.resolve(name, fallback, ModelResourceFailureGate.none());
            return result == null ? new Resolution(null, false, true)
                : new Resolution(result.animation(), result.animation() != null, result.cacheable());
        }

        @Override
        public void close() throws IOException {
            synchronized (this) {
                if (sealed)
                    return;
                sealed = true;
                // Entered callbacks may still open a reader or advance to the fallback phase.
                if (callbacks != 0)
                    return;
            }
            closePhases();
        }

        private void closePhases() throws IOException {
            Throwable failure = null;
            try {
                phases.close();
            } catch (Throwable cleanup) {
                failure = cleanup;
            } finally {
                Use retired;
                synchronized (this) {
                    local.clear();
                    if (fallback != null)
                        fallback.clear();
                    source = null;
                    gates = null;
                    retired = use;
                    use = null;
                }
                if (retired != null) {
                    if (failure == null)
                        retired.close();
                    else
                        releaseAfterFailure(retired, failure);
                }
            }
            if (failure instanceof IOException io)
                throw io;
            rethrow(failure);
        }
    }

    /** An index has no independent lifetime, reader, or execution resource owner. */
    public static final class Index {
        private AnimationLoader source;
        private final Map<String, Slot> slots = new LinkedHashMap<>();
        private final Map<String, Throwable> failures = new HashMap<>();
        private boolean shared;
        private boolean owned;

        private Index(AnimationLoader source) {
            this.source = source;
        }

        private static void claim(Index local, Index fallback) {
            synchronized (Index.class) {
                if (local == fallback || local.owned || fallback != null && fallback.owned)
                    throw new IllegalStateException("Animation index already belongs to a store");
                local.owned = true;
                if (fallback != null)
                    fallback.owned = true;
            }
        }

        public static Index eager(Map<String, Animation> animations) {
            var index = new Index(null);
            animations.forEach((name, animation) -> index.slots.put(Objects.requireNonNull(name),
                new Slot(State.READY, Objects.requireNonNull(animation))));
            return index;
        }

        public static Index lazy(Iterable<String> names, AnimationLoader source) {
            var index = new Index(Objects.requireNonNull(source));
            for (var name : names)
                if (index.slots.put(Objects.requireNonNull(name), new Slot(State.UNLOADED, null)) != null)
                    throw new IllegalArgumentException("Duplicate animation name: " + name);
            return index;
        }

        private synchronized Set<String> names() {
            return Set.copyOf(slots.keySet());
        }

        private synchronized boolean contains(String name) {
            return slots.containsKey(name);
        }

        private synchronized State state(String name) {
            var slot = slots.get(name);
            return slot == null ? null : slot.state();
        }

        private synchronized boolean hasFailures() {
            return slots.values().stream().anyMatch(slot -> slot.state() == State.FAILED);
        }

        private synchronized Resolution resolve(String name, ReadContext context, ModelResourceFailureGate gate) {
            var slot = slots.get(name);
            if (slot == null)
                return null;
            if (slot.state() == State.READY)
                return new Resolution(slot.animation(), false, true);
            if (slot.state() == State.FAILED) {
                var failure = failures.get(name);
                if (failure != null)
                    gate.fail(failure);
                return new Resolution(null, false, false);
            }
            try {
                var value = context.load(name);
                if (value == null)
                    throw new FileNotFoundException("Baked animation payload is missing: " + name);
                failures.remove(name);
                slots.put(name, new Slot(State.READY, value));
                return new Resolution(value, false, true);
            } catch (Exception error) {
                if (error instanceof ReadBatchCloseFailure closeFailure)
                    throw closeFailure;
                if (error instanceof IOException io && (io instanceof ChannelReadPhase.CloseFailure
                    || io.getCause() instanceof ChannelReadPhase.CloseFailure)) {
                    context.abort(io);
                    throw new ReadBatchCloseFailure(io);
                }
                if (error instanceof CancellationException cancelled) {
                    context.abort(cancelled);
                    throw cancelled;
                }
                if (Thread.currentThread().isInterrupted()) {
                    var cancelled = new CancellationException("Animation read was interrupted");
                    cancelled.initCause(error);
                    context.abort(cancelled);
                    throw cancelled;
                }
                context.endFailedPhase(error);
                gate.fail(error);
                failures.put(name, error);
                var deterministic = error instanceof com.elfmcys.ysm.format.AssetLoadException asset
                    ? asset.reason() == com.elfmcys.ysm.format.AssetLoadException.Reason.CONTENT
                    : !(error instanceof IOException);
                slots.put(name, new Slot(shared && !deterministic ? State.UNLOADED : State.FAILED, null));
                return new Resolution(null, false, false);
            }
        }

        private synchronized void clear() {
            source = null;
            slots.clear();
            failures.clear();
        }
    }

    @FunctionalInterface
    public interface AnimationSource {
        AnimationProto load(String animationName) throws Exception;
    }

    @FunctionalInterface
    public interface AnimationBinder {
        Animation bind(AnimationProto animation) throws Exception;
    }

    @FunctionalInterface
    public interface AnimationLoader {
        Animation load(String animationName) throws Exception;

        default AnimationLoader inReadPhase(ReadPhaseSet phases) throws IOException {
            return this;
        }
    }

    private static final class ReadContext {
        private AnimationLoader source;
        private final ReadPhaseSet phases;
        private AnimationLoader reader;
        private long generation = -1;

        private ReadContext(AnimationLoader source, ReadPhaseSet phases) {
            this.source = source;
            this.phases = phases;
        }

        private Animation load(String name) throws Exception {
            var current = phases.generation();
            if (generation != current) {
                reader = source.inReadPhase(phases);
                generation = current;
            }
            return reader.load(name);
        }

        private void clear() {
            source = null;
            reader = null;
        }

        private void endFailedPhase(Exception original) {
            try {
                phases.endPhase();
            } catch (IOException cleanup) {
                cleanup.addSuppressed(original);
                throw new ReadBatchCloseFailure(cleanup);
            }
        }

        private void abort(Throwable original) {
            try {
                phases.close();
            } catch (IOException cleanup) {
                original.addSuppressed(cleanup);
            }
        }
    }

    private static final class ReadBatchCloseFailure extends UncheckedIOException {
        private ReadBatchCloseFailure(IOException cause) {
            super("Failed to end an animation read phase", cause);
        }
    }

    public enum State {
        UNLOADED, READY, FAILED
    }

    public record Resolution(Animation animation, boolean fallback, boolean cacheable) {
        public Resolution unstable() {
            return cacheable ? new Resolution(animation, fallback, false) : this;
        }
    }

    private record Slot(State state, Animation animation) {
    }
}
