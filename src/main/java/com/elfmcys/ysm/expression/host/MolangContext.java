package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.MolangActionView;
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

/** One round owns its inputs and effects; each expression exclusively borrows the execution state. */
public final class MolangContext<E extends Entity> implements RoamingWriteContext, MolangActionView<E>, ExecutionFrame {
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

    public void stage(EffectCommand command) {
        requireAction();
        var batch = requireEffects();
        hostAccess.prevalidateEffect(command, this);
        batch.append(command, provenance());
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
    public void stageOwner(EffectCommand command) {
        requireIdle();
        verifyEligible();
        var batch = requireEffects();
        hostAccess.prevalidateEffect(command, this);
        batch.append(command, ownerProvenance);
    }

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

    public void playSound(String id, String name, int mode, float volume, float pitch) {
        playSound(ModelSoundId.string(id), name, mode, volume, pitch);
    }

    public void playSound(float id, String name, int mode, float volume, float pitch) {
        playSound(ModelSoundId.numeric(id), name, mode, volume, pitch);
    }

    public void playSound(ModelSoundId id, String name, int mode, float volume, float pitch) {
        requireAction();
        this.stage(new EffectCommand.Sound(HostSymbol.YSM_PLAY_SOUND, java.util.Objects.requireNonNull(id), name, mode,
            volume, pitch, false));
    }

    public void stopSound(String id, boolean global) {
        stopSound(ModelSoundId.string(id), global);
    }

    public void stopSound(float id, boolean global) {
        stopSound(ModelSoundId.numeric(id), global);
    }

    public void stopSound(ModelSoundId id, boolean global) {
        requireAction();
        this.stage(new EffectCommand.Sound(HostSymbol.YSM_STOP_SOUND, java.util.Objects.requireNonNull(id), "", 0, 1, 1,
            global));
    }

    public void stopAllSounds(boolean global) {
        requireAction();
        this.stage(new EffectCommand.Sound(HostSymbol.YSM_STOP_ALL_SOUNDS, null, "", 0, 1, 1, global));
    }

    public void setAnimation(String name, float loop) {
        controller(HostSymbol.CTRL_SET_ANIMATION, name, loop, 0, 0);
    }

    public void resetController() {
        controller(HostSymbol.CTRL_RESET, "", 0, 0, 0);
    }

    public void indicateReload() {
        controller(HostSymbol.CTRL_INDICATE_RELOAD, "", 0, 0, 0);
    }

    public void setBeginningTransitionLength(float length) {
        controller(HostSymbol.CTRL_SET_BEGINNING_TRANSITION_LENGTH, "", length, 0, 0);
    }

    public void boneColor(String name, float red, float green, float blue) {
        controller(HostSymbol.YSM_BONE_COLOR, name, red, green, blue);
    }

    public void boneGlow(String name, float glow) {
        controller(HostSymbol.YSM_BONE_GLOW, name, glow, 0, 0);
    }

    public void boneTransparency(String name, float alpha) {
        controller(HostSymbol.YSM_BONE_TRANSPARENCY, name, alpha, 0, 0);
    }

    public void sync(float... values) {
        requireAction();
        this.stage(new EffectCommand.Sync(EventArguments.sync(values)));
    }

    public void particle(String name, float... values) {
        namedFloats(HostSymbol.YSM_PARTICLE, name, values);
    }

    public void absoluteParticle(String name, float... values) {
        namedFloats(HostSymbol.YSM_ABS_PARTICLE, name, values);
    }

    public void defer(float delay, float... values) {
        requireAction();
        this.stage(new EffectCommand.Deferred(EventArguments.deferred(delay, values)));
    }

    private void namedFloats(HostSymbol symbol, String name, float[] values) {
        requireAction();
        this.stage(new EffectCommand.Particle(symbol == HostSymbol.YSM_ABS_PARTICLE, name, values));
    }

    private void controller(HostSymbol symbol, String name, float first, float second, float third) {
        requireAction();
        this.stage(new EffectCommand.Controller(symbol, name, first, second, third));
    }

    /** The legacy absolute-pivot capability currently has no host implementation. */
    public MolangVector bonePivotAbsolute(String name) {
        requireRead();
        java.util.Objects.requireNonNull(name);
        return new MolangVector(0, 0, 0);
    }

    public boolean keyboard(float... keys) {
        requireRead();
        return ContextReads.keyboard(keys);
    }

    public String modVersion(String id) {
        requireRead();
        return ContextReads.modVersion(id);
    }

    public float partialTick() {
        requireRead();
        return this.productionInputs().partialTick();
    }

    public float renderTickDelta() {
        requireRead();
        return this.productionInputs().renderTickDelta();
    }

    public float renderTicks() {
        requireRead();
        return ContextReads.inputs(this).animationEvent().getRenderTicks();
    }

    public float headPitch() {
        requireRead();
        return ContextReads.inputs(this).animationEvent().getExtraData().headPitch;
    }

    public float headYaw() {
        requireRead();
        return ContextReads.inputs(this).animationEvent().getExtraData().netHeadYaw;
    }

    public float bodyYaw() {
        requireRead();
        return ContextReads.inputs(this).animationEvent().getExtraData().lerpBodyRot;
    }

    public float lifeTime() {
        requireRead();
        return ContextReads.inputs(this).animatable().getSeekTime() / 20.0f;
    }

    public float foodLevel() {
        requireRead();
        return ContextReads.foodLevel(this);
    }

    public float forwardInput() {
        requireRead();
        return ContextReads.forwardInput(this);
    }

    public float random() {
        requireRead();
        return ContextReads.inputs(this).randomSource().nextFloat();
    }

    public String textureName() {
        requireRead();
        return ContextReads.textureName(this);
    }

    public boolean playingExtraAnimation() {
        requireRead();
        return ContextReads.playingExtraAnimation(this);
    }

    public boolean shieldCooldown() {
        requireRead();
        return ContextReads.shieldCooldown(this);
    }

    public String selectedControl() {
        requireRead();
        return ContextReads.selectedControl(this);
    }

    public float health(boolean maximum) {
        requireRead();
        return ContextReads.health(this, maximum);
    }

    public boolean renderingInInventory() {
        requireRead();
        return com.elfmcys.ysm.util.PersonView.isInInventory(ContextReads.inputs(this).animationEvent());
    }

    public float animationTime() {
        requireRead();
        var v = animationState();
        return v == null ? 0 : v.animTime();
    }

    public boolean allAnimationsFinished() {
        requireRead();
        var v = controllerState();
        return v != null && v.isAllAnimationsFinished();
    }

    public boolean anyAnimationFinished() {
        requireRead();
        var v = controllerState();
        return v != null && v.isAnyAnimationFinished();
    }

    public @Nullable AnimationExecutionState animationState() {
        return animationSelected ? animationContext : ContextReads.inputs(this).animationState();
    }

    public @Nullable ControllerExecutionState controllerState() {
        return controllerSelected ? controllerContext : ContextReads.inputs(this).controllerState();
    }

    public int randomInt(int bound) {
        requireRead();
        return ContextReads.inputs(this).randomSource().nextInt(bound);
    }

    public MolangVector boneRotation(String name) {
        requireRead();
        return ContextReads.boneRotation(this, name);
    }

    public float firstOrder(String key, float value, float... coefficients) {
        requireRead();
        return ContextReads.physics(this, key, value, true, coefficients);
    }

    public float secondOrder(String key, float value, float... coefficients) {
        requireRead();
        return ContextReads.physics(this, key, value, false, coefficients);
    }
}
