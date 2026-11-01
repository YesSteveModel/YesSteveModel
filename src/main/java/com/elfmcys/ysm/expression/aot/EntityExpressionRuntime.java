package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import dev.semaweave.CompiledModuleInstance;
import dev.semaweave.CompiledRoamingState;
import dev.semaweave.ListType;
import dev.semaweave.ProgramArguments;
import dev.semaweave.RuntimeExecutionException;
import dev.semaweave.ExpressionValues;
import dev.semaweave.ProviderReturnContractException;
import dev.semaweave.RoamingPayload;
import dev.semaweave.RoamingRole;
import dev.semaweave.compilation.ResourceGenerationToken;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Entity owner for one exact target-generation instance and its closed event dispatcher. */
public final class EntityExpressionRuntime implements AutoCloseable {
    public static final int MAX_DEFERRED_CAPTURES = 64;

    private static final ListType EVENT_ARGUMENTS = ExpressionHost.EVENT_F32_ARGUMENTS;

    private final Thread mainThread = Thread.currentThread();
    private long nextGeneration;
    private final ArrayDeque<DeferredCapture> deferred = new ArrayDeque<>();
    private State current;
    private ArmPair armPair;
    private ArmPair executionArmPair;
    private java.util.function.BooleanSupplier projectionAvailable = () -> true;
    private final ArrayDeque<Projection> projections = new ArrayDeque<>();
    private boolean evaluating;

    public void replace(RenderTargetExpressionModule module,
        Map<String, ? extends List<? extends EventProgram>> handlers, ComponentScope components) {
        replace(module, handlers, components, RoamingRole.PRODUCER);
    }

