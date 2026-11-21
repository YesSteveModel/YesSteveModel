package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import dev.semaweave.CompiledModuleInstance;
import dev.semaweave.CompiledRoamingState;
import dev.semaweave.ListType;
import dev.semaweave.ProgramArguments;
import dev.semaweave.ExpressionValues;
import dev.semaweave.RoamingPayload;
import dev.semaweave.RoamingRole;
import dev.semaweave.interpreter.InterpretedValue;
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

/**
 * Owns one installation's mutable expression instance and closed event dispatcher.
 *
 * <p>
 * Invariant: an invocation uses one exact installation. The first program or component failure remains recorded even
 * when later code receives a typed fallback. Domain actions take effect immediately.
 *
 * <p>
 * Execution: lifecycle calls require the idle main-thread owner. Preparation and installation can also run under the
 * exact GUI installation permit. Evaluation uses the owner's exclusive execution window and rejects reentry. Local sync
 * dispatch starts after its source borrow ends.
 *
 * <p>
 * Ownership: one store execution protects this runtime's private instance and active interpreted use. Host inputs
 * remain borrowed, and the runtime owns pending projections.
 *
 * <p>
 * Seam: implements [complete preparation and admission](docs/architecture/animation/molang-runtime.md#完整准备与准入),
 * [invocations and effects](docs/architecture/animation/molang-runtime.md#invocation-与效果), and [execution authority and
 * task handoff](docs/architecture/animation/entity-lifecycle.md#执行权与任务交接).
 */
public final class EntityExpressionRuntime implements AutoCloseable {
    public static final int MAX_DEFERRED_CAPTURES = 64;

    private static final ListType EVENT_ARGUMENTS = ExpressionHost.EVENT_F32_ARGUMENTS;

    private static final InterpretedValue.ForeignAccess INTERPRETED_EVENT_ARGUMENTS =
        new InterpretedValue.ForeignAccess() {
            @Override
            public dev.semaweave.ValueType type() {
                return EVENT_ARGUMENTS;
            }

            @Override
            public InterpretedValue.Kind kind() {
                return InterpretedValue.Kind.LIST;
            }

            @Override
            public int length(Object carrier) {
                return ((EventArguments)carrier).size();
            }

            @Override
            public InterpretedValue element(Object carrier, int index) {
                var values = (EventArguments)carrier;
                return index < 0 || index >= values.size() ? InterpretedValue.missing(dev.semaweave.BuiltinType.F32)
                    : InterpretedValue.ofFloat(values.getFloat(index));
            }
        };

    private final Thread mainThread = Thread.currentThread();
    private Runnable publicAccess = () -> {
    };
    private Runnable executionAccess = () -> {
    };
    private Runnable installationAccess = () -> {
        if (Thread.currentThread() != mainThread)
            throw new IllegalStateException("Expression installation requires its main thread");
    };
    private boolean executionOwnerBound;
    private long nextGeneration;
    private final ArrayDeque<DeferredCapture> deferred = new ArrayDeque<>();
    private volatile State current;
    private ArmPair armPair;
    private ArmPair executionArmPair;
    private java.util.function.BooleanSupplier projectionAvailable = () -> true;
    private final ArrayDeque<Projection> projections = new ArrayDeque<>();
    private boolean evaluating;
    private Evaluation activeEvaluation;
    private final ArrayDeque<LocalSync> localSyncs = new ArrayDeque<>();
    private boolean drainingSync;

    public void bindExecutionOwner(Runnable publicAccess, Runnable executionAccess, Runnable installationAccess) {
        assertLifecycleThread();
        if (executionOwnerBound)
            throw new IllegalStateException("Expression execution owner is already bound");
        this.publicAccess = Objects.requireNonNull(publicAccess);
        this.executionAccess = Objects.requireNonNull(executionAccess);
        this.installationAccess = Objects.requireNonNull(installationAccess);
        executionOwnerBound = true;
    }

    public void replace(AnimationStore store, ComponentScope components) {
        replace(store, components, RoamingRole.PRODUCER);
    }

    public void replace(AnimationStore store, ComponentScope components, RoamingRole role) {
        try (var prepared = prepare(store, components, role)) {
            install(prepared, () -> {
            });
        }
    }

    /** Constructs a complete private instance under the store's execution protection. */
    public PreparedInstallation prepare(AnimationStore store, ComponentScope components, RoamingRole role) {
        assertInstallationThread();
        return new PreparedInstallation(State.create(store, ++nextGeneration, role, components));
    }

    /**
     * Consumes a private candidate and invokes publication inline before retiring the previous instance.
     *
     * <p>
     * Requires: the caller owns the candidate and the idle installation window. Publication must not leave externally
     * published state behind if the callback throws.
     *
     * <p>
     * Failure: publication failure restores the previous expression instance and closes the candidate. Failure after
     * publication does not roll back the installation.
     *
     * <p>
     * Ownership: a taken candidate is consumed on success and failure. The caller must not reuse its mutable state.
     */
    public void install(PreparedInstallation prepared, Runnable publication) {
        install(prepared, publication, () -> {
        });
    }

