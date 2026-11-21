package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.*;

import dev.semaweave.ExecutionFrame;
import dev.semaweave.ExecutionLimits;
import dev.semaweave.CompiledRoamingState;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Carries one invocation round and exclusively lends its execution frame to one top-level expression at a time.
 *
 * <p>
 * Invariant: a top-level call fixes ACTION permission to the host window intersected with the compiled entry
 * permission. Nested calls cannot elevate that permission. Installation eligibility belongs to top-level admission. A
 * typed program fallback does not clear the round's first failure or undo a domain action.
 *
 * <p>
 * Execution: the owner serializes round setup, expression binding, execution, release, and close. Live subject and host
 * views are borrowed inputs, not snapshots.
 *
 * <p>
 * Ownership: providers must not retain the context, event, views, or frame beyond the current call. Deferred requests
 * must contain copied captures rather than borrowed execution state.
 *
 * <p>
 * Seam: implements [call permissions and budgets](docs/architecture/animation/molang-runtime.md#调用许可与预算) and
 * [invocations and effects](docs/architecture/animation/molang-runtime.md#invocation-与效果).
 */
public final class MolangContext<E extends Entity> implements MolangContextView<E>, ExecutionFrame {
    public enum LogicalPhase {
        OBSERVATION, UPDATE, ACTION
    }

    private dev.semaweave.SubjectBinding originBinding;
    private EntityView entityView;
    @Nullable
    private E subject;
    private RenderView renderView;
    private final LogicalPhase phase;
    private RoamingState roamingState;
    private final InvocationProvenance ownerProvenance;
    private ExecutionLimits limits = ExpressionPolicy.EXECUTION_LIMITS;
    private int userFunctionDepth;
    private boolean bound;
    private boolean hostAllowsAction;
    private boolean allowAction;
    private String programLocation;
    private int programId = -1;
    private ControllerExecutionState controllerContext;
    private AnimationExecutionState animationContext;
    private boolean controllerSelected;
    private boolean animationSelected;
    private CallFailure firstFailure;
    private java.util.function.BiConsumer<EventArguments, InvocationProvenance> deferredSubmit;
    private SubjectSample subjectSample = SubjectSample.MISSING;
    private PlayerQueryProjection playerProjection = PlayerQueryProjection.MISSING;

    /** Captures root inputs after producers have prepared the current entry. */
    public void captureSubjectInputs(SubjectSample sample) {
        subjectSample = sample;
        playerProjection = subject instanceof net.minecraft.client.player.RemotePlayer
            ? ((PlayerQueryAccess)subject).ysm$playerQueryProjection() : PlayerQueryProjection.MISSING;
    }

    public SubjectSample subjectSample() {
        return subjectSample;
    }

    public PlayerQueryProjection playerProjection() {
        return playerProjection;
    }

    public void deferredSubmit(java.util.function.BiConsumer<EventArguments, InvocationProvenance> handler) {
        deferredSubmit = Objects.requireNonNull(handler);
    }

    public record CallFailure(InvocationProvenance provenance, Throwable cause) {
    }

    private MolangContext(@Nullable E subject, EntityView entityView, RenderView renderView, LogicalPhase phase,
        RoamingState roamingState, InvocationProvenance provenance) {
        this.subject = subject;
        this.entityView = Objects.requireNonNull(entityView, "entityView");
        this.renderView = Objects.requireNonNull(renderView, "renderView");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.roamingState = Objects.requireNonNull(roamingState, "roamingState");
        this.ownerProvenance = Objects.requireNonNull(provenance, "provenance");
    }

    @SuppressWarnings("unchecked")
    private MolangContext(Entity subject, MolangContext<?> origin) {
        this.subject = (E)subject;
        this.entityView = origin.entityView;
        this.renderView = origin.renderView;
        this.phase = origin.phase;
        this.roamingState = origin.roamingState;
        this.ownerProvenance = origin.ownerProvenance;
        this.allowAction = origin.allowAction;
        this.bound = origin.bound;
        this.programLocation = origin.programLocation;
        this.programId = origin.programId;
        this.deferredSubmit = origin.deferredSubmit;
        this.controllerContext = origin.controllerContext;
        this.animationContext = origin.animationContext;
        this.controllerSelected = origin.controllerSelected;
        this.animationSelected = origin.animationSelected;
        this.subjectSample = ((SubjectSampleAccess)subject).ysm$subjectSample();
        this.playerProjection = subject instanceof net.minecraft.client.player.RemotePlayer
            ? ((PlayerQueryAccess)subject).ysm$playerQueryProjection() : PlayerQueryProjection.MISSING;
    }

    public void bindOrigin(dev.semaweave.SubjectBinding binding) {
        originBinding = binding;
    }

    public static MolangContext origin(ExecutionFrame frame) {
        return (MolangContext)frame;
    }

    public static dev.semaweave.SubjectBinding originBinding(ExecutionFrame frame) {
        return ((MolangContext)frame).originBinding;
    }

    public static PlayerQueryProjection projection(MolangContextView<?> context) {
        return ((MolangContext<?>)context).playerProjection;
    }

    public static dev.semaweave.ChildContext<MolangContext> derive(MolangContext parent, Entity subject,
        dev.semaweave.SubjectBindingFactory bindings, ExecutionFrame frame) {
        MolangContext<?> origin = origin(frame);
        if (subject == null || subject.isRemoved() || subject.level() != origin.subject.level())
            return null;
        return new dev.semaweave.ChildContext<>(new MolangContext<>(subject, origin), bindings.bind(subject));
    }

    public static MolangContext<Entity> observation(EntityView entity, RenderView render, CompiledRoamingState roaming,
        InvocationProvenance provenance) {
        return observation(entity, render, new RoamingState.Aot(roaming), provenance);
    }

    public static MolangContext<Entity> observation(EntityView entity, RenderView render, RoamingState roaming,
        InvocationProvenance provenance) {
        return new MolangContext<>(subjectOrNull(entity), entity, render, LogicalPhase.OBSERVATION, roaming,
            provenance);
    }

    public static <E extends Entity> MolangContext<E> observation(E subject, InvocationInputs<E> inputs,
        CompiledRoamingState roaming, InvocationProvenance provenance) {
        return observation(subject, inputs, new RoamingState.Aot(roaming), provenance);
    }

    public static <E extends Entity> MolangContext<E> observation(E subject, InvocationInputs<E> inputs,
        RoamingState roaming, InvocationProvenance provenance) {
        return new MolangContext<>(Objects.requireNonNull(subject), inputs, inputs, LogicalPhase.OBSERVATION, roaming,
            provenance);
    }

    public static MolangContext<Entity> logical(EntityView entity, RenderView render, LogicalPhase phase,
        CompiledRoamingState roaming, InvocationProvenance provenance) {
        return logical(entity, render, phase, new RoamingState.Aot(roaming), provenance);
    }

    public static MolangContext<Entity> logical(EntityView entity, RenderView render, LogicalPhase phase,
        RoamingState roaming, InvocationProvenance provenance) {
        if (phase == LogicalPhase.OBSERVATION)
            throw new IllegalArgumentException("logical context requires UPDATE or ACTION phase");
        return new MolangContext<>(subjectOrNull(entity), entity, render, phase, roaming, provenance);
    }

    public static <E extends Entity> MolangContext<E> logical(E subject, InvocationInputs<E> inputs, LogicalPhase phase,
        CompiledRoamingState roaming, InvocationProvenance provenance) {
        return logical(subject, inputs, phase, new RoamingState.Aot(roaming), provenance);
    }

    public static <E extends Entity> MolangContext<E> logical(E subject, InvocationInputs<E> inputs, LogicalPhase phase,
        RoamingState roaming, InvocationProvenance provenance) {
        if (phase == LogicalPhase.OBSERVATION)
            throw new IllegalArgumentException("logical context requires UPDATE or ACTION phase");
        return new MolangContext<>(Objects.requireNonNull(subject), inputs, inputs, phase, roaming, provenance);
    }

    public EntityView entityView() {
        return entityView;
    }

    public static Object subjectOrNull(MolangContext state) {
        return state.subject;
    }

    public E subject() {
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

    public RoamingState roamingState() {
        return roamingState;
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
        return (InvocationInputs<E>)entityView.productionInputs();
    }

    @Override
    public void defer(float delay, float... values) {
        requireAction();
        if (deferredSubmit == null)
            throw new IllegalStateException("invocation has no deferred scheduler");
        deferredSubmit.accept(EventArguments.deferred(delay, values), provenance());
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
        limits = Objects.requireNonNull(value, "limits");
    }

    public void bindExpression(String location, int id) {
        bindExpression(location, id, phase != LogicalPhase.OBSERVATION);
    }

    /**
     * Selects one expression while the round is idle and eligible.
     *
     * <p>
     * Execution: the caller must release this binding after the expression's top-level cleanup. hostWindow can restrict
     * the round's permission; it cannot grant ACTION in an observation round.
     */
    public void bindExpression(String location, int id, boolean hostWindow) {
        Objects.requireNonNull(location, "location");
        programLocation = location;
        programId = id;
        hostAllowsAction = hostWindow && phase != LogicalPhase.OBSERVATION;
        bound = true;
    }

    public void releaseExpression() {
        programLocation = null;
        programId = -1;
        hostAllowsAction = false;
        bound = false;
    }

    public void setControllerContext(@Nullable ControllerExecutionState value) {
        controllerContext = value;
        controllerSelected = true;
    }

    public void setAnimationContext(@Nullable AnimationExecutionState value) {
        animationContext = value;
        animationSelected = true;
    }

    /**
     * Borrows the bound frame for one top-level call and resets that call's user-function depth.
     *
     * <p>
     * Requires: the owner has bound an expression and no call is active. The caller must pair success with
     * endTopLevel() on this thread, including after program failure.
     */
    @Override
    public void beginTopLevel(boolean entryAllowsAction) {
        allowAction = entryAllowsAction && hostAllowsAction;
        userFunctionDepth = 0;
    }

    /**
     * Clears call-local permission and depth state after the generated call has unwound.
     */
    @Override
    public void endTopLevel() {
        userFunctionDepth = 0;
        allowAction = false;
    }

    @Override
    public boolean actionAllowed() {
        return allowAction;
    }

    public void reportFailure(InvocationProvenance source, Throwable cause) {
        if (firstFailure == null)
            firstFailure = new CallFailure(source, cause);
        if (cause instanceof ExecutionFailure budget && budget.isUserFunctionDepthExceeded())
            com.elfmcys.ysm.YesSteveModel.LOGGER.log(AnimationDiagnostics.errorLevel(productionInputs()),
                "Molang user-function depth exhausted: source={} kind={} maximum={} phase={}", source, budget.reason(),
                budget.maximum().orElse(-1), budget.phase(), cause);
        else
            com.elfmcys.ysm.YesSteveModel.LOGGER.log(AnimationDiagnostics.errorLevel(productionInputs()),
                "Molang execution failure: source={}", source, cause);
    }

    public CallFailure firstFailure() {
        return firstFailure;
    }

    /**
     * Ends an idle round and drops its borrowed input references before external dispatch.
     *
     * <p>
     * Requires: all expression bindings and active calls have ended. Close neither commits effects nor closes the host
     * objects that the round borrowed. A closed context cannot start another round.
     */
    public void closeRound() {
        originBinding = null;
        subjectSample = SubjectSample.MISSING;
        playerProjection = PlayerQueryProjection.MISSING;
        subject = null;
        entityView = null;
        renderView = null;
        roamingState = null;
        controllerContext = null;
        animationContext = null;
        deferredSubmit = null;
    }

    private void requireAction() {
        if (!allowAction)
            throw new IllegalStateException("entry forbids ACTION");
    }

    @Override
    public void enterUserFunction() {
        if (userFunctionDepth == limits.maxUserFunctionDepth()) {
            throw new ExecutionFailure(ExecutionFailure.Reason.USER_FUNCTION_DEPTH, provenance(),
                "user-function depth exceeded", limits.maxUserFunctionDepth());
        }
        userFunctionDepth++;
    }

    @Override
    public void exitUserFunction() {
        userFunctionDepth--;
    }

    public ExecutionLimits limits() {
        return limits;
    }

    @Override
    public MolangInputs inputs() {
        var source = productionInputs();
        var controller = controllerState();
        var animation = animationState();
        return new MolangInputs(source == null ? 0 : source.partialTick(),
            source == null ? 0 : source.renderTickDelta(), controller == null ? null : controller.input(),
            animation == null ? null : animation.input());
    }

    public @Nullable AnimationExecutionState animationState() {
        if (animationSelected)
            return animationContext;
        return productionInputs() instanceof com.elfmcys.ysm.geckolib3.core.processor.MinecraftRuntimeInputs<?> value
            ? value.animationState() : null;
    }

    public @Nullable ControllerExecutionState controllerState() {
        if (controllerSelected)
            return controllerContext;
        return productionInputs() instanceof com.elfmcys.ysm.geckolib3.core.processor.MinecraftRuntimeInputs<?> value
            ? value.controllerState() : null;
    }
}