    public void replace(RenderTargetExpressionModule module,
        Map<String, ? extends List<? extends EventProgram>> handlers, ComponentScope components,
        RoamingRole roamingRole) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(handlers, "handlers");
        Objects.requireNonNull(components, "components");
        Objects.requireNonNull(roamingRole, "roamingRole");
        try (var prepared = prepare(module, handlers, components, roamingRole)) {
            install(prepared, () -> {
            });
        }
    }

    public PreparedInstallation prepare(RenderTargetExpressionModule module,
        Map<String, ? extends List<? extends EventProgram>> handlers, ComponentScope components, RoamingRole role) {
        assertLifecycleThread();
        return new PreparedInstallation(
            State.create(module, handlers, ++nextGeneration, module.newRoamingState(role), components));
    }

    public void install(PreparedInstallation prepared, Runnable publication) {
        assertLifecycleThread();
        if (evaluating)
            throw new IllegalStateException("cannot replace an evaluating expression owner");
        var candidate = prepared.take();
        var retired = current;
        current = candidate;
        try {
            publication.run();
        } catch (RuntimeException | Error failure) {
            current = retired;
            candidate.close();
            throw failure;
        }
        disconnect(retired);
        publishArmConnection(candidate);
        deferred.clear();
        if (retired != null) {
            try {
                retired.close();
            } catch (RuntimeException | LinkageError failure) {
                YesSteveModel.LOGGER.warn("Previous expression installation cleanup failed", failure);
            }
        }
    }

    public final class PreparedInstallation implements AutoCloseable {
        private State state;

        private PreparedInstallation(State state) {
            this.state = state;
        }

        private State take() {
            var candidate = Objects.requireNonNull(state, "installation already transferred");
            state = null;
            return candidate;
        }

        public void initializeRoaming(RoamingPayload payload) {
            var candidate = Objects.requireNonNull(state, "installation already transferred");
            EntityExpressionRuntime.initializeRoaming(candidate, payload);
        }

        public Installation installation() {
            return new Installation(EntityExpressionRuntime.this,
                Objects.requireNonNull(state, "installation already transferred"));
        }

        public void connectArmTo(EntityExpressionRuntime body) {
            prepareArmConnection(Objects.requireNonNull(state, "installation already transferred"), body);
        }

        @Override
        public void close() {
            if (state != null) {
                state.close();
                state = null;
            }
        }
    }

    void replace(RenderTargetExpressionModule module, Map<String, ? extends List<? extends EventProgram>> handlers,
        dev.semaweave.RuntimeLinkage<MolangContext> linkage) {
        replace(module, handlers, linkage, RoamingRole.PRODUCER);
    }

    void replace(RenderTargetExpressionModule module, Map<String, ? extends List<? extends EventProgram>> handlers,
        dev.semaweave.RuntimeLinkage<MolangContext> linkage, RoamingRole roamingRole) {
        assertLifecycleThread();
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(handlers, "handlers");
        Objects.requireNonNull(linkage, "linkage");
        Objects.requireNonNull(roamingRole, "roamingRole");
        var roamingState = module.newRoamingState(roamingRole);
        var prepared = State.create(module, handlers, ++nextGeneration, roamingState, linkage);
        replace(prepared);
    }

    private void replace(State prepared) {
        assertLifecycleThread();
        try {
            if (evaluating)
                throw new IllegalStateException("cannot replace an evaluating expression owner");
            var retired = current;
            current = prepared;
            disconnect(retired);
            deferred.clear();
            if (retired != null) {
                retired.close();
            }
        } catch (Throwable failure) {
            if (current != prepared) {
                prepared.close();
            }
            throw failure;
        }
    }

    public <T> T observe(InvocationInputs inputs, Body<T> body) {
        return invoke(inputs, MolangContext.LogicalPhase.OBSERVATION, body);
    }

    public <T> T update(InvocationInputs inputs, Body<T> body) {
        return invoke(inputs, MolangContext.LogicalPhase.UPDATE, body);
    }

    public <T> T normalUpdate(InvocationInputs inputs, Body<T> body,
        java.util.function.Consumer<RoamingPayload> localProjection) {
        Objects.requireNonNull(localProjection);
        var evaluation = beginEvaluation(inputs, MolangContext.LogicalPhase.UPDATE);
        evaluation.localProjection = (delta, finalWindow) -> { localProjection.accept(delta); return true; };
        boolean successful = false;
        try {
            drainDeferred(evaluation.state, evaluation);
            T result = runWithComponents(body, evaluation);
            successful = true;
            return result;
        } catch (RuntimeException | Error failure) {
            evaluation.recordFailure(failure);
            throw failure;
        } finally {
            endEvaluation(evaluation, successful);
        }
    }

    public <T> T action(InvocationInputs inputs, Body<T> body) {
        return invoke(inputs, MolangContext.LogicalPhase.ACTION, body);
    }

    /** Component cleanup remains inside the transaction so a failed hook cannot publish effects. */
    public <T> T animationPass(InvocationInputs inputs, boolean persistentActionWindow, Body<T> body,
        java.util.function.Consumer<RoamingPayload> localProjection) {
        if (persistentActionWindow)
            Objects.requireNonNull(localProjection);
        return animationPassProjected(inputs, persistentActionWindow, body,
            (delta, finalWindow) -> { localProjection.accept(delta); return true; });
    }

    public <T> T animationPassProjected(InvocationInputs inputs, boolean persistentActionWindow, Body<T> body,
        ProjectionSink localProjection) {
        if (persistentActionWindow)
            Objects.requireNonNull(localProjection);
        var evaluation = beginEvaluation(inputs, MolangContext.LogicalPhase.UPDATE);
        evaluation.persistentActionWindow = persistentActionWindow;
        if (persistentActionWindow)
            evaluation.localProjection = localProjection;
        boolean successful = false;
        try {
            T result = runBody(body, evaluation);
            successful = true;
            return result;
        } catch (RuntimeException | Error failure) {
            evaluation.recordFailure(failure);
            throw failure;
        } finally {
            endEvaluation(evaluation, successful);
        }
    }

    private <T> T invoke(InvocationInputs inputs, MolangContext.LogicalPhase phase, Body<T> body) {
        Objects.requireNonNull(body, "body");
        var evaluation = beginEvaluation(inputs, phase);
        boolean successful = false;
        try {
            if (phase != MolangContext.LogicalPhase.OBSERVATION)
                drainDeferred(evaluation.state, evaluation);
            T result = runWithComponents(body, evaluation);
            successful = true;
            return result;
        } catch (RuntimeException | Error failure) {
            evaluation.recordFailure(failure);
            throw failure;
        } finally {
            endEvaluation(evaluation, successful);
        }
    }

    private Evaluation beginEvaluation(InvocationInputs inputs, MolangContext.LogicalPhase phase) {
        Objects.requireNonNull(inputs, "inputs");
        try {
            if (evaluating)
                throw new IllegalStateException("an entity expression owner cannot reenter");
            var state = requireCurrent();
            if (!state.accepting)
                throw new IllegalStateException("Expression installation is sealed");
            if (state.subject != null && inputs.productionInputs().entity() != state.subject) {
                throw new IllegalArgumentException("invocation inputs belong to another entity owner");
            }
            var ownerProvenance =
                provenance(state, "<logical-" + phase.name().toLowerCase(java.util.Locale.ROOT) + ">", -1);
            var production = inputs.productionInputs();
            BooleanSupplier eligible = () -> true;
            var ownerContext = phase == MolangContext.LogicalPhase.OBSERVATION
                ? (state.typedSubject
                    ? state.linkage.observation(production, state.roamingState, ownerProvenance, inputs.cancellation(),
                        eligible)
                    : MolangContext.observation(production, production, state.linkage.access(production),
                        state.roamingState, ownerProvenance, inputs.cancellation(), eligible))
                : (state.typedSubject
                    ? state.linkage.logical(production, state.roamingState, phase, ownerProvenance,
                        inputs.cancellation(), eligible, EffectBatch.DEFAULT_MAX_COMMANDS)
                    : MolangContext.logical(production, production, phase, state.linkage.access(production),
                        state.roamingState, ownerProvenance, inputs.cancellation(), eligible,
                        EffectBatch.DEFAULT_MAX_COMMANDS));
            ownerContext.configureLimits(state.module.executionLimits());
            var evaluation = new Evaluation(state, inputs, phase, ownerContext);
            ownerContext.deferredSubmit((request, source) -> {
                if (evaluation.deferredCaptures++ >= MAX_DEFERRED_CAPTURES)
                    throw new IllegalStateException("deferred capture queue exceeds " + MAX_DEFERRED_CAPTURES);
                deferred.addLast(new DeferredCapture(state, evaluation, ownerContext.animationState(),
                    ownerContext.controllerState(), request.arguments(), source));
            });
            evaluating = true;
            return evaluation;
        } catch (Throwable failure) {
            throw failure;
        }
    }

    private void endEvaluation(Evaluation evaluation, boolean successful) {
        Throwable primary = evaluation.failure().orElse(null);
        Throwable closingFailure = null;
        try {
            if (successful && evaluation.failure().isEmpty())
                drainDeferred(evaluation.state, evaluation);
            if (evaluation.phase != MolangContext.LogicalPhase.OBSERVATION) {
                EffectTransaction.finish(evaluation.ownerContext, effectSink(evaluation.state, evaluation.inputs),
                    successful && evaluation.failure().isEmpty(), () -> closeEvaluation(evaluation));
            }
            if (evaluation.localProjection != null && successful && evaluation.failure().isEmpty()
                && evaluation.state.roamingState.role() == RoamingRole.PRODUCER
                && evaluation.state.roamingState.hasDelta()) {
                var delta = evaluation.state.roamingState.delta();
                projections.addLast(new Projection(evaluation.state, delta, evaluation.localProjection, executionArmPair));
            }
        } catch (Throwable failure) {
            closingFailure = failure;
            EffectTransaction.finish(evaluation.ownerContext, evaluation.inputs.effectSink(), false);
        } finally {
            try {
                try {
                    deferred.removeIf(capture -> capture.evaluation == evaluation);
                } catch (Throwable failure) {
                    closingFailure = appendFailure(closingFailure, failure);
                }
                try {
                    closeEvaluation(evaluation);
                } catch (Throwable failure) {
                    closingFailure = appendFailure(closingFailure, failure);
                }
            } finally {
                evaluating = false;
            }
        }
        if (closingFailure != null) {
            if (primary != null)
                appendFailure(primary, closingFailure);
            else if (closingFailure instanceof RuntimeException failure)
                throw failure;
            else if (closingFailure instanceof Error failure)
                throw failure;
            else
                throw new IllegalStateException("expression round cleanup failed", closingFailure);
        }
    }

    private void closeEvaluation(Evaluation evaluation) {
        if (evaluation.closed)
            return;
        evaluation.ownerContext.closeRound();
        evaluation.closed = true;
        evaluating = false;
    }

    private static Throwable appendFailure(@Nullable Throwable primary, Throwable failure) {
        if (primary == null)
            return failure;
        if (primary != failure)
            primary.addSuppressed(failure);
        return primary;
    }

    public void projectionAvailability(BooleanSupplier available) {
        assertLifecycleThread();
        projectionAvailable = Objects.requireNonNull(available);
    }

    public void prepareExecution() {
        assertLifecycleThread();
        executionArmPair = armPair;
    }

    void connectArmTo(EntityExpressionRuntime body) {
        assertLifecycleThread();
        var arm = requireCurrent();
        prepareArmConnection(arm, body);
        publishArmConnection(arm);
    }

    private void prepareArmConnection(State arm, EntityExpressionRuntime body) {
        assertLifecycleThread();
        if (!body.projectionAvailable.getAsBoolean())
            throw new IllegalStateException("arm connection requires a joined body");
        if (arm.armInitializationAttempted)
            throw new IllegalStateException("arm installation already initialized");
        arm.armInitializationAttempted = true;
        if (arm.roamingState.role() != RoamingRole.CONSUMER)
            throw new IllegalStateException("arm must be a roaming consumer");
        var producer = body.current;
        if (producer == null || !producer.accepting || producer.roamingState.role() != RoamingRole.PRODUCER)
            return;
        if (!producer.module.identity().containerId().equals(arm.module.identity().containerId())
            || !producer.module.identity().targetId().equals(arm.module.identity().targetId()))
            return;
        var fields = new java.util.HashSet<>(producer.roamingState.fields());
        fields.retainAll(arm.roamingState.fields());
        var pair = new ArmPair(body, producer, this, arm, java.util.Set.copyOf(fields));
        arm.armConnection = pair;
        arm.roamingState.applyFull(project(producer.roamingState.snapshot(), pair.fields));
    }

    private static void publishArmConnection(State arm) {
        var pair = arm.armConnection;
        if (pair == null)
            return;
        var previous = pair.body.armPair;
        pair.body.armPair = pair;
        if (previous != null)
            previous.consumer.armConnection = null;
    }

    /** Called only after the source get, and again after the invocation's complete drain. */
    public void adoptProjections(boolean finalWindow) {
        assertLifecycleThread();
        Throwable failure = null;
        for (var iterator = projections.iterator(); iterator.hasNext();) {
            var result = iterator.next();
            if (current != result.source || !result.source.accepting) {
                iterator.remove();
                continue;
            }
            if (!result.localDone) {
                try {
                    result.localDone = result.local.adopt(result.delta, finalWindow);
                } catch (RuntimeException | Error next) {
                    result.localDone = true;
                    if (failure == null) failure = next;
                    else if (failure != next) failure.addSuppressed(next);
                }
            }
            var pair = result.pair;
            if (!result.armDone && (pair == null || armPair != pair))
                result.armDone = true;
            if (!result.armDone && pair.arm.projectionAvailable.getAsBoolean()) {
                result.armDone = true;
                try {
                    if (pair.arm.current == pair.consumer && pair.consumer.accepting
                        && pair.consumer.armConnection == pair) {
                        var payload = project(result.delta, pair.fields);
                        pair.consumer.module.validateRoamingPatch(payload);
                        pair.consumer.roamingState.applyPatch(payload);
                    }
                } catch (RuntimeException | Error next) {
                    if (failure == null) failure = next;
                    else if (failure != next) failure.addSuppressed(next);
                }
            }
            if (finalWindow || result.localDone && result.armDone)
                iterator.remove();
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    @FunctionalInterface
    public interface ProjectionSink {
        boolean adopt(RoamingPayload delta, boolean finalWindow);
    }

    private static final class Projection {
        final State source;
        final RoamingPayload delta;
        final ProjectionSink local;
        final ArmPair pair;
        boolean localDone;
        boolean armDone;
        Projection(State source, RoamingPayload delta, ProjectionSink local, ArmPair pair) {
            this.source = source;
            this.delta = delta;
            this.local = local;
            this.pair = pair;
        }
    }

    private static RoamingPayload project(RoamingPayload source, java.util.Set<String> fields) {
        var values = new java.util.TreeMap<String, Float>();
        source.values().forEach((name, value) -> {
            if (fields.contains(name))
                values.put(name, value);
        });
        return new RoamingPayload(values);
    }

    private record ArmPair(EntityExpressionRuntime body, State producer, EntityExpressionRuntime arm, State consumer,
        java.util.Set<String> fields) {
    }

    private void disconnect(State retired) {
        var outgoing = armPair;
        armPair = null;
        executionArmPair = null;
        if (outgoing != null)
            outgoing.consumer.armConnection = null;
        if (retired != null && retired.armConnection != null) {
            var incoming = retired.armConnection;
            retired.armConnection = null;
            if (incoming.body.armPair == incoming)
                incoming.body.armPair = null;
        }
        projections.clear();
    }

    public Optional<Snapshot> snapshot() {
        return sample(java.util.function.Function.identity());
    }

    private void assertLifecycleThread() {
        if (Thread.currentThread() != mainThread || evaluating)
            throw new IllegalStateException("Expression lifecycle requires its idle main thread");
    }

    public void invalidate() {
        assertLifecycleThread();
        if (current != null)
            current.accepting = false;
        projections.clear();
    }

    public <T> Optional<T> sample(java.util.function.Function<Snapshot, T> projection) {
        if (current == null)
            return Optional.empty();
        var state = current;
        return Optional
            .of(projection.apply(new Snapshot(new Installation(this, state), state.roamingState.snapshot())));
    }

    public <T> Optional<T> sampleInstallation(java.util.function.Function<Installation, T> projection) {
        return current == null ? Optional.empty() : Optional.of(projection.apply(new Installation(this, current)));
    }

    public static final class Installation {
        private final EntityExpressionRuntime owner;
        private final State state;

        private Installation(EntityExpressionRuntime owner, State state) {
            this.owner = owner;
            this.state = state;
        }

        public boolean commit(Runnable action) {
            owner.assertLifecycleThread();
            if (owner.current != state || !state.accepting || owner.evaluating)
                return false;
            action.run();
            return true;
        }
    }

    public record Snapshot(Installation installation, RoamingPayload payload) {
    }

    private EffectSink effectSink(State state, InvocationInputs inputs) {
        return inputs.effectSink();
    }

    private void drainDeferred(State state, Evaluation evaluation) {
        drainDeferred(state, evaluation, null, false);
    }

    private void drainDeferred(State state, Evaluation evaluation, AnimationExecutionState animation, boolean exact) {
        var previousAnimation = evaluation.ownerContext.animationState();
        var previousController = evaluation.ownerContext.controllerState();
        var previousOrigin = evaluation.deferredOrigin;
        boolean previousActionWindow = evaluation.persistentActionWindow;
        try {
            while (evaluation.failure().isEmpty()) {
                DeferredCapture selected = null;
                for (var it = deferred.descendingIterator(); it.hasNext();) {
                    var candidate = it.next();
                    if (candidate.state == state && candidate.evaluation == evaluation
                        && (!exact || candidate.animation == animation)) {
                        selected = candidate;
                        it.remove();
                        break;
                    }
                }
                if (selected == null)
                    return;
                evaluation.ownerContext.setAnimationContext(selected.animation);
                evaluation.ownerContext.setControllerContext(selected.controller);
                evaluation.deferredOrigin = selected.source;
                // An admitted capture starts a fresh action entry, including captures from transient calls.
                evaluation.persistentActionWindow = true;
                evaluation.dispatchDeferred(selected.arguments);
            }
        } finally {
            evaluation.ownerContext.setAnimationContext(previousAnimation);
            evaluation.ownerContext.setControllerContext(previousController);
            evaluation.deferredOrigin = previousOrigin;
            evaluation.persistentActionWindow = previousActionWindow;
        }
    }

    private <T> T runWithComponents(Body<T> body, Evaluation evaluation) {
        var production = evaluation.inputs.productionInputs();
        if (!(production instanceof com.elfmcys.ysm.geckolib3.core.processor.MinecraftRuntimeInputs<?> minecraft)
            || minecraft.animationEvent() == null || evaluation.state.components == null)
            return runBody(body, evaluation);
        class ComponentBody implements Runnable {
            T result;
            public void run() {
                result = runBody(body, evaluation);
                drainDeferred(evaluation.state, evaluation);
            }
        }
        var scoped = new ComponentBody();
        evaluation.state.components.evaluate(minecraft.animationEvent(),
            () -> true, scoped);
        return scoped.result;
    }

    private static <T> T runBody(Body<T> body, Evaluation evaluation) {
        try {
            return body.run(evaluation);
        } catch (RuntimeException | Error failure) {
            evaluation.recordFailure(failure);
            throw failure;
        } catch (Throwable failure) {
            evaluation.recordFailure(failure);
            throw new IllegalStateException("expression evaluation body failed", failure);
        }
    }

    public Optional<RenderTargetExpressionModule> module() {
        return current == null ? Optional.empty() : Optional.of(current.module);
    }

    public Optional<CompiledRoamingState> roamingState() {
        return current == null ? Optional.empty() : Optional.of(current.roamingState);
    }

    public void initializeRoaming(RoamingPayload payload) {
        Objects.requireNonNull(payload, "payload");
        var state = requireCurrent();
        initializeRoaming(state, payload);
    }

    private static void initializeRoaming(State state, RoamingPayload payload) {
        if (state.roamingInitialized) {
            throw new IllegalStateException("roaming state is already initialized");
        }
        if (state.roamingState.role() == RoamingRole.CONSUMER) {
            state.roamingState.applyFull(payload);
        } else {
            payload.values().forEach(state.roamingState::write);
            state.roamingState.full();
        }
        state.roamingInitialized = true;
    }

    public boolean roamingInitialized() {
        return current != null && current.roamingInitialized;
    }

    public void applyRoamingFull(RoamingPayload payload) {
        requireCurrent().roamingState.applyFull(Objects.requireNonNull(payload, "payload"));
    }

    public void applyRoamingPatch(RoamingPayload payload) {
        requireCurrent().roamingState.applyPatch(Objects.requireNonNull(payload, "payload"));
    }

    public boolean hasRoamingDelta() {
        return requireCurrent().roamingState.hasDelta();
    }

    public RoamingPayload takeRoamingDelta() {
        return requireCurrent().roamingState.delta();
    }

    public RoamingPayload takeRoamingFull() {
        return requireCurrent().roamingState.full();
    }

    public long generation() {
        return current == null ? 0 : current.entityGeneration;
    }

    public int pendingDeferredCount() {
        return deferred.size();
    }

    public boolean readConfigBoolean(InvocationInputs inputs, long expectedGeneration, ConfigActionRoute route) {
        return observe(inputs, evaluation -> evaluation.readConfigBoolean(expectedGeneration, route));
    }

    public float readConfigFloat(InvocationInputs inputs, long expectedGeneration, ConfigActionRoute route) {
        return observe(inputs, evaluation -> evaluation.readConfigFloat(expectedGeneration, route));
    }

    public void writeConfigBoolean(InvocationInputs inputs, long expectedGeneration, ConfigActionRoute route,
        boolean value) {
        action(inputs, evaluation -> {
            evaluation.writeConfigBoolean(expectedGeneration, route, value);
            return null;
        });
    }

    public void writeConfigFloat(InvocationInputs inputs, long expectedGeneration, ConfigActionRoute route,
        float value) {
        if (!Float.isFinite(value))
            throw new IllegalArgumentException("config F32 value must be finite");
        action(inputs, evaluation -> {
            evaluation.writeConfigFloat(expectedGeneration, route, value);
            return null;
        });
    }

    public void executeConfigLabel(InvocationInputs inputs, long expectedGeneration, ConfigActionRoute route,
        String labelName, int labelIndex) {
        action(inputs, evaluation -> {
            evaluation.executeConfigLabel(expectedGeneration, route, labelName, labelIndex);
            return null;
        });
    }

    public Object executeTransient(InvocationInputs inputs, TransientExpressionSession session) {
        Objects.requireNonNull(session, "session");
        return session.privilege().effects() ? action(inputs, evaluation -> evaluation.executeTransient(session))
            : observe(inputs, evaluation -> evaluation.executeTransient(session));
    }

    public TransientCompilation prepareTransient(long expectedGeneration, String source,
        TransientExpressionSession.Privilege privilege, TransientExpressionSession.Purpose purpose,
        String sourceLocation) {
        assertLifecycleThread();
        var state = requireCurrent();
        if (!state.accepting || state.entityGeneration != expectedGeneration)
            throw new IllegalStateException("transient compilation belongs to a stale entity generation");
        var lease = state.module.retain();
        try {
            return new TransientCompilation(TransientExpressionSession.prepare(state.module, privilege),
                state.installationKey, expectedGeneration, source, privilege, purpose, sourceLocation, lease);
        } catch (Throwable failure) {
            lease.close();
            throw failure;
        }
    }

    public void attachTransient(TransientCompilation request, TransientExpressionSession session) {
        assertLifecycleThread();
        var state = requireCurrent();
        if (!state.accepting || state.installationKey != request.installationKey
            || state.entityGeneration != session.entityGeneration()
            || !state.targetGeneration.equals(session.targetGeneration()))
            throw new IllegalStateException("transient compilation belongs to a stale installation");
        session.attach(state.instance.openTransientAccess(session.layout(), session.dynamicAccess()));
    }

    public static final class TransientCompilation implements AutoCloseable {
        private final TransientExpressionSession.CompilationInputs inputs;
        private final Object installationKey;
        private final long generation;
        private final String source;
        private final TransientExpressionSession.Privilege privilege;
        private final TransientExpressionSession.Purpose purpose;
        private final String location;
        private final dev.semaweave.ResourceLease<MolangContext> lease;

        private TransientCompilation(TransientExpressionSession.CompilationInputs inputs, Object installationKey, long generation,
            String source, TransientExpressionSession.Privilege privilege, TransientExpressionSession.Purpose purpose,
            String location, dev.semaweave.ResourceLease<MolangContext> lease) {
            this.inputs = inputs;
            this.installationKey = installationKey;
            this.generation = generation;
            this.source = source;
            this.privilege = privilege;
            this.purpose = purpose;
            this.location = location;
            this.lease = lease;
        }

        public TransientExpressionSession compile(BooleanSupplier cancelled) throws Exception {
            try (lease) {
                return TransientExpressionSession.compile(inputs, generation, source, privilege, purpose, location,
                    cancelled);
            }
        }

        @Override public void close() { lease.close(); }
    }

    /** Synchronous tooling entry; asynchronous callers must separate prepare, compile and attach. */
    public TransientExpressionSession compileTransient(long expectedGeneration, String source,
        TransientExpressionSession.Privilege privilege, TransientExpressionSession.Purpose purpose,
        String sourceLocation, BooleanSupplier cancelled) throws Exception {
        var request = prepareTransient(expectedGeneration, source, privilege, purpose, sourceLocation);
        var session = request.compile(cancelled);
        try {
            attachTransient(request, session);
            return session;
        } catch (Throwable failure) {
            session.close();
            throw failure;
        }
    }

    public void clear() {
        assertLifecycleThread();
        if (evaluating)
            throw new IllegalStateException("cannot clear an evaluating expression owner");
        var retired = current;
        current = null;
        disconnect(retired);
        deferred.clear();
        if (retired != null) {
            retired.close();
        }
    }

    private State requireCurrent() {
        if (current == null) {
            throw new IllegalStateException("entity expression runtime has no current target");
        }
        return current;
    }

    @Override
    public void close() {
        clear();
    }

    @FunctionalInterface
    public interface Body<T> {
        T run(Evaluation evaluation) throws Throwable;
    }

    public record InvocationInputs(com.elfmcys.ysm.expression.host.InvocationInputs<?> productionInputs,
        EffectSink effectSink, BooleanSupplier cancellation) {
        public InvocationInputs {
            Objects.requireNonNull(productionInputs, "productionInputs");
            Objects.requireNonNull(effectSink, "effectSink");
            Objects.requireNonNull(cancellation, "cancellation");
        }
    }

    public final class Evaluation {
        private final State state;
        private final InvocationInputs inputs;
        private final MolangContext.LogicalPhase phase;

        @Nullable
        private Throwable failure;
        private ProjectionSink localProjection;
        private final MolangContext ownerContext;
        private boolean persistentActionWindow;
        private boolean closed;
        private int deferredCaptures;
        private InvocationProvenance deferredOrigin;

        private Evaluation(State state, InvocationInputs inputs, MolangContext.LogicalPhase phase,
            MolangContext ownerContext) {
            this.state = state;
            this.inputs = inputs;
            this.phase = phase;
            this.ownerContext = ownerContext;
            persistentActionWindow = phase != MolangContext.LogicalPhase.OBSERVATION;
        }

        public void setControllerContext(@Nullable ControllerExecutionState value) {
            setAnimationContext(null);
            drainDeferred(state, this, null, true);
            ownerContext.setControllerContext(value);
        }

        public void setAnimationContext(@Nullable AnimationExecutionState value) {
            var previous = ownerContext.animationState();
            if (previous != value)
                drainDeferred(state, this, previous, true);
            ownerContext.setAnimationContext(value);
        }

        public void endAnimationContext() { setAnimationContext(null); }

        public float evaluate(FloatExpression expression) {
            Objects.requireNonNull(expression, "expression");
            if (expression instanceof FloatExpression.Literal literal) {
                return literal.value();
            }
            if (expression instanceof FloatExpression.Scaled scaled) {
                return evaluate(scaled.source()) * scaled.factor();
            }
            var program = (FloatExpression.Program)expression;
            try {
                return executeFloat(program, ProgramArguments.empty());
            } catch (Throwable cause) {
                programFailed(program.location(), program.reference(), cause);
                return 0.0f;
            }
        }

        public boolean evaluate(BooleanProgram program) {
            Objects.requireNonNull(program, "program");
            try {
                return executeBoolean(program);
            } catch (Throwable cause) {
                programFailed(program.location(), program.reference(), cause);
                return false;
            }
        }

        public void execute(VoidProgram program) {
            executeVoid(program, ProgramArguments.empty());
        }

        public Object execute(UserFunctionProgram program, ProgramArguments arguments) {
            Objects.requireNonNull(program, "program");
            try {
                var context = beginProgram(program.location(), program.reference(), program.generation());
                try {
                    var result = state.instance.executeValue(program.reference(), context, state.subjectBinding,
                        ownerContext, arguments);
                    context.verifyEligible();
                    return result;
                } finally {
                    ownerContext.releaseExpression();
                }
            } catch (Throwable cause) {
                programFailed(program.location(), program.reference(), cause);
                return ExpressionValues.UNSET;
            }
        }

        public void dispatchPlayerInit() {
            for (var handler : state.playerInit) {
                executeVoid(handler, ProgramArguments.empty());
            }
        }

        public void dispatchPlayerUpdate(boolean shouldUpdate) {
            var arguments = ProgramArguments.ofBoolean(shouldUpdate);
            for (var handler : state.playerUpdate) {
                executeVoid(handler, arguments);
            }
        }

        public void dispatchSync(EventArguments values) {
            Objects.requireNonNull(values, "values");
            var arguments = ProgramArguments.reference(EVENT_ARGUMENTS, values);
            for (var handler : state.sync) {
                executeVoid(handler, arguments);
            }
        }

        public void dispatchDeferred(EventArguments values) {
            Objects.requireNonNull(values, "values");
            var arguments = ProgramArguments.reference(EVENT_ARGUMENTS, values);
            for (var handler : state.defer) {
                executeVoid(handler, arguments);
            }
        }

        @Nullable
        public PlayState codedController(@Nullable FloatExpression.Program handler) {
            if (handler == null)
                return null;
            try {
                float value = executeFloat(handler, ProgramArguments.empty());
                if (value == 2.0f)
                    return PlayState.CONTINUE;
                if (value == 3.0f)
                    return PlayState.STOP;
                if (value == 4.0f)
                    return PlayState.PAUSE;
                return null;
            } catch (Throwable cause) {
                programFailed(handler.location(), handler.reference(), cause);
                return null;
            }
        }

        public Optional<Throwable> failure() {
            return Optional.ofNullable(failure != null ? failure
                : ownerContext.firstFailure() == null ? null : ownerContext.firstFailure().cause());
        }

        private void recordFailure(Throwable cause) {
            if (failure == null)
                failure = ownerContext.firstFailure() == null ? cause : ownerContext.firstFailure().cause();
        }

        private boolean readConfigBoolean(long expectedGeneration, ConfigActionRoute route) {
            requireGeneration(expectedGeneration);
            var location = configLocation(route, ProgramRole.CONFIG_READ_BOOL, "read");
            return evaluate(state.module.requireBooleanProgram(location));
        }

        private float readConfigFloat(long expectedGeneration, ConfigActionRoute route) {
            requireGeneration(expectedGeneration);
            var location = configLocation(route, ProgramRole.CONFIG_READ_F32, "read");
            return evaluate(state.module.requireFloatProgram(location));
        }

        private void writeConfigBoolean(long expectedGeneration, ConfigActionRoute route, boolean value) {
            requireGeneration(expectedGeneration);
            var location = configLocation(route, ProgramRole.CONFIG_WRITE_BOOL, "write");
            executeVoid(state.module.requireVoidProgram(location), ProgramArguments.ofBoolean(value));
        }

        private void writeConfigFloat(long expectedGeneration, ConfigActionRoute route, float value) {
            requireGeneration(expectedGeneration);
            var location = configLocation(route, ProgramRole.CONFIG_WRITE_F32, "write");
            executeVoid(state.module.requireVoidProgram(location), ProgramArguments.ofFloat(value));
        }

        private void executeConfigLabel(long expectedGeneration, ConfigActionRoute route, String labelName,
            int labelIndex) {
            requireGeneration(expectedGeneration);
            Objects.requireNonNull(route, "route");
            var location = new ModelProgramInventory.SemanticLocation(state.targetId, route.labelLocation(),
                ProgramRole.CONFIG_LABEL_ACTION, route.labelDiscriminator(labelName, labelIndex));
            executeVoid(state.module.requireVoidProgram(location), ProgramArguments.empty());
        }

        public void dispatchPendingDeferred() {
            if (phase == MolangContext.LogicalPhase.OBSERVATION)
                throw new IllegalStateException("deferred dispatch requires an effect-capable round");
            drainDeferred(state, this);
        }

        public Object executeTransient(TransientExpressionSession session) throws Throwable {
            Objects.requireNonNull(session, "session");
            if (!state.targetGeneration.equals(session.targetGeneration())
                || state.entityGeneration != session.entityGeneration() || current != state) {
                throw new IllegalStateException("transient session belongs to a stale target generation");
            }
            if (session.privilege().effects() && phase == MolangContext.LogicalPhase.OBSERVATION) {
                throw new IllegalStateException("transient capability does not match its logical phase");
            }
            ownerContext.bindExpression(session.sourceLocation(), -1, session.privilege().effects());
            var context = ownerContext;
            try {
                var result = session.execute(context, state.subjectBinding, ownerContext);
                context.verifyEligible();
                return result;
            } catch (Throwable cause) {
                recordFailure(cause);
                rethrowSevere(provenance(state, session.sourceLocation(), -1), cause);
                throw cause;
            } finally {
                ownerContext.releaseExpression();
            }
        }

        private void requireGeneration(long expectedGeneration) {
            if (expectedGeneration <= 0 || state.entityGeneration != expectedGeneration || current != state) {
                throw new IllegalStateException("config action belongs to a stale entity generation");
            }
        }

        private ModelProgramInventory.SemanticLocation configLocation(ConfigActionRoute route, ProgramRole role,
            String discriminator) {
            Objects.requireNonNull(route, "route");
            return new ModelProgramInventory.SemanticLocation(state.targetId, route.location(), role, discriminator);
        }

        private float executeFloat(FloatExpression.Program program, ProgramArguments arguments) throws Throwable {
            var context = beginProgram(program.location(), program.reference(), program.generation());
            try {
                float result = state.instance.executeFloat(program.reference(), context, state.subjectBinding,
                    ownerContext, arguments);
                context.verifyEligible();
                return result;
            } finally {
                ownerContext.releaseExpression();
            }
        }

        private boolean executeBoolean(BooleanProgram program) throws Throwable {
            var context = beginProgram(program.location(), program.reference(), program.generation());
            try {
                boolean result = state.instance.executeBoolean(program.reference(), context, state.subjectBinding,
                    ownerContext, ProgramArguments.empty());
                context.verifyEligible();
                return result;
            } finally {
                ownerContext.releaseExpression();
            }
        }

        private void executeVoid(VoidProgram program, ProgramArguments arguments) {
            Objects.requireNonNull(program, "program");
            try {
                var context = beginProgram(program.location(), program.reference(), program.generation());
                try {
                    state.instance.executeVoid(program.reference(), context, state.subjectBinding, ownerContext,
                        arguments);
                    context.verifyEligible();
                } finally {
                    ownerContext.releaseExpression();
                }
            } catch (Throwable cause) {
                programFailed(program.location(), program.reference(), cause);
            }
        }

        private MolangContext beginProgram(ModelProgramInventory.SemanticLocation location,
            dev.semaweave.compilation.ProgramRef reference, ResourceGenerationToken generation) {
            if (!state.targetGeneration.equals(generation)) {
                throw new IllegalStateException("program belongs to a stale target generation");
            }
            if (state.failedPrograms.get(reference.sourceId())) {
                throw new IllegalStateException("program is disabled until target replacement");
            }
            ownerContext.bindExpression(location.location(), reference.sourceId(), persistentActionWindow);
            return ownerContext;
        }

        private void programFailed(ModelProgramInventory.SemanticLocation location,
            dev.semaweave.compilation.ProgramRef reference, Throwable cause) {
            if (cause instanceof VirtualMachineError fatal)
                throw fatal;
            if (cause instanceof ThreadDeath fatal)
                throw fatal;
            var source = provenance(state, location.location(), reference.sourceId());
            if (deferredOrigin != null)
                YesSteveModel.LOGGER.debug("Molang deferred capture failed: origin={} handler={}", deferredOrigin, source);
            state.recordFailure(source, cause, isDeterministicProgramFailure(cause),
                AnimationDiagnostics.errorLevel(inputs.productionInputs()));
            recordFailure(cause);
            rethrowSevere(source, cause);
        }

        private static void rethrowSevere(InvocationProvenance source, Throwable cause) {
            var visited = new IdentityHashMap<Throwable, Boolean>();
            for (Throwable currentCause = cause;
                currentCause != null && visited.put(currentCause, Boolean.TRUE) == null;
                currentCause = currentCause.getCause()) {
                if (currentCause instanceof ProviderReturnContractException) {
                    throw new ExecutionFailure(ExecutionFailure.Reason.NON_NULL_RETURN, source,
                        "provider violated its non-null return contract", cause);
                }
                if (currentCause instanceof ExecutionFailure classified
                    && classified.reason() == ExecutionFailure.Reason.NON_NULL_RETURN)
                    throw classified;
            }
        }

        private static boolean isDeterministicProgramFailure(Throwable failure) {
            if (!(failure instanceof RuntimeExecutionException runtime)) {
                return false;
            }
            return switch (runtime.reason()) {
                case RUNTIME_LINKAGE_MISMATCH, UNKNOWN_PROGRAM, SHAPE_MISMATCH, INVALID_ARGUMENTS, CONTEXT_MISMATCH ->
                    true;
                case RESOURCE_CLOSED, INSTANCE_CLOSED, SUBJECT_BINDING_MISMATCH -> false;
            };
        }

    }

    private static InvocationProvenance provenance(State state, String location, int programId) {
        return new InvocationProvenance(state.targetId, location, programId, state.targetGenerationId,
            state.entityGeneration);
    }

    private static final class State implements AutoCloseable {
        private final Object installationKey = new Object();
        private final RenderTargetExpressionModule module;
        private final CompiledModuleInstance<MolangContext> instance;
        private final CompiledRoamingState roamingState;
        private final ResourceGenerationToken targetGeneration;
        private final long targetGenerationId;
        private final long entityGeneration;
        private final String targetId;
        private final HostLinkage linkage;
        private final boolean typedSubject;
        @Nullable
        private final net.minecraft.world.entity.Entity subject;
        private final dev.semaweave.SubjectBinding subjectBinding;
        private final ComponentScope components;
        private final List<VoidProgram> playerInit;
        private final List<VoidProgram> playerUpdate;
        private final List<VoidProgram> sync;
        private final List<VoidProgram> defer;
        private final BitSet failedPrograms = new BitSet();
        private long invocationFailures;
        private boolean accepting = true;
        private boolean roamingInitialized;
        private boolean armInitializationAttempted;
        private ArmPair armConnection;

        private State(RenderTargetExpressionModule module, CompiledModuleInstance<MolangContext> instance,
            CompiledRoamingState roamingState, long entityGeneration,
            @Nullable net.minecraft.world.entity.Entity subject,
            Map<String, ? extends List<? extends EventProgram>> handlers, @Nullable ComponentScope components) {
            this.module = module;
            this.instance = instance;
            this.roamingState = roamingState;
            this.targetGeneration = module.generation();
            this.targetGenerationId = module.generationId();
            this.entityGeneration = entityGeneration;
            this.targetId = module.identity().targetId();
            this.linkage = module.productionLinkage();
            this.subject = subject;
            this.components = components;
            this.typedSubject = subject != null;
            this.subjectBinding = instance.bindSubject(subject);
            playerInit = voidHandlers(handlers, "player_init", ProgramRole.EVENT_PLAYER_INIT, targetGeneration);
            playerUpdate = voidHandlers(handlers, "player_update", ProgramRole.EVENT_PLAYER_UPDATE, targetGeneration);
            sync = voidHandlers(handlers, "sync", ProgramRole.EVENT_SYNC, targetGeneration);
            defer = voidHandlers(handlers, "defer", ProgramRole.EVENT_DEFER, targetGeneration);
        }

        private static State create(RenderTargetExpressionModule module,
            Map<String, ? extends List<? extends EventProgram>> handlers, long entityGeneration,
            CompiledRoamingState roamingState, ComponentScope components) {
            var instance = module.newInstance(components, roamingState);
            try {
                return new State(module, instance, roamingState, entityGeneration, components.subject(), handlers, components);
            } catch (Throwable failure) {
                instance.close();
                throw failure;
            }
        }

        private static State create(RenderTargetExpressionModule module,
            Map<String, ? extends List<? extends EventProgram>> handlers, long entityGeneration,
            CompiledRoamingState roamingState, dev.semaweave.RuntimeLinkage<MolangContext> linkage) {
            var instance = module.newInstance(linkage, roamingState);
            try {
                return new State(module, instance, roamingState, entityGeneration, null, handlers, null);
            } catch (Throwable failure) {
                instance.close();
                throw failure;
            }
        }

        private static List<VoidProgram> voidHandlers(Map<String, ? extends List<? extends EventProgram>> handlers,
            String name, ProgramRole role, ResourceGenerationToken generation) {
            var values = handlers.get(name);
            if (values == null) {
                values = List.of();
            }
            var result = new ArrayList<VoidProgram>(values.size());
            for (var value : values) {
                if (!(value instanceof VoidProgram program) || program.location().role() != role
                    || !program.generation().equals(generation)) {
                    throw new IllegalArgumentException("invalid " + name + " event binding");
                }
                result.add(program);
            }
            return List.copyOf(result);
        }

        @Override
        public void close() {
            instance.close();
        }

        private void recordFailure(InvocationProvenance provenance, Throwable failure, boolean deterministic,
            org.apache.logging.log4j.Level logLevel) {
            var id = provenance.programId();
            if (deterministic && !failedPrograms.get(id)) {
                failedPrograms.set(id);
                YesSteveModel.LOGGER.log(logLevel,
                    "Persistent expression disabled until target replacement: "
                        + "target={} location={} program={} targetGeneration={} " + "entityGeneration={}",
                    provenance.targetId(), provenance.programLocation(), id, provenance.targetGeneration(),
                    provenance.entityGeneration(), failure);
            } else if (!deterministic && invocationFailures++ % 256 == 0) {
                YesSteveModel.LOGGER.log(logLevel,
                    "Expression invocation aborted without disabling its program: "
                        + "target={} location={} program={} targetGeneration={} " + "entityGeneration={}",
                    provenance.targetId(), provenance.programLocation(), id, provenance.targetGeneration(),
                    provenance.entityGeneration(), failure);
            }
        }
    }

    private record DeferredCapture(State state, Evaluation evaluation, AnimationExecutionState animation,
        ControllerExecutionState controller, EventArguments arguments, InvocationProvenance source) {
    }

}