    public void install(PreparedInstallation prepared, Runnable publication, Runnable clearRetiredState) {
        assertInstallationThread();
        if (evaluating)
            throw new IllegalStateException("cannot replace an evaluating expression owner");
        prepared.initializeAtCommit();
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
                try {
                    clearRetiredState.run();
                } finally {
                    retired.close();
                }
            } catch (RuntimeException | LinkageError failure) {
                YesSteveModel.LOGGER.warn("Previous expression installation cleanup failed", failure);
            }
        }
    }

    /**
     * Owns an unpublished mutable instance until install() takes it or close() releases it.
     *
     * <p>
     * Execution: preparation, initialization, installation, and close use the same exclusive installation window. The
     * returned installation identity does not grant evaluation or publication rights before activation.
     */
    public final class PreparedInstallation implements AutoCloseable {
        private State state;
        private java.util.function.Supplier<RoamingPayload> roamingInitializer;

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

        /** Reads selection-owned inputs in the installation commit window, before publication. */
        public void initializeRoamingAtCommit(java.util.function.Supplier<RoamingPayload> payload) {
            Objects.requireNonNull(state, "installation already transferred");
            roamingInitializer = Objects.requireNonNull(payload);
        }

        private void initializeAtCommit() {
            if (roamingInitializer != null) {
                initializeRoaming(roamingInitializer.get());
                roamingInitializer = null;
            }
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
            var retired = state;
            state = null;
            roamingInitializer = null;
            if (retired != null)
                retired.close();
        }
    }

    void replace(AnimationStore store, dev.semaweave.RuntimeLinkage<MolangContext> linkage) {
        replace(store, linkage, RoamingRole.PRODUCER);
    }

    void replace(AnimationStore store, dev.semaweave.RuntimeLinkage<MolangContext> linkage, RoamingRole role) {
        assertLifecycleThread();
        replace(State.create(store, ++nextGeneration, role, linkage));
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
        evaluation.localProjection = (delta, finalWindow) -> {
            localProjection.accept(delta);
            return true;
        };
        boolean successful = false;
        try {
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

    /** Component cleanup completes before borrowed inputs are released. */
    public <T> T animationPass(InvocationInputs inputs, boolean persistentActionWindow, Body<T> body,
        java.util.function.Consumer<RoamingPayload> localProjection) {
        if (persistentActionWindow)
            Objects.requireNonNull(localProjection);
        return animationPassProjected(inputs, persistentActionWindow, body, (delta, finalWindow) -> {
            localProjection.accept(delta);
            return true;
        });
    }

    /**
     * Runs one animation body and retains successful roaming output for later owner-qualified adoption.
     *
     * <p>
     * Requires: the caller completes component hooks before the body returns. The production inputs match the installed
     * subject. The projection sink is required for a persistent action window.
     *
     * <p>
     * Failure: body, program, or hook failure rejects the new projection. Earlier domain actions remain effective.
     *
     * <p>
     * Ownership: evaluation and component inputs must not escape the synchronous body. A pending sink remains
     * associated with this exact source installation until adoption, invalidation, or the final window.
     */
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
        executionAccess.run();
        Objects.requireNonNull(inputs, "inputs");
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
        var ownerContext = phase == MolangContext.LogicalPhase.OBSERVATION
            ? (state.typedSubject ? state.linkage.observation(production, state.roamingState, ownerProvenance)
                : MolangContext.observation(production, production, state.roamingState, ownerProvenance))
            : (state.typedSubject ? state.linkage.logical(production, state.roamingState, phase, ownerProvenance)
                : MolangContext.logical(production, production, phase, state.roamingState, ownerProvenance));
        ownerContext.configureLimits(state.execution.metadata().executionLimits());
        ownerContext.bindOrigin(state.subjectBinding);
        var evaluation = new Evaluation(state, inputs, phase, ownerContext);
        ownerContext.deferredSubmit((request, source) -> {
            if (evaluation.deferredCaptures++ >= MAX_DEFERRED_CAPTURES)
                throw new IllegalStateException("deferred capture queue exceeds " + MAX_DEFERRED_CAPTURES);
            deferred.addLast(new DeferredCapture(state, evaluation, ownerContext.animationState(),
                ownerContext.controllerState(), request, source));
        });
        evaluating = true;
        activeEvaluation = evaluation;
        return evaluation;
    }

    private void endEvaluation(Evaluation evaluation, boolean successful) {
        Throwable primary = evaluation.failure().orElse(null);
        Throwable closingFailure = null;
        try {
            if (primary == null && evaluation.phase != MolangContext.LogicalPhase.OBSERVATION)
                drainDeferred(evaluation.state, evaluation);
            if (evaluation.localProjection != null && successful && evaluation.failure().isEmpty()
                && evaluation.state.roamingState.role() == RoamingRole.PRODUCER
                && evaluation.state.roamingState.hasChanges()) {
                var delta = evaluation.state.roamingState.popChanges();
                projections
                    .addLast(new Projection(evaluation.state, delta, evaluation.localProjection, executionArmPair));
            }
        } catch (Throwable failure) {
            closingFailure = failure;
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
                activeEvaluation = null;
            }
        }
        try {
            if (primary != null || closingFailure != null)
                localSyncs.clear();
            else
                drainLocalSyncs();
        } catch (Throwable failure) {
            closingFailure = appendFailure(closingFailure, failure);
        }
        if (closingFailure != null) {
            if (appendFailure(primary, closingFailure) != closingFailure)
                return;
            if (closingFailure instanceof RuntimeException failure)
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
        evaluation.state.execution.reportPreparation();
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
        assertInstallationThread();
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
        if (arm.execution.interpreted() || producer.execution.interpreted())
            throw new IllegalStateException("arm pairing requires AOT installations");
        if (!producer.execution.metadata().containerId().equals(arm.execution.metadata().containerId())
            || !producer.execution.metadata().targetId().equals(arm.execution.metadata().targetId()))
            return;
        var bodyFields = producer.roamingState.fieldNames();
        var armFields = arm.roamingState.fieldNames();
        var fields = new short[bodyFields.size()];
        for (int index = 0; index < fields.length; index++)
            fields[index] = (short)armFields.indexOf(bodyFields.get(index));
        var pair = new ArmPair(body, producer, this, arm, fields);
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
                    if (failure == null)
                        failure = next;
                    else if (failure != next)
                        failure.addSuppressed(next);
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
                        pair.consumer.roamingState.applyPatch(payload);
                    }
                } catch (RuntimeException | Error next) {
                    if (failure == null)
                        failure = next;
                    else if (failure != next)
                        failure.addSuppressed(next);
                }
            }
            if (finalWindow || result.localDone && result.armDone)
                iterator.remove();
        }
        if (failure instanceof RuntimeException error)
            throw error;
        if (failure instanceof Error error)
            throw error;
    }

    /**
     * Receives a committed delta at an idle owner adoption point.
     *
     * <p>
     * Execution: called synchronously on the lifecycle thread after task handoff. Returning false retains the pending
     * projection only before the final window. A thrown failure ends that sink's attempt.
     *
     * <p>
     * Ownership: the delta contains copied values. The callback does not obtain access to the source's mutable
     * instance.
     */
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

    private static RoamingPayload project(RoamingPayload source, short[] fields) {
        int count = 0;
        for (var field : source.fields()) {
            if (fields[field] >= 0)
                count++;
        }
        var mappedFields = new short[count];
        var values = new float[count];
        int next = 0;
        for (int index = 0; index < source.fields().length; index++) {
            short target = fields[source.fields()[index]];
            if (target >= 0) {
                mappedFields[next] = target;
                values[next++] = source.values()[index];
            }
        }
        return new RoamingPayload(mappedFields, values);
    }

    private record ArmPair(EntityExpressionRuntime body, State producer, EntityExpressionRuntime arm, State consumer,
        short[] fields) {
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
        publicAccess.run();
        if (Thread.currentThread() != mainThread || evaluating)
            throw new IllegalStateException("Expression lifecycle requires its idle main thread");
    }

    private void assertInstallationThread() {
        installationAccess.run();
        if (evaluating)
            throw new IllegalStateException("Expression installation requires its idle execution owner");
    }

    /** Revokes admission without mutating collections still owned by an accepted execution. */
    public void seal() {
        var state = current;
        if (state != null)
            state.accepting = false;
    }

    /** Revokes acceptance and drops projections in the idle lifecycle window. */
    public void invalidate() {
        assertLifecycleThread();
        if (current != null)
            current.accepting = false;
        projections.clear();
    }

    public <T> Optional<T> sample(java.util.function.Function<Snapshot, T> projection) {
        executionAccess.run();
        if (current == null)
            return Optional.empty();
        var state = current;
        return Optional
            .of(projection.apply(new Snapshot(new Installation(this, state), state.roamingState.snapshot())));
    }

    public <T> Optional<T> sampleInstallation(java.util.function.Function<Installation, T> projection) {
        executionAccess.run();
        return current == null ? Optional.empty() : Optional.of(projection.apply(new Installation(this, current)));
    }

    /**
     * Identifies one installation without owning or extending that installation's lifetime.
     *
     * <p>
     * Ownership: callers can retain this routing identity, but must use commit() for an operation that depends on the
     * installation still being current and accepting.
     */
    public static final class Installation {
        private final EntityExpressionRuntime owner;
        private final State state;

        private Installation(EntityExpressionRuntime owner, State state) {
            this.owner = owner;
            this.state = state;
        }

        public List<String> roamingFields() {
            return state.roamingState == null ? List.of() : state.roamingState.fieldNames();
        }

        /**
         * Runs the action inline only if this exact installation is current, accepting, and idle.
         *
         * <p>
         * Execution: the caller uses the lifecycle thread. A false result runs no action. An action failure propagates
         * without rolling back mutations that the action already made.
         *
         * @return whether the qualified action ran and returned normally
         */
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

    /** Accepts an independent sync payload; dispatch starts after the source borrow has ended. */
    public void enqueueLocalSync(EventArguments arguments,
        com.elfmcys.ysm.geckolib3.core.processor.AnimationProcessor<?> processor) {
        if (!evaluating || activeEvaluation == null)
            throw new IllegalStateException("sync capture requires an active source evaluation");
        localSyncs.addLast(
            new LocalSync(activeEvaluation.state, arguments, activeEvaluation.ownerContext.provenance(), processor));
    }

    private void drainLocalSyncs() {
        if (drainingSync)
            return;
        drainingSync = true;
        try {
            LocalSync pending;
            while ((pending = localSyncs.pollFirst()) != null) {
                if (current != pending.state || !pending.state.accepting) {
                    YesSteveModel.LOGGER.debug("Molang sync rejected after installation ended: {}", pending.source);
                    continue;
                }
                pending.processor.dispatchSync(pending.arguments);
            }
        } finally {
            localSyncs.clear();
            drainingSync = false;
        }
    }

    private record LocalSync(State state, EventArguments arguments, InvocationProvenance source,
        com.elfmcys.ysm.geckolib3.core.processor.AnimationProcessor<?> processor) {
    }

    private void drainDeferred(State state, Evaluation evaluation) {
        drainDeferred(state, evaluation, null, false);
    }

    private void drainDeferred(State state, Evaluation evaluation, AnimationExecutionState animation, boolean exact) {
        if (evaluation.failure != null || current != state || !state.accepting) {
            deferred.removeIf(capture -> capture.evaluation == evaluation);
            return;
        }
        var previousAnimation = evaluation.ownerContext.animationState();
        var previousController = evaluation.ownerContext.controllerState();
        var previousOrigin = evaluation.deferredOrigin;
        boolean previousActionWindow = evaluation.persistentActionWindow;
        var production = evaluation.inputs.productionInputs();
        var event = production instanceof com.elfmcys.ysm.geckolib3.core.processor.MinecraftRuntimeInputs<?> minecraft
            ? minecraft.animationEvent() : null;
        var controllerWindow = event == null ? null : event.getCodedController();
        if (event != null)
            event.setCodedAnimationController(null);
        try {
            while (true) {
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
            restoreControllerWindow(event, controllerWindow);
            evaluation.ownerContext.setAnimationContext(previousAnimation);
            evaluation.ownerContext.setControllerContext(previousController);
            evaluation.deferredOrigin = previousOrigin;
            evaluation.persistentActionWindow = previousActionWindow;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void restoreControllerWindow(com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent event,
        com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController controller) {
        if (event != null)
            event.setCodedAnimationController(controller);
    }

    private <T> T runWithComponents(Body<T> body, Evaluation evaluation) {
        var production = evaluation.inputs.productionInputs();
        if (!(production instanceof com.elfmcys.ysm.geckolib3.core.processor.MinecraftRuntimeInputs<?> minecraft)
            || minecraft.animationEvent() == null || evaluation.state.components == null) {
            evaluation.captureSubjectSample(com.elfmcys.ysm.expression.host.SubjectSample.MISSING);
            if (evaluation.phase != MolangContext.LogicalPhase.OBSERVATION)
                drainDeferred(evaluation.state, evaluation);
            return runBody(body, evaluation);
        }
        class ComponentBody implements Runnable {
            T result;

            public void run() {
                evaluation.captureSubjectSample(evaluation.state.components.captureSubjectSample());
                if (evaluation.phase != MolangContext.LogicalPhase.OBSERVATION)
                    drainDeferred(evaluation.state, evaluation);
                result = runBody(body, evaluation);
                drainDeferred(evaluation.state, evaluation);
            }
        }
        var scoped = new ComponentBody();
        evaluation.state.components.evaluate(minecraft.animationEvent(), () -> true, scoped);
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

    public Optional<AnimationStore> store() {
        executionAccess.run();
        return current == null ? Optional.empty() : Optional.of(current.execution.store());
    }

    public Optional<AnimationStore.Metadata> metadata() {
        executionAccess.run();
        return current == null ? Optional.empty() : Optional.of(current.execution.metadata());
    }

    public AnimationStore.ReadBatch openAnimationReadPhase(AnimationStore.View view) throws java.io.IOException {
        return requireCurrent().execution.openReadPhase(view);
    }

    public Optional<RoamingState> roamingState() {
        executionAccess.run();
        return current == null ? Optional.empty() : Optional.of(current.roamingState);
    }

    public void initializeRoaming(RoamingPayload payload) {
        executionAccess.run();
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
            for (int index = 0; index < payload.fields().length; index++)
                state.roamingState.write(payload.fields()[index], payload.values()[index]);
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
        return requireCurrent().roamingState.hasChanges();
    }

    public RoamingPayload takeRoamingDelta() {
        return requireCurrent().roamingState.popChanges();
    }

    public RoamingPayload takeRoamingFull() {
        return requireCurrent().roamingState.full();
    }

    public long generation() {
        executionAccess.run();
        return current == null ? 0 : current.entityGeneration;
    }

    public int pendingDeferredCount() {
        executionAccess.run();
        return deferred.size();
    }

    public void validateConfigAction(long expectedGeneration, String location, ProgramRole role, String discriminator) {
        assertLifecycleThread();
        var state = requireCurrent();
        if (!state.accepting || expectedGeneration <= 0 || state.entityGeneration != expectedGeneration)
            throw new IllegalStateException("config action belongs to a stale entity generation");
        state.execution.requireVoidProgram(new ModelProgramInventory.SemanticLocation(state.targetId,
            state.execution.metadata().set(), location, role, discriminator));
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

    /**
     * Executes a caller-owned transient session as one privileged action or observation round.
     *
     * <p>
     * Requires: the session is attached to this runtime's current installation. The owner holds its exclusive execution
     * window. This call borrows the session through execution and cleanup without closing it.
     *
     * <p>
     * Failure: stale generation or incompatible phase rejects execution. Program failure follows the round's failure
     * boundary; successful compilation alone does not authorize this invocation.
     */
    public Object executeTransient(InvocationInputs inputs, TransientExpressionSession session) {
        Objects.requireNonNull(session, "session");
        return session.privilege().effects() ? action(inputs, evaluation -> evaluation.executeTransient(session))
            : observe(inputs, evaluation -> evaluation.executeTransient(session));
    }

    /**
     * Freezes transient compilation inputs under independent store protection on the idle lifecycle thread.
     *
     * <p>
     * Failure: a missing, ended, or different entity generation rejects preparation before handoff. A returned request
     * can become stale while independent compilation continues.
     *
     * <p>
     * Ownership: the caller owns the returned request until a compiler accepts it. The caller must close an unaccepted
     * request. Successful compilation retains protection until attachment or result discard; failure releases it.
     *
     * @return the independent compilation request, not an executable owner capability
     */
    public TransientCompilation prepareTransient(long expectedGeneration, String source,
        TransientExpressionSession.Privilege privilege, TransientExpressionSession.Purpose purpose,
        String sourceLocation) {
        assertLifecycleThread();
        var state = requireCurrent();
        if (!state.accepting || state.entityGeneration != expectedGeneration)
            throw new IllegalStateException("transient compilation belongs to a stale entity generation");
        state.aotInstance();
        var lease = state.execution.openWork();
        try {
            return new TransientCompilation(lease.transientInputs(privilege), state.installationKey, expectedGeneration,
                source, privilege, purpose, sourceLocation, lease);
        } catch (Throwable failure) {
            lease.close();
            throw failure;
        }
    }

    /**
     * Attaches a completed program only to the request's exact current installation and target generation.
     *
     * <p>
     * Requires: session is the result of request, and the lifecycle-thread caller owns the session. The caller must
     * serialize attachment with session execution and close.
     *
     * <p>
     * Ensures: success equips the session for qualified owner execution; the caller still owns its close obligation.
     *
     * <p>
     * Failure: stale installation rejects attachment without closing the session. The handoff owner must close a
     * rejected or late session. Failure to attach execution access does not transfer session ownership.
     */
    public void attachTransient(TransientCompilation request, TransientExpressionSession session) {
        assertLifecycleThread();
        var state = requireCurrent();
        if (!state.accepting || state.installationKey != request.installationKey
            || state.entityGeneration != session.entityGeneration()
            || !state.targetGeneration.equals(session.targetGeneration()))
            throw new IllegalStateException("transient compilation belongs to a stale installation");
        session.attach(state.aotInstance().openTransientAccess(session.layout(), session.dynamicAccess()));
        request.close();
    }

    /**
     * Owns frozen inputs and store protection for one independent compilation handoff.
     *
     * <p>
     * Execution: prepare runs on the idle lifecycle thread; compile can run on a background thread. The caller must not
     * close an accepted request while compile uses its frozen inputs.
     *
     * <p>
     * Ownership: close releases an unaccepted request. Once compile starts, its worker retains cleanup on failure. A
     * successful result receiver closes the request after attaching or discarding the session.
     *
     * <p>
     * Seam: refines [transient execution](docs/architecture/animation/molang-runtime.md#短期执行).
     */
    public static final class TransientCompilation implements AutoCloseable {
        private TransientExpressionSession.CompilationInputs inputs;
        private final Object installationKey;
        private final long generation;
        private final String source;
        private final TransientExpressionSession.Privilege privilege;
        private final TransientExpressionSession.Purpose purpose;
        private final String location;
        private AnimationStore.Work lease;

        private TransientCompilation(TransientExpressionSession.CompilationInputs inputs, Object installationKey,
            long generation, String source, TransientExpressionSession.Privilege privilege,
            TransientExpressionSession.Purpose purpose, String location, AnimationStore.Work lease) {
            this.inputs = inputs;
            this.installationKey = installationKey;
            this.generation = generation;
            this.source = source;
            this.privilege = privilege;
            this.purpose = purpose;
            this.location = location;
            this.lease = lease;
        }

        /**
         * Compiles this unconsumed request, preserving store protection for the result handoff.
         *
         * <p>
         * Requires: the caller owns the accepted request and has not compiled or closed it before. A cancellation
         * signal requests termination; the worker retains cleanup responsibility until return.
         *
         * @return an owned session whose result receiver must attach or close, including after a late completion
         */
        public TransientExpressionSession compile(BooleanSupplier cancelled) throws Exception {
            try {
                return TransientExpressionSession.compile(Objects.requireNonNull(inputs, "compilation already used"),
                    generation, source, privilege, purpose, location, cancelled);
            } catch (Exception | Error failure) {
                close();
                throw failure;
            } finally {
                inputs = null;
            }
        }

        @Override
        public void close() {
            var retained = lease;
            lease = null;
            inputs = null;
            if (retained != null)
                retained.close();
        }
    }

    /**
     * Prepares, compiles, and attaches a transient session synchronously for tooling.
     *
     * <p>
     * Execution: the idle lifecycle-thread caller waits for compilation. Background callers must use separate prepare,
     * compile, and attach handoffs instead.
     *
     * <p>
     * Ownership: failure closes the new session. Success transfers its close obligation to the caller.
     */
    public TransientExpressionSession compileTransient(long expectedGeneration, String source,
        TransientExpressionSession.Privilege privilege, TransientExpressionSession.Purpose purpose,
        String sourceLocation, BooleanSupplier cancelled) throws Exception {
        var request = prepareTransient(expectedGeneration, source, privilege, purpose, sourceLocation);
        var session = request.compile(cancelled);
        try {
            attachTransient(request, session);
            return session;
        } catch (Throwable failure) {
            try {
                session.close();
            } finally {
                request.close();
            }
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
        projections.clear();
        localSyncs.clear();
        if (retired != null) {
            retired.close();
        }
    }

    private State requireCurrent() {
        executionAccess.run();
        if (current == null) {
            throw new IllegalStateException("entity expression runtime has no current target");
        }
        return current;
    }

    @Override
    public void close() {
        clear();
    }

    /**
     * Borrows the current evaluation synchronously inside one owner invocation.
     *
     * <p>
     * Requires: the body must not retain the evaluation or its call-local contexts after return. A body failure
     * participates in the enclosing round's effect and cleanup outcome.
     */
    @FunctionalInterface
    public interface Body<T> {
        T run(Evaluation evaluation) throws Throwable;
    }

    public record InvocationInputs(com.elfmcys.ysm.expression.host.InvocationInputs<?> productionInputs) {
        public InvocationInputs {
            Objects.requireNonNull(productionInputs, "productionInputs");
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

        /** Binds one immutable root sample before the entry's first expression. */
        public void captureSubjectSample(com.elfmcys.ysm.expression.host.SubjectSample sample) {
            ownerContext.captureSubjectInputs(sample);
        }

        public com.elfmcys.ysm.expression.host.SubjectSample subjectSample() {
            return ownerContext.subjectSample();
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

        public void endAnimationContext() {
            setAnimationContext(null);
        }

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
                programFailed(program.location(), program.binding(), cause);
                return 0.0f;
            }
        }

        public boolean evaluate(BooleanProgram program) {
            Objects.requireNonNull(program, "program");
            try {
                return executeBoolean(program);
            } catch (Throwable cause) {
                programFailed(program.location(), program.binding(), cause);
                return false;
            }
        }

        public void execute(VoidProgram program) {
            executeVoid(program, ProgramArguments.empty());
        }

        public Object execute(UserFunctionProgram program, ProgramArguments arguments) {
            Objects.requireNonNull(program, "program");
            try {
                var context = beginProgram(program.location(), program.binding());
                try {
                    var result = state.aotInstance().executeValue(program.reference(), context, state.subjectBinding,
                        ownerContext, arguments);

                    return result;
                } finally {
                    ownerContext.releaseExpression();
                }
            } catch (Throwable cause) {
                programFailed(program.location(), program.binding(), cause);
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
                programFailed(handler.location(), handler.binding(), cause);
                return null;
            }
        }

        public Optional<Throwable> failure() {
            return Optional.ofNullable(failure);
        }

        private void recordFailure(Throwable cause) {
            if (failure == null)
                failure = cause;
        }

        private boolean readConfigBoolean(long expectedGeneration, ConfigActionRoute route) {
            requireGeneration(expectedGeneration);
            var location = configLocation(route, ProgramRole.CONFIG_READ_BOOL, "read");
            return evaluate(state.execution.requireBooleanProgram(location));
        }

        private float readConfigFloat(long expectedGeneration, ConfigActionRoute route) {
            requireGeneration(expectedGeneration);
            var location = configLocation(route, ProgramRole.CONFIG_READ_F32, "read");
            return evaluate(state.execution.requireFloatProgram(location));
        }

        private void writeConfigBoolean(long expectedGeneration, ConfigActionRoute route, boolean value) {
            requireGeneration(expectedGeneration);
            var location = configLocation(route, ProgramRole.CONFIG_WRITE_BOOL, "write");
            executeVoid(state.execution.requireVoidProgram(location), ProgramArguments.ofBoolean(value));
        }

        private void writeConfigFloat(long expectedGeneration, ConfigActionRoute route, float value) {
            requireGeneration(expectedGeneration);
            var location = configLocation(route, ProgramRole.CONFIG_WRITE_F32, "write");
            executeVoid(state.execution.requireVoidProgram(location), ProgramArguments.ofFloat(value));
        }

        private void executeConfigLabel(long expectedGeneration, ConfigActionRoute route, String labelName,
            int labelIndex) {
            requireGeneration(expectedGeneration);
            Objects.requireNonNull(route, "route");
            var location = new ModelProgramInventory.SemanticLocation(state.targetId, route.labelLocation(),
                ProgramRole.CONFIG_LABEL_ACTION, route.labelDiscriminator(labelName, labelIndex));
            executeVoid(state.execution.requireVoidProgram(location), ProgramArguments.empty());
        }

        public void dispatchPendingDeferred() {
            if (phase == MolangContext.LogicalPhase.OBSERVATION)
                throw new IllegalStateException("deferred dispatch requires an effect-capable round");
            drainDeferred(state, this);
        }

        public Object executeTransient(TransientExpressionSession session) throws Throwable {
            Objects.requireNonNull(session, "session");
            state.aotInstance();
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

                return result;
            } catch (Throwable cause) {
                acceptExecutionFailure(provenance(state, session.sourceLocation(), -1), cause);
                return ExpressionValues.UNSET;
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
            var context = beginProgram(program.location(), program.binding());
            try {
                if (program.binding() instanceof ProgramBinding.Aot aot)
                    return state.aotInstance().executeFloat(aot.reference(), context, state.subjectBinding,
                        ownerContext, arguments);
                var interpreted = (ProgramBinding.Interpreted)program.binding();
                var use = state.execution.interpreterUse();
                return use.instance().executeFloat(use.program(interpreted.sourceId()), context, state.subjectBinding,
                    ownerContext, interpretedArguments(arguments));
            } finally {
                ownerContext.releaseExpression();
            }
        }

        private boolean executeBoolean(BooleanProgram program) throws Throwable {
            var context = beginProgram(program.location(), program.binding());
            try {
                if (program.binding() instanceof ProgramBinding.Aot aot)
                    return state.aotInstance().executeBoolean(aot.reference(), context, state.subjectBinding,
                        ownerContext, ProgramArguments.empty());
                var interpreted = (ProgramBinding.Interpreted)program.binding();
                var use = state.execution.interpreterUse();
                return use.instance().executeBoolean(use.program(interpreted.sourceId()), context, state.subjectBinding,
                    ownerContext, List.of());
            } finally {
                ownerContext.releaseExpression();
            }
        }

        private void executeVoid(VoidProgram program, ProgramArguments arguments) {
            Objects.requireNonNull(program, "program");
            try {
                var context = beginProgram(program.location(), program.binding());
                try {
                    if (program.binding() instanceof ProgramBinding.Aot aot)
                        state.aotInstance().executeVoid(aot.reference(), context, state.subjectBinding, ownerContext,
                            arguments);
                    else {
                        var interpreted = (ProgramBinding.Interpreted)program.binding();
                        var use = state.execution.interpreterUse();
                        use.instance().executeVoid(use.program(interpreted.sourceId()), context, state.subjectBinding,
                            ownerContext, interpretedArguments(arguments));
                    }
                } finally {
                    ownerContext.releaseExpression();
                }
            } catch (Throwable cause) {
                programFailed(program.location(), program.binding(), cause);
            }
        }

        private MolangContext beginProgram(ModelProgramInventory.SemanticLocation location, ProgramBinding binding) {
            // Target installation pairs program bindings with this evaluation's fixed state.
            ownerContext.bindExpression(location.location(), binding.programId(), persistentActionWindow);
            return ownerContext;
        }

        private void programFailed(ModelProgramInventory.SemanticLocation location, ProgramBinding binding,
            Throwable cause) {
            acceptExecutionFailure(provenance(state, location.location(), binding.programId()), cause);
        }

        private void acceptExecutionFailure(InvocationProvenance source, Throwable cause) {
            if (cause instanceof Error error)
                throw error;
            ownerContext.reportFailure(source, cause);
            if (cause instanceof ExecutionFailure budget && budget.isUserFunctionDepthExceeded())
                return;
            var terminal = new ExecutionFailure(ExecutionFailure.Reason.INSTANCE_EXECUTION, source,
                "expression execution failed", cause);
            recordFailure(terminal);
            throw terminal;
        }

    }

    private static InvocationProvenance provenance(State state, String location, int programId) {
        return new InvocationProvenance(state.targetId, location, programId, state.targetGenerationId,
            state.entityGeneration);
    }

    private static List<InterpretedValue> interpretedArguments(ProgramArguments arguments) {
        if (arguments.size() == 0)
            return List.of();
        var values = new ArrayList<InterpretedValue>(arguments.size());
        for (int index = 0; index < arguments.size(); index++) {
            var type = arguments.type(index);
            if (arguments.isMissing(index))
                values.add(InterpretedValue.missing(type));
            else if (type == dev.semaweave.BuiltinType.F32)
                values.add(InterpretedValue.ofFloat(arguments.floatValue(index)));
            else if (type == dev.semaweave.BuiltinType.BOOL)
                values.add(InterpretedValue.ofBoolean(arguments.booleanValue(index)));
            else if (type.equals(EVENT_ARGUMENTS))
                values.add(InterpretedValue.ofForeign(INTERPRETED_EVENT_ARGUMENTS, arguments.referenceValue(index)));
            else
                throw new IllegalArgumentException("unsupported interpreted host argument " + type);
        }
        return values;
    }

    private static final class State implements AutoCloseable {
        private final Object installationKey = new Object();
        private AnimationStore.Execution execution;
        private RoamingState roamingState;
        private final Object targetGeneration;
        private final long targetGenerationId;
        private final long entityGeneration;
        private final String targetId;
        private HostLinkage linkage;
        private final boolean typedSubject;
        @Nullable
        private net.minecraft.world.entity.Entity subject;
        private dev.semaweave.SubjectBinding subjectBinding;
        private ComponentScope components;
        private List<VoidProgram> playerInit;
        private List<VoidProgram> playerUpdate;
        private List<VoidProgram> sync;
        private List<VoidProgram> defer;
        private volatile boolean accepting = true;
        private boolean roamingInitialized;
        private boolean armInitializationAttempted;
        private ArmPair armConnection;

        private State(AnimationStore.Execution execution, long entityGeneration,
            @Nullable net.minecraft.world.entity.Entity subject, @Nullable ComponentScope components) {
            this.execution = execution;
            this.roamingState = execution.roamingState();
            var metadata = execution.metadata();
            this.targetGeneration = metadata.executionGeneration();
            this.targetGenerationId = metadata.generationId();
            this.entityGeneration = entityGeneration;
            this.targetId = metadata.targetId();
            this.linkage = metadata.productionLinkage();
            this.subject = subject;
            this.components = components;
            this.typedSubject = subject != null;
            this.subjectBinding =
                execution.interpreted() ? execution.interpreterUse().environment().bindSubject(subject)
                    : execution.aotInstance().bindSubject(subject);
            var handlers = execution.store().eventHandlers();
            playerInit = voidHandlers(handlers, "player_init", ProgramRole.EVENT_PLAYER_INIT, targetGeneration);
            playerUpdate = voidHandlers(handlers, "player_update", ProgramRole.EVENT_PLAYER_UPDATE, targetGeneration);
            sync = voidHandlers(handlers, "sync", ProgramRole.EVENT_SYNC, targetGeneration);
            defer = voidHandlers(handlers, "defer", ProgramRole.EVENT_DEFER, targetGeneration);
        }

        private static State create(AnimationStore store, long entityGeneration, RoamingRole role,
            ComponentScope components) {
            var execution = store.openExecution(components, role);
            try {
                return new State(execution, entityGeneration, components.subject(), components);
            } catch (Throwable failure) {
                execution.close();
                throw failure;
            }
        }

        private static State create(AnimationStore store, long entityGeneration, RoamingRole role,
            dev.semaweave.RuntimeLinkage<MolangContext> linkage) {
            var execution = store.openExecution(linkage, role);
            try {
                return new State(execution, entityGeneration, null, null);
            } catch (Throwable failure) {
                execution.close();
                throw failure;
            }
        }

        private static List<VoidProgram> voidHandlers(Map<String, ? extends List<? extends EventProgram>> handlers,
            String name, ProgramRole role, Object generation) {
            var values = handlers.get(name);
            if (values == null) {
                values = List.of();
            }
            var result = new ArrayList<VoidProgram>(values.size());
            for (var value : values) {
                if (!(value instanceof VoidProgram program) || program.location().role() != role
                    || !program.binding().executionGeneration().equals(generation)) {
                    throw new IllegalArgumentException("invalid " + name + " event binding");
                }
                result.add(program);
            }
            return List.copyOf(result);
        }

        @Override
        public void close() {
            var retired = execution;
            execution = null;
            subject = null;
            subjectBinding = null;
            components = null;
            linkage = null;
            roamingState = null;
            playerInit = List.of();
            playerUpdate = List.of();
            sync = List.of();
            defer = List.of();
            armConnection = null;
            accepting = false;
            if (retired != null)
                retired.close();
        }

        private CompiledModuleInstance<MolangContext> aotInstance() {
            if (execution == null)
                throw new IllegalStateException("installation has ended");
            if (execution.interpreted())
                throw new IllegalStateException("Interpreted installations do not admit AOT transient execution");
            return execution.aotInstance();
        }
    }

    private record DeferredCapture(State state, Evaluation evaluation, AnimationExecutionState animation,
        ControllerExecutionState controller, EventArguments arguments, InvocationProvenance source) {
    }

}
