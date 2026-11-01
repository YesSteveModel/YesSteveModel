package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.*;

import dev.semaweave.ArgumentScratch;
import dev.semaweave.ExecutionFrame;
import dev.semaweave.ExecutionLimits;
import dev.semaweave.CompiledRoamingState;
import dev.semaweave.RoamingWriteContext;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Carries one invocation round and exclusively lends its execution frame to one top-level expression at a time.
 *
 * <p>Invariant: a top-level call fixes ACTION permission to the host window intersected with the compiled
 * entry permission. Nested calls cannot elevate that permission. Cancellation and generation eligibility remain dynamic.
 * A typed program fallback does not clear the round's first failure or authorize a batch commit.
 *
 * <p>Execution: the owner serializes round setup, expression binding, execution, release, and close.
 * Active calls use the recorded execution thread. Live subject and host views are borrowed inputs, not snapshots.
 *
 * <p>Ownership: providers must not retain the context, event, views, or frame beyond the current call.
 * Deferred requests must contain copied captures rather than borrowed execution state.
 *
 * <p>Seam: implements [call permissions and budgets](docs/architecture/animation/molang-runtime.md#调用许可与预算) and [invocations and effects](docs/architecture/animation/molang-runtime.md#invocation-与效果).
 */
public final class MolangContext<E extends Entity> implements RoamingWriteContext, MolangContextView<E>, ExecutionFrame {
    public enum LogicalPhase {
        OBSERVATION, UPDATE, ACTION
    }

    private EntityView entityView;
    @Nullable
    private E subject;
    private RenderView renderView;
    private final LogicalPhase phase;
    private EffectValidator hostAccess;
    private CompiledRoamingState roamingState;
    private BooleanSupplier cancellation;
    private BooleanSupplier currentGeneration;
    private Optional<EffectBatch> effects;
    private final InvocationProvenance ownerProvenance;
    private ExecutionLimits limits = ExpressionPolicy.EXECUTION_LIMITS;
    private int[] userFunctions = new int[ExpressionPolicy.EXECUTION_LIMITS.maxUserFunctionDepth()];
    private int userFunctionDepth;
    private Thread ownerThread;
    private boolean bound;
    private boolean hostAllowsAction;
    private boolean active;
    private boolean allowAction;
    private boolean closed;
    private int loopIterations;
    private int providerCalls;
    private int controlTransfers;
    private String programLocation;
    private int programId = -1;
    private ControllerExecutionState controllerContext;
    private AnimationExecutionState animationContext;
    private boolean controllerSelected;
    private boolean animationSelected;
    private CallFailure firstFailure;
    private java.util.function.BiConsumer<DeferredRequest, InvocationProvenance> deferredSubmit;

    public void deferredSubmit(java.util.function.BiConsumer<DeferredRequest, InvocationProvenance> handler) {
        requireIdle();
        deferredSubmit = Objects.requireNonNull(handler);
    }

    public record CallFailure(InvocationProvenance provenance, int programId, String path, String callee,
        Throwable cause) {
    }

    private MolangContext(@Nullable E subject, EntityView entityView, RenderView renderView, LogicalPhase phase,
        EffectValidator hostAccess, CompiledRoamingState roamingState, InvocationProvenance provenance,
        BooleanSupplier cancellation, BooleanSupplier currentGeneration, Optional<EffectBatch> effects) {
        this.subject = subject;
        this.entityView = Objects.requireNonNull(entityView, "entityView");
        this.renderView = Objects.requireNonNull(renderView, "renderView");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.hostAccess = Objects.requireNonNull(hostAccess, "hostAccess");
        this.ownerProvenance = Objects.requireNonNull(provenance, "provenance");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.currentGeneration = Objects.requireNonNull(currentGeneration, "currentGeneration");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.roamingState = Objects.requireNonNull(roamingState, "roamingState");
    }

    public static MolangContext<Entity> observation(EntityView entityView, RenderView renderView,
        EffectValidator hostAccess, CompiledRoamingState roamingState, InvocationProvenance provenance,
        BooleanSupplier cancellation, BooleanSupplier currentGeneration) {
        return new MolangContext<>(subjectOrNull(entityView), entityView, renderView, LogicalPhase.OBSERVATION,
            hostAccess, roamingState, provenance, cancellation, currentGeneration, Optional.empty());
    }

    public static <E extends Entity> MolangContext<E> observation(E subject, InvocationInputs<E> inputs,
        EffectValidator hostAccess, CompiledRoamingState roamingState, InvocationProvenance provenance,
        BooleanSupplier cancellation, BooleanSupplier currentGeneration) {
        return new MolangContext<>(Objects.requireNonNull(subject, "subject"), inputs, inputs, LogicalPhase.OBSERVATION,
            hostAccess, roamingState, provenance, cancellation, currentGeneration, Optional.empty());
    }

    public static MolangContext<Entity> logical(EntityView entityView, RenderView renderView, LogicalPhase phase,
        EffectValidator hostAccess, CompiledRoamingState roamingState, InvocationProvenance provenance,
        BooleanSupplier cancellation, BooleanSupplier currentGeneration, int maximumEffectCommands) {
        if (phase == LogicalPhase.OBSERVATION) {
            throw new IllegalArgumentException("logical context requires UPDATE or ACTION phase");
        }
        return new MolangContext<>(subjectOrNull(entityView), entityView, renderView, phase, hostAccess, roamingState,
            provenance, cancellation, currentGeneration,
            Optional.of(new EffectBatch(maximumEffectCommands, provenance)));
    }

    public static <E extends Entity> MolangContext<E> logical(E subject, InvocationInputs<E> inputs, LogicalPhase phase,
        EffectValidator hostAccess, CompiledRoamingState roamingState, InvocationProvenance provenance,
        BooleanSupplier cancellation, BooleanSupplier currentGeneration, int maximumEffectCommands) {
        if (phase == LogicalPhase.OBSERVATION) {
            throw new IllegalArgumentException("logical context requires UPDATE or ACTION phase");
        }
        return new MolangContext<>(Objects.requireNonNull(subject, "subject"), inputs, inputs, phase, hostAccess,
            roamingState, provenance, cancellation, currentGeneration,
            Optional.of(new EffectBatch(maximumEffectCommands, provenance)));
    }

    public EntityView entityView() {
        return entityView;
    }

    public static Object subjectOrNull(MolangContext state) {
        return state.subject;
    }

    public static void validateInvocation(MolangContext state) {
        state.verifyEligible();
    }

    public E subject() {
        requireRead();
        if (subject == null) {
            throw new IllegalStateException("this invocation has no typed Minecraft subject");
        }
        return subject;
    }

    public RenderView renderView() {
        return renderView;
    }

    public LogicalPhase phase() {
        return phase;
    }

    public InvocationProvenance provenance() {
        return !bound ? ownerProvenance : new InvocationProvenance(ownerProvenance.targetId(), programLocation,
            programId, ownerProvenance.targetGeneration(), ownerProvenance.entityGeneration());
    }

    public CompiledRoamingState roamingState() {
        return roamingState;
    }

    public Optional<EffectBatch> effects() {
        return effects;
    }

    /**
     * The borrowed production inputs for this invocation, or {@code null} when the host view is not a production
     * source.
     *
     * <p>
     * A declaration method that implements its own business reads the entity, render and execution-scope inputs here
     * instead of through the capability access. The reference is valid only for this invocation and must not be
     * retained.
     */
    @SuppressWarnings("unchecked")
    public InvocationInputs<E> productionInputs() {
        var inputs = entityView.productionInputs();
        if (inputs == null) {
            return null;
        }
        if (subject != null && inputs.entity() != subject) {
            throw failure(ExecutionFailure.Reason.STALE_GENERATION, "production inputs belong to another subject");
        }
        return (InvocationInputs<E>)inputs;
    }

    /**
     * Stages an authorized prepared effect or copied deferred request without dispatching external effects.
     *
     * <p>Failure: missing ACTION permission, stale eligibility, unsupported requests, capacity, or host
     * prevalidation failure rejects submission. Submission is not a promise that the whole round will commit.
     *
     * <p>Ownership: an accepted effect remains referenced by the round until commit or discard.
     * The batch does not acquire closing ownership of resources captured by an effect.
     */
    public void submit(EffectRequest request) {
        requireAction();
        Objects.requireNonNull(request, "request");
        if (request instanceof DeferredRequest capture) {
            if (deferredSubmit == null)
                throw new IllegalStateException("invocation has no deferred scheduler");
            deferredSubmit.accept(capture, provenance());
            return;
        }
        if (!(request instanceof PreparedEffect effect))
            throw new IllegalArgumentException("unsupported effect request");
        var batch = requireEffects();
        batch.requireCapacity();
        hostAccess.prevalidateEffect(effect, this);
        batch.append(effect, provenance());
    }

    public void verifyEligible() {
        if (closed)
            throw new IllegalStateException("Molang round is closed");
        if (cancellation.getAsBoolean()) {
            throw failure(ExecutionFailure.Reason.CANCELLATION, "invocation cancelled");
        }
        if (!currentGeneration.getAsBoolean()) {
            throw failure(ExecutionFailure.Reason.STALE_GENERATION, "invocation generation is no longer current");
        }
    }

    @Override
    public void authorizeRoamingWrite(CompiledRoamingState state) {
        verifyEligible();
        // Storage keeps its host-window policy even when the program's ACTION calls are forbidden.
        if (phase == LogicalPhase.OBSERVATION || effects.isEmpty() || (bound && !hostAllowsAction)) {
            throw failure(ExecutionFailure.Reason.EFFECT_NOT_AUTHORIZED,
                "observation context cannot mutate roaming state");
        }
        if (state != roamingState) {
            throw failure(ExecutionFailure.Reason.STALE_GENERATION, "roaming state does not belong to this invocation");
        }
    }

    void commit(EffectSink sink) {
        requireEffects().commit(sink, this::verifyEligible);
    }

    void commit(EffectSink sink, Runnable beforeCommit) {
        requireEffects().commit(sink, this::verifyEligible, beforeCommit);
    }

    void discard() {
        effects.ifPresent(EffectBatch::discard);
    }

    ExecutionFailure failure(ExecutionFailure.Reason reason, String message) {
        return new ExecutionFailure(reason, provenance(), message);
    }

    private EffectBatch requireEffects() {
        return effects.orElseThrow(() -> failure(ExecutionFailure.Reason.EFFECT_NOT_AUTHORIZED,
            "observation context has no effect capability"));
    }

    @Nullable
    private static Entity subjectOrNull(EntityView view) {
        try {
            return view.entity();
        } catch (IllegalStateException unavailable) {
            return null;
        }
    }

    public void configureLimits(ExecutionLimits value) {
        requireIdle();
        limits = Objects.requireNonNull(value, "limits");
        if (userFunctions.length != value.maxUserFunctionDepth())
            userFunctions = new int[value.maxUserFunctionDepth()];
    }

    public void bindExpression(String location, int id) {
        bindExpression(location, id, phase != LogicalPhase.OBSERVATION);
    }

    /**
     * Selects one expression while the round is idle and eligible.
     *
     * <p>Execution: the caller must release this binding after the expression's top-level cleanup.
     * hostWindow can restrict the round's permission; it cannot grant ACTION in an observation round.
     */
    public void bindExpression(String location, int id, boolean hostWindow) {
        requireIdle();
        verifyEligible();
        Objects.requireNonNull(location, "location");
        programLocation = location;
        programId = id;
        hostAllowsAction = hostWindow && phase != LogicalPhase.OBSERVATION;
        bound = true;
    }

    public void releaseExpression() {
        if (active || !bound)
            throw new IllegalStateException("expression is not ready for release");
        programLocation = null;
        programId = -1;
        hostAllowsAction = false;
        bound = false;
    }

    public void setControllerContext(@Nullable ControllerExecutionState value) {
        requireIdle();
        controllerContext = value;
        controllerSelected = true;
    }

    public void setAnimationContext(@Nullable AnimationExecutionState value) {
        requireIdle();
        animationContext = value;
        animationSelected = true;
    }

    /**
     * Borrows the bound frame for one top-level call and resets that call's execution budgets.
     *
     * <p>Requires: the owner has bound an expression and no call is active. The caller must pair success
     * with endTopLevel() on this thread, including after program failure.
     */
    @Override
    public void beginTopLevel(boolean entryAllowsAction) {
        if (active || !bound)
            throw new IllegalStateException("execution frame is not exclusively borrowed");
        verifyEligible();
        ownerThread = Thread.currentThread();
        allowAction = entryAllowsAction && hostAllowsAction;
        loopIterations = 0;
        providerCalls = 0;
        controlTransfers = 0;
        userFunctionDepth = 0;
        active = true;
    }

    /**
     * Releases this thread's frame borrow and clears call-local permission and budget state.
     *
     * <p>Failure: unbalanced user-function scopes report FRAME_STATE after releasing the active borrow.
     * Round failure and staged effects remain under the enclosing transaction's control.
     */
    @Override
    public void endTopLevel() {
        requireActive();
        boolean balanced = userFunctionDepth == 0;
        userFunctionDepth = 0;
        java.util.Arrays.fill(userFunctions, 0);
        loopIterations = 0;
        providerCalls = 0;
        controlTransfers = 0;
        allowAction = false;
        active = false;
        ownerThread = null;
        if (!balanced)
            throw failure(ExecutionFailure.Reason.FRAME_STATE, "user-function scopes were not balanced");
    }

    @Override
    public boolean actionAllowed() {
        return active && allowAction;
    }

    @Override
    public void reportFailure(int id, String path, String callee, Throwable cause) {
        requireActive();
        var source = provenance();
        if (firstFailure == null)
            firstFailure = new CallFailure(source, id, path, callee, cause);
        com.elfmcys.ysm.YesSteveModel.LOGGER.log(AnimationDiagnostics.errorLevel(productionInputs()),
            "Molang call failure: owner={} program={} node={} callee={} cause={}", source, id, path, callee,
            cause.toString());
    }

    public CallFailure firstFailure() {
        return firstFailure;
    }

    public boolean isActive() {
        return active && ownerThread == Thread.currentThread();
    }

    /** Owner scheduling has its own round qualification, independent of the most recent expression. */
    public void stageOwner(PreparedEffect command) {
        requireIdle();
        verifyEligible();
        var batch = requireEffects();
        batch.requireCapacity();
        hostAccess.prevalidateEffect(command, this);
        batch.append(command, ownerProvenance);
    }

    /**
     * Ends an idle round and drops its borrowed input references before external dispatch.
     *
     * <p>Requires: all expression bindings and active calls have ended. Close neither commits effects
     * nor closes the host objects that the round borrowed. A closed context cannot start another round.
     */
    public void closeRound() {
        requireIdle();
        closed = true;
        subject = null;
        entityView = null;
        renderView = null;
        hostAccess = null;
        roamingState = null;
        cancellation = null;
        currentGeneration = null;
        controllerContext = null;
        animationContext = null;
        effects = Optional.empty();
        deferredSubmit = null;
    }

    private void requireIdle() {
        if (closed || bound || active)
            throw new IllegalStateException("Molang context is not idle");
    }

    private void requireActive() {
        if (!active || ownerThread != Thread.currentThread())
            throw new IllegalStateException("execution frame is not active on its owner thread");
    }

    private void requireRead() {
        requireActive();
        verifyEligible();
    }

    private void requireAction() {
        requireRead();
        if (!allowAction)
            throw new IllegalStateException("entry forbids ACTION");
    }

    @Override
    public void chargeLoopIteration() {
        chargeValueWork(1);
    }

    @Override
    public void chargeValueWork(int elements) {
        requireActive();
        if (elements < 0)
            throw new IllegalArgumentException("negative value work");
        if (elements > limits.maxLoopIterations() - loopIterations) {
            throw new ExecutionFailure(ExecutionFailure.Reason.LOOP_BUDGET, provenance(), "value/loop work budget exceeded", limits.maxLoopIterations());
        }
        loopIterations += elements;
    }

    @Override
    public void chargeProviderCall() {
        requireActive();
        if (providerCalls == limits.maxProviderCalls()) {
            throw new ExecutionFailure(ExecutionFailure.Reason.PROVIDER_BUDGET, provenance(), "provider call budget exceeded", limits.maxProviderCalls());
        }
        providerCalls++;
    }

    @Override
    public void chargeControlTransfer() {
        requireActive();
        if (controlTransfers == limits.maxControlTransfers()) {
            throw new ExecutionFailure(ExecutionFailure.Reason.CONTROL_TRANSFER_BUDGET, provenance(), "control transfer budget exceeded", limits.maxControlTransfers());
        }
        controlTransfers++;
    }

    @Override
    public boolean tryEnterUserFunction(int programId) {
        requireActive();
        if (programId < 0)
            throw new IllegalArgumentException("negative program id");
        if (userFunctionDepth == limits.maxUserFunctionDepth()) {
            throw new ExecutionFailure(ExecutionFailure.Reason.USER_FUNCTION_DEPTH, provenance(), "user-function depth budget exceeded", limits.maxUserFunctionDepth());
        }

        userFunctions[userFunctionDepth++] = programId;
        return true;
    }

    @Override
    public void exitUserFunction(int programId) {
        requireActive();
        if (userFunctionDepth == 0 || userFunctions[--userFunctionDepth] != programId) {
            userFunctionDepth = 0;
            throw failure(ExecutionFailure.Reason.FRAME_STATE, "user-function scopes were not balanced");
        }
    }

    @Override
    public ArgumentScratch acquireArgumentScratch(int size) {
        throw new UnsupportedOperationException("closed programs have no generic argument scratch");
    }

    @Override
    public void releaseArgumentScratch(ArgumentScratch scratch) {
        throw new UnsupportedOperationException("closed programs have no generic argument scratch");
    }

    public ExecutionLimits limits() {
        return limits;
    }

    @Override
    public MolangInputs inputs() {
        requireRead();
        var source = productionInputs();
        var controller = controllerState();
        var animation = animationState();
        return new MolangInputs(source == null ? 0 : source.partialTick(), source == null ? 0 : source.renderTickDelta(),
            controller == null ? null : controller.input(), animation == null ? null : animation.input());
    }

    public @Nullable AnimationExecutionState animationState() {
        if (animationSelected) return animationContext;
        return productionInputs() instanceof com.elfmcys.ysm.geckolib3.core.processor.MinecraftRuntimeInputs<?> value
            ? value.animationState() : null;
    }

    public @Nullable ControllerExecutionState controllerState() {
        if (controllerSelected) return controllerContext;
        return productionInputs() instanceof com.elfmcys.ysm.geckolib3.core.processor.MinecraftRuntimeInputs<?> value
            ? value.controllerState() : null;
    }
}
