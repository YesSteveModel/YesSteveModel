package com.elfmcys.ysm.client.entity;

import java.io.UncheckedIOException;
import java.io.IOException;

import com.elfmcys.ysm.geckolib3.geo.animated.GeoModelState;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.expression.host.builtin.PositionComponent;
import com.elfmcys.ysm.util.ResourceDiagnostics;

import com.elfmcys.ysm.client.animation.physics.PhysicsManager;
import com.elfmcys.ysm.client.animation.physics.PlayerTeleportState;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.event.ClientTickEvent;
import com.elfmcys.ysm.client.physics.debug.PhysicsDebugController;
import com.elfmcys.ysm.client.sound.instance.AudioPlaybackFactory;
import com.elfmcys.ysm.geckolib3.core.AnimationState;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.TransientExpressionSession;
import com.elfmcys.ysm.expression.host.EventArguments;
import com.elfmcys.ysm.expression.host.ExecutionFailure;
import com.elfmcys.ysm.expression.host.ComponentScope;
import com.elfmcys.ysm.expression.host.ComponentLifecycleException;
import dev.semaweave.RoamingRole;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.client.animation.debug.DebugSource;
import com.elfmcys.ysm.geckolib3.core.processor.AnimationProcessor;
import com.elfmcys.ysm.geckolib3.core.processor.BoneView;
import com.elfmcys.ysm.geckolib3.core.util.RateLimiter;
import com.elfmcys.ysm.util.CleanupFailures;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.provider.data.EntityModelData;
import com.elfmcys.ysm.natives.NativeProfiler;
import com.elfmcys.ysm.natives.physics.NativePhysicsAsset;
import com.elfmcys.ysm.natives.physics.NativePhysicsWorld;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.model.resource.client.PhysicsAssetMetadata;
import com.elfmcys.ysm.util.RenderUtil;
import com.google.common.collect.Maps;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import com.elfmcys.ysm.client.animation.AnimationParallelTicker;
import com.elfmcys.ysm.client.animation.debug.CustomDebugSource;
import com.elfmcys.ysm.client.gui.overlay.DebugAnimationScreen;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.expression.aot.UserFunctionProgram;
import com.elfmcys.ysm.geckolib3.core.processor.DebugInfo;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.util.Closeable;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Objects;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.geckolib3.model.EntityStateTracker;
import java.util.function.Supplier;
import java.util.concurrent.CancellationException;

/**
 * Owns one subject's mutable animation state and its current resource installation.
 *
 * <p>
 * Invariant: each owner has one execution authority. An outstanding task keeps that authority until the corresponding
 * wait or GUI join completes, including task cleanup.
 *
 * <p>
 * Execution: public lifecycle operations run on the render thread. Worker execution uses the owner's reserved window. A
 * GUI permit grants only its exact task's internal installation and evaluation operations. Callbacks must not reenter
 * public lifecycle operations. Owner confinement does not make the live entity, level, or optional host APIs immutable
 * or safe for concurrent access.
 *
 * <p>
 * Ownership: resource getters and render data borrow the current installation; they do not transfer its lease.
 *
 * <p>
 * Seam: establishes [execution authority and task handoff](docs/architecture/animation/entity-lifecycle.md#执行权与任务交接)
 * and depends on [complete installation and replacement](docs/architecture/animation/entity-lifecycle.md#模型使用与完整安装).
 */
public abstract class AnimatableEntity<TEntity extends Entity>
    implements com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Installation {
    private static final float SWING_MOTION_THRESHOLD = 0.15f;
    private final RateLimiter rateLimiter;
    private final EntityStateTracker<TEntity> stateTracker;
    private final Map<RenderContext, GeoRenderData> renderDataPool = new HashMap<>();
    protected final PhysicsManager physicsManager;

    protected final TEntity entity;
    private final EntityExpressionRuntime expressionRuntime = new EntityExpressionRuntime();
    private final EntityModelBinding modelBinding;
    private final EntityExecutionLifecycle lifecycle;
    // Main-thread registration survives reset and retirement until the invocation's complete drain.
    private boolean invocationRetained;
    private com.elfmcys.ysm.client.entity.EntityModelDemand demandScope;
    private PhysicsManager alterPhysicsManager;
    private PlayerPhysicsSession.RecoveryGate physicsRecovery;

    public enum InstallationRelation {
        UNBOUND, MATCHING, RETAINED, DEFAULT_SUBSTITUTE
    }

    public enum BlockReason {
        NONE, UNASSIGNED, RESTRICTED, WAITING, FAILED, MISSING_PART, STALE_RESULT
    }

    private GeoRenderData mainRenderData;

    // 这两个变量不跟随动画一起更新，所以不能放进 stateTracker
    protected float lastFrameTime = -1;
    protected boolean lastMutableRender;
    protected boolean currentFrameTicked;
    protected boolean currentFrameShouldTick;

    private boolean currentFrameExtracted = false;

    protected boolean lastFrameRendered = true;
    protected boolean currentFrameRendered = false;
    protected boolean lastFrameUpdated;
    protected float seekTime;
    private boolean sampledMainTimeline;
    private int sampledEntityTick;
    private float sampledAnimationTime;

    /**
     * 存储 Coded 动画控制器的动画播放状态，用于一些 molang 判断
     */
    protected Map<String, AnimationState> codedAnimationStates = Maps.newHashMap();
    private final com.elfmcys.ysm.expression.host.SubjectSample.Draft subjectSampleDraft =
        new com.elfmcys.ysm.expression.host.SubjectSample.Draft();
    private final boolean worldSubjectSamplePublisher;

    /** Exact producer write target; borrowed only in this owner's installation and component windows. */
    public final com.elfmcys.ysm.expression.host.SubjectSample.Draft subjectSampleDraft() {
        return subjectSampleDraft;
    }

    /** Called once during construction; role selection must depend only on the supplied subject. */
    protected boolean worldSubjectSamplePublisher(TEntity subject) {
        return false;
    }

    protected AnimatableEntity(TEntity entity) {
        this(entity, false);
    }

    protected AnimatableEntity(TEntity entity, boolean asyncUpdate) {
        this(entity, asyncUpdate, new EntityModelBinding());
    }

    AnimatableEntity(TEntity entity, boolean asyncUpdate, EntityModelBinding modelBinding) {
        this.entity = Objects.requireNonNull(entity, "entity");
        worldSubjectSamplePublisher = worldSubjectSamplePublisher(entity);
        this.modelBinding = Objects.requireNonNull(modelBinding, "modelBinding");
        this.lifecycle = new EntityExecutionLifecycle(expressionRuntime, this::clearEndedUse, this::handleAsyncFailure);
        expressionRuntime.projectionAvailability(lifecycle::canAdoptProjection);
        this.rateLimiter = new RateLimiter();
        this.stateTracker = createStateTracker(entity);
        this.physicsManager = new PhysicsManager();
        this.rateLimiter.setLimit(getFrameRateLimit());
        if (asyncUpdate)
            AnimationParallelTicker.add(this);
    }

    public final void reset() {
        lifecycle.endUse();
        if (isAssigned())
            registerDemandIfAvailable();
    }

    private void clearOutputs() {
        currentFrameExtracted = false;
        var main = mainRenderData;
        mainRenderData = null;
        var failure = closeOutput(main, null);
        for (var data : renderDataPool.values())
            failure = closeOutput(data, failure);
        renderDataPool.clear();
        throwCleanupFailure(failure);
    }

    private static Throwable closeOutput(@Nullable GeoRenderData data, @Nullable Throwable previous) {
        return attemptCleanup(previous, () -> {
            if (data != null)
                data.modelState.close();
        });
    }

    private static Throwable attemptCleanup(@Nullable Throwable primary, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error failure) {
            return CleanupFailures.merge(primary, failure);
        }
        return primary;
    }

    private static void throwCleanupFailure(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime)
            throw runtime;
        if (failure instanceof Error error)
            throw error;
    }

    private void resetAnimationClock(boolean endRun) {
        physicsManager.reset();
        alterPhysicsManager = null;
        rateLimiter.reset();
        if (endRun) {
            stateTracker.resetAnimationHistory();
            lastFrameTime = -1;
            seekTime = 0;
            sampledMainTimeline = false;
            sampledEntityTick = 0;
            sampledAnimationTime = 0;
        }
        lastMutableRender = currentFrameTicked = currentFrameShouldTick = false;
        lastFrameRendered = true;
        currentFrameRendered = currentFrameExtracted = lastFrameUpdated = false;
        codedAnimationStates.clear();
    }

    /**
     * Permanently revokes this owner's assignment and ends its current use.
     *
     * <p>
     * Execution: joins an outstanding ordinary task before cleanup. An active draw borrow delays resource cleanup until
     * the borrow ends, but does not delay revocation.
     */
    public final void retire() {
        lifecycle.rejectCandidateMutation();
        try {
            lifecycle.retire();
        } finally {
            modelBinding.revokeAssignment();
            physicsRecovery = null;
            PhysicsDebugController.retire(this);
            onEntityRetired();
        }
    }

    protected void onEntityRetired() {}

    private void clearEndedUse() {
        subjectSampleDraft.clear();
        if (worldSubjectSamplePublisher)
            ((com.elfmcys.ysm.expression.host.SubjectSampleAccess)entity)
                .ysm$subjectSample(com.elfmcys.ysm.expression.host.SubjectSample.MISSING);
        var failure = attemptCleanup(null, this::closeDemandScope);
        failure = attemptCleanup(failure, this::clearOutputs);
        failure = attemptCleanup(failure, this::clearInstalledRuntime);
        failure = attemptCleanup(failure, modelBinding::releaseRenderTarget);
        throwCleanupFailure(failure);
    }

    private void clearInstalledRuntime() {
        var retired = animationSession;
        animationSession = null;
        localProjection = null;
        var failure = attemptCleanup(null, () -> {
            if (retired != null)
                retired.close();
        });
        failure = attemptCleanup(failure, expressionRuntime::clear);
        failure = attemptCleanup(failure, () -> resetAnimationClock(true));
        failure = attemptCleanup(failure, () -> {
            try {
                onModelEnded();
            } catch (RuntimeException | LinkageError roleFailure) {
                reportCleanupFailure(roleFailure);
            }
        });
        throwCleanupFailure(failure);
    }

    /** Releases state owned outside the generated component holder. */
    protected void onModelEnded() {}

    protected EntityStateTracker<TEntity> createStateTracker(TEntity entity) {
        return new EntityStateTracker<>(entity);
    }

    public EntityStateTracker<TEntity> getStateTracker() {
        lifecycle.assertStateAccess();
        return stateTracker;
    }

    public float getSeekTime() {
        lifecycle.assertStateAccess();
        return seekTime;
    }

    public final AnimationData getAnimationData() {
        lifecycle.assertStateAccess();
        return requireSession().animations;
    }

    public abstract ResourceLocation getTextureLocation();

    public abstract float getWidthScale();

    public abstract float getHeightScale();

    @Nullable
    public abstract Animation getAnimation(String name);

    private AnimationSession animationSession;

    private AnimationStore.View readAnimationSource;
    private AnimationStore.ReadBatch animationReads;
    private boolean animationReadScope;

    protected AnimationStore.View animationSource() {
        return null;
    }

    protected final Animation readAnimation(AnimationStore.View source, String name) {
        return animationReadView(source).get(name);
    }

    public final AnimationStore.View animationResolutionSource() {
        return animationSource();
    }

    public final AnimationStore.Resolution resolveAnimation(String name) {
        var source = animationSource();
        return source == null ? new AnimationStore.Resolution(getAnimation(name), false, false)
            : animationReadView(source).resolve(name);
    }

    private AnimationStore.View animationReadView(AnimationStore.View source) {
        if (!animationReadScope || readAnimationSource != source)
            return source;
        if (animationReads == null) {
            try {
                animationReads = expressionRuntime.openAnimationReadPhase(source);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        return animationReads.animations();
    }

    public Optional<AudioPlaybackFactory> getSoundPlayback(String name) {
        var target = getModelRenderTarget();
        if (target == null)
            return Optional.empty();
        var source = assets(target).sounds().get(name);
        return source == null ? Optional.empty()
            : Optional.of(ClientModelService.instance().createSoundPlayback(source));
    }

    public PhysicsManager getPhysicsManager(AnimationEvent<?> event) {
        lifecycle.assertStateAccess();
        var context = event.getRenderContext();
        if (context.immutable() || context.firstPersonMod() || context.paperDoll())
            return physicsManager;
        if (alterPhysicsManager == null)
            alterPhysicsManager = new PhysicsManager();
        return alterPhysicsManager;
    }

    @Nullable
    public AnimationControllerData getAnimationControllerData(String animationControllerName) {
        return null;
    }

    /**
     * 更新动画之后调用， 如果由于频率限制、renderTick 倒退等原因导致动画不更新，则不会调用
     */
    protected void postAnimationSetup(float seekTime, boolean shouldTick) {}

    /** Closed persistent events are dispatched explicitly inside the logical owner. */
    protected void dispatchPersistentEvents(EntityExpressionRuntime.Evaluation evaluation, boolean shouldUpdate) {}

    /**
     * Returns the borrowed live subject, without granting access to owner-private animation state.
     *
     * <p>
     * Execution: a stable world subject remains readable on the main thread during ordinary work. A reserved GUI task
     * excludes public subject access until join.
     *
     * @return the subject; the caller does not own its lifetime or obtain a host-input snapshot
     */
    public final TEntity getEntity() {
        lifecycle.assertSubjectAccess();
        return entity;
    }

    public boolean isFakePlayer() {
        return false;
    }

    @Nullable
    public BoneView getBone(int boneName) {
        return hasInstalledModel() ? getAnimationProcessor().getBone(boneName) : null;
    }

    protected boolean allowEmitting() {
        return true;
    }

    public int getFrameRateLimit() {
        var localPlayer = Minecraft.getInstance().player;
        if (localPlayer != null && localPlayer != entity) {
            var localPos = localPlayer.position();
            if (localPos.x != 0 || localPos.y != 0 || localPos.z != 0) {
                // 未渲染：屏幕外、太远、被 EntityCulling 剔除
                if (!lastFrameRendered) {
                    return 10;
                }

                var distance = localPlayer.distanceTo(entity);
                // 超远距离：原版超过这个距离会跳过渲染
                if (distance > 64) {
                    return 30;
                }
                // 一般远距离
                if (distance > 40) {
                    return 60;
                }
            }
        }
        return ClientTickEvent.getRefreshRate();
    }

    /**
     * Prepares the ordinary level pose after joining prior owner work.
     *
     * <p>
     * Ownership: the result borrows the current installation and mutable pose output. A later owner update or ended use
     * can invalidate that output. The caller must use consumePrepared() to qualify consumption; a non-null return does
     * not reserve later draw access.
     *
     * @return the prepared pose, or null when no consumable pose is available for this update
     */
    public @Nullable GeoRenderData update(float partialTicks) {
        if (!isAssigned()) {
            return null;
        }
        return update(partialTicks, resolveRenderContext(RenderUtil.extractRenderContext()));
    }

    @Nullable
    protected GeoRenderData update(float partialTicks, RenderContext context) {
        RenderSystem.assertOnRenderThread();
        lifecycle.rejectRecursiveCall();
        waitForRenderUpdate();
        checkModelUpdate();
        prepareEvaluation();
        var resolved = resolveRenderContext(context);
        try {
            return inCall(() -> computePose(partialTicks, resolved));
        } catch (OutOfMemoryError failure) {
            currentFrameExtracted = false;
            return null;
        } catch (FailedExtract failure) {
            handleFailedExtract(failure);
            return null;
        } catch (FailedUse failure) {
            handleFailedUse(failure);
            return null;
        } catch (ExecutionFailure failure) {
            handleExpressionFailure(failure);
            return null;
        } finally {
            finishProjections(!AnimationParallelTicker.isInvocationActive());
        }
    }

    private GeoRenderData computePose(float partialTicks, RenderContext context) {
        if (!canEvaluate())
            return null;
        var installation = requireInstallation();
        try (var ignored = NativeProfiler.beginAnimatableUpdate()) {
            var event = createAnimationEvent(partialTicks, context);
            var session = requireSession();
            if (session.physics != null)
                return computePhysicsPose(installation, session, event);
            tickAnimation(event);
            var data = getRenderData(context, context.immutable());
            if (!context.immutable() || !currentFrameExtracted || !data.modelState.isValid()
                || data.modelState.getModel() != requireSession().geoModel.getModel() || !context.equals(data.ctx)
                || partialTicks != data.partialTicks) {
                currentFrameExtracted = false;
                data.installation = null;
                try {
                    data.modelState.extract(requireSession().geoModel);
                } catch (RuntimeException failure) {
                    throw new FailedExtract(installation, event, failure);
                }
                if (!data.modelState.isValid())
                    return null;
                currentFrameExtracted = context.immutable();
            }
            updateRenderData(event, data);
            data.owner = this;
            data.installation = installation;
            return data;
        }
    }

    private @Nullable GeoRenderData computePhysicsPose(ResourceHolder installation, AnimationSession session,
        AnimationEvent<AnimatableEntity<TEntity>> event) {
        var physics = session.physics;
        var context = event.getRenderContext();
        var pass = PhysicsRenderPass.classify(context);
        var canonical = pass == PhysicsRenderPass.CANONICAL;
        var frame = Math.max(event.renderTicks, lastFrameTime);
        var time = observedSeekTime(frame) / 20.0;
        var partialTicks = event.getRequestedPartialTick();
        var level = entity.level();
        var teleport = PlayerTeleportState.capture(entity);
        EntityRootTransform root = null;
        if (pass != PhysicsRenderPass.PURE_ANIMATION_ONLY && !physics.isDegraded()) {
            root = EntityRootTransform.capture((LivingEntity)entity, partialTicks, event.getExtraData().lerpBodyRot);
            physics.invalidate(level, teleport, event.getEntityTickCount(), root.entityWorldPos(), time, frame);
        }
        if (canonical) {
            tickAnimation(event);
            session.canonicalBonePoseFrameTime = frame;
            session.canonicalBonePosePartialTick = partialTicks;
        } else {
            event.renderTicks = observedSeekTime(frame);
        }

        var mode = root == null ? PlayerPhysicsSession.ANIMATION_ONLY
            : physics.select(time, frame, canonical && currentFrameShouldTick);
        long epoch = pass == PhysicsRenderPass.PURE_ANIMATION_ONLY ? PlayerPhysicsSession.NO_SAMPLE_EPOCH
            : physics.sampleEpoch();
        var data = getRenderData(context, canonical);
        boolean advancing = mode == NativeModelState.RESET || mode == NativeModelState.STEP;
        if (advancing || (canonical && !currentFrameExtracted) || (!canonical && !context.immutable())
            || !data.modelState.isValid() || data.modelState.getModel() != session.geoModel.getModel()
            || !context.equals(data.ctx) || partialTicks != data.partialTicks || frame != data.physicsFrameTime
            || epoch != data.physicsSampleEpoch) {
            data.installation = null;
            if (canonical)
                currentFrameExtracted = false;
            float[] restored = null;
            try {
                if (!canonical && (!context.immutable() || session.canonicalBonePoseFrameTime != frame
                    || session.canonicalBonePosePartialTick != partialTicks)) {
                    restored = session.saveBoneAttributes();
                    evaluateObservedPose(session, event);
                }
                var state = data.modelState;
                if (mode == PlayerPhysicsSession.ANIMATION_ONLY) {
                    state.extract(session.geoModel);
                } else {
                    var dt = mode == NativeModelState.STEP ? (float)physics.secondsSinceSample(time) : 0;
                    var flags = state.extractWithPhysics(session.geoModel, physics.world(), mode, dt,
                        root.renderRootPos(), root.renderRootQuat());
                    physics.accept(mode, flags, state.getNativeState().getRawJoltError(), level, teleport,
                        event.getEntityTickCount(), root.entityWorldPos(), time, frame);
                    if (physics.isDegraded() && (flags & NativeModelState.EXTRACT_OK) == 0)
                        state.extract(session.geoModel);
                }
            } catch (RuntimeException failure) {
                throw new FailedExtract(installation, event, failure);
            } finally {
                if (restored != null)
                    System.arraycopy(restored, 0, session.geoModel.getBoneAttributes(), 0, restored.length);
            }
            if (!data.modelState.isValid())
                return null;
            if (canonical)
                currentFrameExtracted = true;
            data.physicsFrameTime = frame;
            data.physicsSampleEpoch = root != null ? physics.sampleEpoch() : epoch;
            data.rootTransform = root;
        }
        updateRenderData(event, data);
        data.owner = this;
        data.installation = installation;
        return data;
    }

    private float observedSeekTime(float frame) {
        return lastFrameTime < 0 ? seekTime : seekTime + Math.max(0, frame - lastFrameTime);
    }

    private void evaluateObservedPose(AnimationSession session, AnimationEvent<AnimatableEntity<TEntity>> event) {
        session.observingPose = true;
        try {
            recoverLastCodedAnimation();
            getAnimationProcessor().observePose(event, () -> codeAnimation(event, true));
        } finally {
            session.observingPose = false;
        }
    }

    protected final boolean isObservingPose() {
        return requireSession().observingPose;
    }

    /** Allows a synchronous host to consume a local result without extracting the whole model. */
    protected boolean updateAnimationOnly(float partialTicks, RenderContext context) {
        RenderSystem.assertOnRenderThread();
        lifecycle.rejectRecursiveCall();
        waitForRenderUpdate();
        checkModelUpdate();
        prepareEvaluation();
        try {
            return inCall(() -> {
                if (!canEvaluate())
                    return false;
                var resolved = resolveRenderContext(context);
                var event = createAnimationEvent(partialTicks, resolved);
                var session = requireSession();
                if (session.physics == null || PhysicsRenderPass.classify(resolved) == PhysicsRenderPass.CANONICAL) {
                    tickAnimation(event);
                } else {
                    event.renderTicks = observedSeekTime(event.renderTicks);
                    var restored = session.saveBoneAttributes();
                    try {
                        evaluateObservedPose(session, event);
                    } finally {
                        System.arraycopy(restored, 0, session.geoModel.getBoneAttributes(), 0, restored.length);
                    }
                }
                return true;
            });
        } catch (OutOfMemoryError failure) {
            currentFrameExtracted = false;
            return false;
        } catch (FailedUse failure) {
            handleFailedUse(failure);
            return false;
        } catch (ExecutionFailure failure) {
            handleExpressionFailure(failure);
            return false;
        } finally {
            finishProjections(!AnimationParallelTicker.isInvocationActive());
        }
    }

    protected void updateRenderData(AnimationEvent<?> event, GeoRenderData data) {
        data.ctx = event.getRenderContext();
        data.texture = getTextureLocation();
        data.widthScale = getWidthScale();
        data.heightScale = getHeightScale();
        data.partialTicks = event.getRequestedPartialTick();
        data.animationData = event.getExtraData();
    }

    private GeoRenderData getRenderData(RenderContext context, boolean mainSlot) {
        GeoRenderData renderData;
        if (mainSlot) {
            if (mainRenderData == null) {
                renderData = new GeoRenderData();
                mainRenderData = renderData;
            } else {
                renderData = mainRenderData;
            }
        } else {
            renderData = renderDataPool.computeIfAbsent(context, c -> new GeoRenderData());
        }
        return renderData;
    }

    /** Borrows the matching previous output during this owner's serialized animation evaluation. */
    public final @Nullable GeoModelState getModelState() {
        lifecycle.assertStateAccess();
        var data = mainRenderData;
        var installation = modelBinding.resourceHolder();
        if (data == null || installation == null || data.installation != installation || !data.modelState.isValid()
            || data.modelState.getModel() != requireSession().geoModel.getModel()
            || !matchesPhysicsEpoch(data, requireSession().physics))
            return null;
        return data.modelState;
    }

    private AnimationEvent<AnimatableEntity<TEntity>> createAnimationEvent(float partialTicks, RenderContext context) {
        final Entity entity = this.entity;
        final LivingEntity livingEntity = entity instanceof LivingEntity ? (LivingEntity)entity : null;
        int entityTickCount = this instanceof IPreviewEntity ? ClientTickEvent.getTickCount() : entity.tickCount;
        float realPartialTicks = partialTicks != 1f ? partialTicks : Minecraft.getInstance().getFrameTime();

        boolean shouldSit =
            entity.isPassenger() && (entity.getVehicle() != null && entity.getVehicle().shouldRiderSit());
        float limbSwingAmount = 0;
        float limbSwing = 0;

        if (!shouldSit && entity.isAlive() && livingEntity != null) {
            limbSwingAmount = livingEntity.walkAnimation.speed(partialTicks);
            limbSwing = livingEntity.walkAnimation.position(partialTicks);
            if (livingEntity.isBaby()) {
                limbSwing *= 3.0F;
            }
        }

        EntityModelData entityModelData = new EntityModelData();
        entityModelData.isSitting = shouldSit;

        float lerpBodyRot = 0;
        float lerpHeadRot = 0;
        float netHeadYaw = 0;

        if (livingEntity != null) {
            entityModelData.isChild = livingEntity.isBaby();
            lerpBodyRot = Mth.rotLerp(partialTicks, livingEntity.yBodyRotO, livingEntity.yBodyRot);
            lerpHeadRot = Mth.rotLerp(partialTicks, livingEntity.yHeadRotO, livingEntity.yHeadRot);
            netHeadYaw = lerpHeadRot - lerpBodyRot;
        }

        if (shouldSit && entity.getVehicle() instanceof LivingEntity) {
            LivingEntity vehicle = (LivingEntity)entity.getVehicle();
            lerpBodyRot = Mth.rotLerp(partialTicks, vehicle.yBodyRotO, vehicle.yBodyRot);
            netHeadYaw = lerpHeadRot - lerpBodyRot;
            float clampedHeadYaw = Mth.clamp(Mth.wrapDegrees(netHeadYaw), -85, 85);
            lerpBodyRot = lerpHeadRot - clampedHeadYaw;
            if (clampedHeadYaw * clampedHeadYaw > 2500f) {
                lerpBodyRot += clampedHeadYaw * 0.2f;
            }
            netHeadYaw = lerpHeadRot - lerpBodyRot;
        }

        entityModelData.rawHeadPitch = Mth.lerp(partialTicks, entity.xRotO, entity.getXRot());
        entityModelData.headPitch = -entityModelData.rawHeadPitch;
        entityModelData.rawNetHeadYaw = netHeadYaw;
        entityModelData.netHeadYaw = -Mth.clamp(Mth.wrapDegrees(netHeadYaw), -85, 85);
        entityModelData.lerpBodyRot = lerpBodyRot;
        entityModelData.lerpedAge = entityTickCount + partialTicks;
        entityModelData.limbSwing = limbSwing;
        entityModelData.limbSwingAmount = limbSwingAmount;
        entityModelData.isMoving =
            (limbSwingAmount <= -SWING_MOTION_THRESHOLD || limbSwingAmount <= SWING_MOTION_THRESHOLD);

        return new AnimationEvent<>(this, entityTickCount, partialTicks, realPartialTicks, context, entityModelData,
            getDebugSource());
    }

    /** Non-render ingress must not inherit an earlier render frame's context. */
    public final AnimationEvent<AnimatableEntity<TEntity>> createIngressAnimationEvent() {
        return createAnimationEvent(Minecraft.getInstance().getFrameTime(), RenderContext.levelImmutable());
    }

    protected void tickAnimation(@NotNull AnimationEvent<AnimatableEntity<TEntity>> animationEvent) {
        var installation = requireInstallation();
        var source = animationSource();
        if (source == null || animationReadScope && readAnimationSource == source) {
            publishSubjectSample(installation, tickAnimationNow(animationEvent));
            return;
        }
        var previousSource = readAnimationSource;
        var previousReads = animationReads;
        var previousScope = animationReadScope;
        readAnimationSource = source;
        animationReads = null;
        animationReadScope = true;
        Throwable primaryFailure = null;
        com.elfmcys.ysm.expression.host.SubjectSample completed;
        try {
            completed = tickAnimationNow(animationEvent);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            var reads = animationReads;
            animationReads = previousReads;
            readAnimationSource = previousSource;
            animationReadScope = previousScope;
            if (reads != null) {
                try {
                    reads.close();
                } catch (IOException failure) {
                    if (primaryFailure != null)
                        primaryFailure.addSuppressed(failure);
                    else
                        throw new UncheckedIOException(failure);
                }
            }
        }
        publishSubjectSample(installation, completed);
    }

    private void publishSubjectSample(ResourceHolder installation,
        @Nullable com.elfmcys.ysm.expression.host.SubjectSample completed) {
        if (worldSubjectSamplePublisher && completed != null && isCurrent(installation))
            ((com.elfmcys.ysm.expression.host.SubjectSampleAccess)entity).ysm$subjectSample(completed);
    }

    private @Nullable com.elfmcys.ysm.expression.host.SubjectSample
        tickAnimationNow(AnimationEvent<AnimatableEntity<TEntity>> animationEvent) {
        var installation = requireInstallation();
        com.elfmcys.ysm.expression.host.SubjectSample completed = null;
        var frameTime = animationEvent.renderTicks;
        var ctx = animationEvent.getRenderContext();
        var mutableRender = !ctx.immutable();

        if (frameTime > lastFrameTime) {
            if (lastFrameTime >= 0)
                seekTime += frameTime - lastFrameTime;
            currentFrameTicked = false;
            currentFrameShouldTick = false;
            lastFrameTime = frameTime;
            rateLimiter.setLimit(getFrameRateLimit());
            lastFrameRendered = currentFrameRendered;
            currentFrameExtracted = false;
            currentFrameRendered = false;
        }

        animationEvent.renderTicks = this.seekTime;

        if (!getAnimationProcessor().isModelEmpty()) {
            currentFrameShouldTick |= rateLimiter.request(seekTime / 20);
            var shouldUpdate = (currentFrameShouldTick && !currentFrameTicked) || lastMutableRender || mutableRender;
            var shouldTick = (!mutableRender || (seekTime == 0 && !currentFrameTicked)) && currentFrameShouldTick
                && !currentFrameTicked;
            recoverLastCodedAnimation();
            if (shouldUpdate) {
                currentFrameExtracted = false;
                try {
                    var inputs = getAnimationProcessor().invocationInputs(animationEvent);
                    completed = expressionRuntime.animationPassProjected(inputs, shouldTick, evaluation -> {
                        requireSession().components.evaluate(animationEvent, () -> true, () -> {
                            if (shouldTick) {
                                currentFrameTicked = true;
                                advanceComponentTime(installation, animationEvent);
                            }
                            evaluation.captureSubjectSample(subjectSampleDraft.freeze());
                            getPhysicsManager(animationEvent).update(this.seekTime);
                            getAnimationProcessor().processTransientTasks(evaluation, true);
                            if (shouldTick)
                                evaluation.dispatchPendingDeferred();
                            if (shouldTick)
                                dispatchPersistentEvents(evaluation, true);
                            getAnimationProcessor().tickAnimation(animationEvent, shouldTick,
                                shouldTick && allowEmitting(), evaluation);
                            postAnimationSetup(this.seekTime, shouldTick);
                            getAnimationProcessor().processTransientTasks(evaluation, false);
                            codeAnimation(animationEvent, true);
                            evaluation.dispatchPendingDeferred();
                        });
                        return shouldTick && evaluation.failure().isEmpty() ? evaluation.subjectSample() : null;
                    }, localProjection);
                } catch (RuntimeException failure) {
                    if (!containsComponentFailure(failure)) {
                        if (failure instanceof CancellationException)
                            return null;
                        throw failure;
                    }
                    throw new FailedUse(this, installation, animationEvent, failure);
                }
                lastMutableRender = mutableRender;
            } else {
                codeAnimation(animationEvent, false);
            }
            lastFrameUpdated = shouldUpdate;
        }
        return completed;
    }

    private void advanceComponentTime(ResourceHolder installation, AnimationEvent<?> event) {
        var firstOwnerSample = !sampledMainTimeline;
        var firstComponentSample = !requireSession().componentsSampled;
        int currentTick =
            firstOwnerSample ? event.getEntityTickCount() : Math.max(sampledEntityTick, event.getEntityTickCount());
        int previousTick = firstOwnerSample ? currentTick : sampledEntityTick;
        float currentTime = seekTime;
        float previousTime = firstOwnerSample ? currentTime : sampledAnimationTime;
        sampledMainTimeline = true;
        sampledEntityTick = currentTick;
        sampledAnimationTime = currentTime;
        requireSession().componentsSampled = true;
        if (firstOwnerSample || currentTick > previousTick)
            stateTracker.tick(previousTick, currentTick);
        if (firstComponentSample || currentTick > previousTick)
            requireSession().components.tick(firstComponentSample ? currentTick : previousTick, currentTick,
                () -> true);
        if (firstOwnerSample || currentTime > previousTime)
            stateTracker.update(previousTime, currentTime, event.getPartialTick());
        if (firstComponentSample || currentTime > previousTime)
            requireSession().components.update(firstComponentSample ? currentTime : previousTime, currentTime,
                event.getPartialTick(), () -> true);
    }

    protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<TEntity>> animationEvent,
        boolean shouldUpdate) {}

    protected void recoverLastCodedAnimation() {}

    @SuppressWarnings("unchecked")
    public final AnimationProcessor<TEntity> getAnimationProcessor() {
        return (AnimationProcessor<TEntity>)requireSession().processor;
    }

    public final EntityExpressionRuntime getExpressionRuntime() {
        lifecycle.assertStateAccess();
        return expressionRuntime;
    }

    protected void onNormalRoamingChanges(dev.semaweave.RoamingPayload changes) {}

    public final <T> Optional<T> exactComponent(Class<T> type) {
        var installed = modelBinding.resourceHolder();
        return lifecycle.isEndingUse() || installed == null ? Optional.empty()
            : requireSession().components.exact(type);
    }

    private <R> R invokeInstalled(Supplier<R> action, R unavailable) {
        lifecycle.rejectRecursiveCall();
        waitForAsyncUpdate();
        if (!canEvaluate())
            return unavailable;
        try {
            return inCall(action);
        } catch (OutOfMemoryError failure) {
            currentFrameExtracted = false;
            return unavailable;
        } catch (ExecutionFailure failure) {
            handleExpressionFailure(failure);
            return unavailable;
        }
    }

    public final boolean readConfigBoolean(long generation, ConfigActionRoute route) {
        return invokeInstalled(() -> getAnimationProcessor().readConfigBoolean(generation, route), false);
    }

    public final float readConfigFloat(long generation, ConfigActionRoute route) {
        return invokeInstalled(() -> getAnimationProcessor().readConfigFloat(generation, route), 0f);
    }

    public final void writeConfigBoolean(long generation, ConfigActionRoute route, boolean value) {
        invokeInstalled(() -> {
            getAnimationProcessor().writeConfigBoolean(generation, route, value);
            return null;
        }, null);
    }

    public final void writeConfigFloat(long generation, ConfigActionRoute route, float value) {
        invokeInstalled(() -> {
            getAnimationProcessor().writeConfigFloat(generation, route, value);
            return null;
        }, null);
    }

    public final void executeConfigLabel(long generation, ConfigActionRoute route, String label, int index) {
        invokeInstalled(() -> {
            getAnimationProcessor().executeConfigLabel(generation, route, label, index);
            return null;
        }, null);
    }

    public final void dispatchSync(EventArguments arguments) {
        invokeInstalled(() -> {
            getAnimationProcessor().dispatchSync(arguments);
            return null;
        }, null);
    }

    protected final void handleExpressionFailure(ExecutionFailure failure) {
        if ((failure.reason() == ExecutionFailure.Reason.STALE_GENERATION)
            && (!isAssigned() || lifecycle.isEndingUse()))
            return;
        if (failure.reason() != ExecutionFailure.Reason.INSTANCE_EXECUTION
            || expressionRuntime.generation() != failure.provenance().entityGeneration())
            throw failure;
        clearOutputs();
        modelBinding.failExpression(failure, fallbackRenderTargetId(), lifecycle::endInstallationUse);
    }

    protected abstract void prepareAnimationControllers(ModelRenderTarget target, AnimationData animations);

    protected abstract GeoModel resolveGeoModel(ModelRenderTarget target, String texture);

    protected AnimationControllerData resolveControllerData(ModelRenderTarget target, String name) {
        return null;
    }

    /**
     * 设置模型
     */
    private ResourceHolder prepareInstallation(ResourceLease lease, boolean intrinsicDefault) {
        if (!(lease.poll() instanceof AcquireResult.Ready ready))
            throw new IllegalArgumentException("Installation requires a Ready lease");
        return lifecycle.inCandidate(() -> {
            ResourceHolder next = null;
            try {
                var previous = modelBinding.resourceHolder();
                var previousSession = animationSession;
                var store = animationStore(ready.target());
                var geometry = Objects.requireNonNull(
                    resolveGeoModel(ready.target(), intrinsicDefault ? "" : requestedTextureName()),
                    "candidate geometry");
                var compatible = previous != null && previousSession != null && modelBinding.usageMatchesOperation()
                    && previous.model.isStateCompatible(ready.target()) && previousSession.components.owner() == this
                    && previousSession.store == store && !lifecycle.isEndingUse();
                if (compatible) {
                    var binding = new ResourceHolder(lease);
                    next = binding;
                    var bindGeometry = previousSession.geoModel.prepareModelBinding(geometry);
                    var bindResources = previousSession.processor.prepareResourceBinding(store.eventHandlers(),
                        previousSession.animations, name -> resolveControllerData(ready.target(), name));
                    binding.abortSharedStateActivation = lifecycle::endInstallationUse;
                    binding.activation = (publication, qualified) -> lifecycle.inCall(() -> {
                        if (!isRunRequired() || !isCurrent(previous) || animationSession != previousSession
                            || !qualified.getAsBoolean())
                            throw new CancellationException("Candidate owner no longer runs");
                        clearOutputs();
                        binding.sharedStateActivationStarted = true;
                        previousSession.components.setRenderTarget(ready.target(),
                            () -> isCurrent(previous) && qualified.getAsBoolean());
                        bindGeometry.run();
                        bindResources.run();
                        publication.run();
                        return null;
                    });
                } else {
                    var candidate = new FullInstallationCandidate(lease);
                    next = candidate;
                    var session = new AnimationSession(store);
                    candidate.session = session;
                    session.geoModel = new AnimatedGeoModel(geometry);
                    if (this instanceof PlayerAnimatableCapability) {
                        var asset = ready.target().physicsAsset();
                        if (asset != null) {
                            var inputs = ready.target().stateIdentity();
                            var level = entity.level();
                            var recovery = physicsRecovery != null && physicsRecovery.blocks(inputs, level)
                                ? physicsRecovery : new PlayerPhysicsSession.RecoveryGate(inputs, level);
                            session.physics =
                                PlayerPhysicsSession.open(entity.getUUID() + " " + ready.target().modelHash() + "/"
                                    + ready.target().renderTargetId(), recovery, () -> createPhysicsWorld(asset));
                        }
                    }
                    session.animations = new AnimationData();
                    var processor = new AnimationProcessor<TEntity>(this);
                    session.processor = processor;
                    session.components = store.prepareComponents(this, null);
                    session.components.setRenderTarget(ready.target(),
                        () -> isRunRequired() && !lifecycle.isEndingUse());
                    if (previousSession != null && previousSession.components != null) {
                        var history = previousSession.components.exact(PositionComponent.class);
                        session.components.exact(PositionComponent.class)
                            .ifPresent(position -> history.ifPresent(position::copyHistoryFrom));
                    }
                    session.expressions = expressionRuntime.prepare(store, session.components, roamingRole());
                    var commitRole = prepareRoleInstallation(ready.target(), session.expressions);
                    prepareAnimationControllers(ready.target(), session.animations);
                    processor.loadModel(session.geoModel, store.eventHandlers(), session.animations,
                        name -> resolveControllerData(ready.target(), name));
                    next.activation = (publication, qualified) -> {
                        if (!isRunRequired() || !qualified.getAsBoolean())
                            throw new CancellationException("Candidate owner no longer runs");
                        clearOutputs();
                        expressionRuntime.install(session.expressions, () -> {
                            try {
                                resetAnimationClock(false);
                                if (commitRole != null)
                                    commitRole.run();
                                publication.run();
                                animationSession = session;
                                physicsRecovery = session.physics == null ? null : session.physics.recoveryGate();
                                candidate.session = null;
                                session.expressions = null;
                            } catch (RuntimeException | Error failure) {
                                try {
                                    session.close();
                                } catch (RuntimeException | Error cleanup) {
                                    throwCleanupFailure(CleanupFailures.merge(failure, cleanup));
                                }
                                throw failure;
                            }
                        }, () -> {
                            if (previousSession != null)
                                previousSession.close();
                        });
                    };
                }
                if (!isRunRequired() || lifecycle.isEndingUse())
                    throw new CancellationException("Candidate owner no longer runs");
                return next;
            } catch (RuntimeException | Error failure) {
                try {
                    if (next != null)
                        next.close();
                } catch (RuntimeException | Error closeFailure) {
                    throwCleanupFailure(CleanupFailures.merge(failure, closeFailure));
                }
                throw failure;
            }
        });
    }

    protected AnimationStore animationStore(ModelRenderTarget target) {
        return target.animationStore();
    }

    protected com.elfmcys.ysm.model.resource.client.CommonAsset assets(ModelRenderTarget target) {
        return target.assets();
    }

    protected @Nullable Runnable prepareRoleInstallation(ModelRenderTarget target,
        EntityExpressionRuntime.PreparedInstallation prepared) {
        return null;
    }

    protected RoamingRole roamingRole() {
        return RoamingRole.CONSUMER;
    }

    public final void reloadGeoModel() {
        rejectCandidateMutation();
        waitForAsyncUpdate();
        physicsRecovery = null;
        modelBinding.retryRejectedInstallations();
        reset();
    }

    /** Restores attempt eligibility only for an explicitly retried, currently installed physics session. */
    public final boolean retryPhysics() {
        lifecycle.rejectRecursiveCall();
        waitForAsyncUpdate();
        return inCall(() -> {
            if (!canEvaluate())
                return false;
            var physics = requireSession().physics;
            if (physics == null || !physics.isDegraded())
                return false;
            var asset = requireInstallation().model.physicsAsset();
            physics.retry(() -> createPhysicsWorld(asset));
            return true;
        });
    }

    private @Nullable NativePhysicsWorld createPhysicsWorld(NativePhysicsAsset asset) {
        var world = NativePhysicsWorld.create(asset);
        if (world != null && PhysicsDebugController.isTarget(this))
            world.setDebugContactCapture(true);
        return world;
    }

    /** Changes contact capture only after the owner has joined any outstanding world task. */
    public final void setPhysicsDebugContactCapture(boolean enabled) {
        waitForAsyncUpdate();
        lifecycle.assertStateAccess();
        var session = animationSession;
        var world = session == null || session.physics == null ? null : session.physics.world();
        if (world != null)
            world.setDebugContactCapture(enabled);
    }

    /** A main-thread read after joining the current owner. No native world or render data escapes. */
    public final @Nullable PhysicsDebugStatus physicsDebugStatus() {
        waitForAsyncUpdate();
        var target = getModelRenderTarget();
        if (target == null || target.physicsMetadata() == null)
            return null;
        var physics = requireSession().physics;
        boolean current =
            physics != null && physics.hasCurrentSample(entity.level(), PlayerTeleportState.capture(entity).epoch());
        return new PhysicsDebugStatus(installationIdentity(), target.physicsMetadata(),
            current ? physics.sampleEpoch() : PlayerPhysicsSession.NO_SAMPLE_EPOCH,
            physics == null ? null : physics.debugFailure());
    }

    public record PhysicsDebugStatus(Object installation, PhysicsAssetMetadata metadata, long sampleEpoch,
        @Nullable String failure) {
    }

    @Nullable
    public final AnimatedGeoModel getLoadedGeoModel() {
        lifecycle.assertStateAccess();
        var current = modelBinding.resourceHolder();
        return current == null || lifecycle.isEndingUse() ? null : requireSession().geoModel;
    }

    public final boolean hasInstalledModel() {
        lifecycle.assertStateAccess();
        return !lifecycle.isEndingUse() && modelBinding.resourceHolder() != null;
    }

    protected final RenderContext resolveRenderContext(RenderContext context) {
        return context.withImmutable(determineImmutableContext(context));
    }

    protected boolean determineImmutableContext(RenderContext context) {
        if (context.inventory() || context.firstPersonMod() || context.paperDoll()) {
            return false;
        }
        return context.level() || context.irisShadow() || context.offScreen();
    }

    public void countRender() {
        currentFrameRendered = true;
    }

    public void executeTransient(String source, TransientExpressionSession.Privilege privilege, boolean pre,
        @Nullable Consumer<String> resultConsumer) {
        rejectCandidateMutation();
        waitForAsyncUpdate();
        if (canEvaluate())
            getAnimationProcessor().enqueueTransient(source, privilege, pre, resultConsumer);
    }

    @Nullable
    public DebugSource getDebugSource() {
        return DebugAnimationScreen.isEnabled() ? CustomDebugSource.INSTANCE : null;
    }

    @SuppressWarnings("resource")
    public boolean isActive() {
        return Minecraft.getInstance().level == entity.level() && !entity.isRemoved();
    }

    public void setCodedAnimationStates(String controllerName, AnimationState state) {
        lifecycle.assertStateAccess();
        this.codedAnimationStates.put(controllerName, state);
    }

    public AnimationState getCodedAnimationStates(String controllerName) {
        lifecycle.assertStateAccess();
        return this.codedAnimationStates.getOrDefault(controllerName, AnimationState.IDLE);
    }

    /** Composite state is queried in the client/render owner window; queries never join or advance. */
    public final boolean isAssigned() {
        return !lifecycle.isRetired() && modelBinding.isAssigned();
    }

    public final boolean hasUnfinishedWork() {
        return lifecycle.hasTask() || demandScope != null && demandScope.hasUnfinishedWork();
    }

    public boolean isPresentationAllowed() {
        return isAssigned() && presentationPermission();
    }

    protected boolean presentationPermission() {
        return true;
    }

    protected boolean isProducer() {
        return false;
    }

    public final boolean isRunRequired() {
        return isAssigned() && (isProducer() || presentationPermission());
    }

    public final boolean canAcceptPlayback() {
        return canEvaluate();
    }

    public final boolean canEvaluate() {
        lifecycle.assertStateAccess();
        return isRunRequired() && !lifecycle.isEndingUse() && modelBinding.permitsCurrentUse();
    }

    public final InstallationRelation getInstallationRelation() {
        if (!hasInstalledModel())
            return InstallationRelation.UNBOUND;
        return modelBinding.relation(requestedRenderTargetId(), requestedTextureName(), resourcePurpose(),
            resourceSubjectType());
    }

    public final boolean hasMatchingInstallation() {
        return getInstallationRelation() == InstallationRelation.MATCHING;
    }

    public final BlockReason getSelectionBlockReason() {
        if (!isAssigned())
            return BlockReason.UNASSIGNED;
        if (!isRunRequired())
            return BlockReason.RESTRICTED;
        if (EntityModelBinding.intrinsicDefaultOnly()
            && (!modelBinding.permitsCurrentUse() || !hasMatchingInstallation()))
            return BlockReason.FAILED;
        if (hasMatchingInstallation())
            return BlockReason.NONE;
        return modelLoadFailure() == null ? BlockReason.WAITING : BlockReason.FAILED;
    }

    public final BlockReason getPreparationBlockReason(RenderContext context) {
        if (!isAssigned())
            return BlockReason.UNASSIGNED;
        if (!isPresentationAllowed())
            return BlockReason.RESTRICTED;
        return canPrepare(context) ? BlockReason.NONE : getSelectionBlockReason();
    }

    public final ModelRenderTarget getModelRenderTarget() {
        lifecycle.assertStateAccess();
        var installed = modelBinding.resourceHolder();
        return installed == null || lifecycle.isEndingUse() ? null : installed.model;
    }

    public final ModelId getModelHash() {
        return modelBinding.modelHash();
    }

    public final String getModelId() {
        var hash = getModelHash();
        return hash == null ? "default"
            : ClientModelService.current().map(service -> service.displayPath(hash)).orElseGet(hash::toString);
    }

    public final @Nullable String getInstalledModelId() {
        var modelId = modelBinding.installedModelId();
        return modelId == null ? null
            : ClientModelService.current().map(service -> service.displayPath(modelId)).orElseGet(modelId::toString);
    }

    public final String getRequestedTextureName() {
        return requestedTextureName();
    }

    public final Throwable modelLoadFailure() {
        lifecycle.assertStateAccess();
        var installedFailure = modelBinding.failure();
        return installedFailure != null ? installedFailure : demandScope == null ? null : demandScope.failure();
    }

    protected final void updateModelHash(ModelId hash) {
        rejectCandidateMutation();
        waitForAsyncUpdate();
        if (lifecycle.isRetired())
            throw new IllegalStateException("Retired entity cannot be assigned");
        modelBinding.updateModelHash(hash, requestedRenderTargetId(), requestedTextureName());
        registerDemandIfAvailable();
    }

    protected final void requestBuiltinDefault() {
        modelBinding.requestBuiltinDefault();
        registerDemandIfAvailable();
    }

    public final void revokeAssignment() {
        rejectCandidateMutation();
        try {
            lifecycle.endUse();
        } finally {
            modelBinding.revokeAssignment();
        }
        ResourceDiagnostics.log("animatable assignment revoked: owner={} subject={} world={}",
            System.identityHashCode(this), entity.getClass().getSimpleName(), System.identityHashCode(entity.level()));
    }

    protected final void restrictionsChanged() {
        rejectCandidateMutation();
        if (!isRunRequired()) {
            lifecycle.endUse();
        }
    }

    protected String requestedTextureName() {
        return "";
    }

    protected String requestedRenderTargetId() {
        return RenderTargetIds.PLAYER;
    }

    protected String fallbackRenderTargetId() {
        return requestedRenderTargetId();
    }

    protected ModelResourceDemand.Mode resourceDemandMode() {
        return ModelResourceDemand.Mode.ONLINE;
    }

    protected com.elfmcys.ysm.model.resource.client.ModelResourcePurpose resourcePurpose() {
        return com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.FULL;
    }

    protected @Nullable Class<?> resourceSubjectType() {
        return null;
    }

    public void checkModelUpdate() {
        lifecycle.rejectRecursiveCall();
        waitForAsyncUpdate();
        if (!isRunRequired()) {
            if (demandScope != null || modelBinding.resourceHolder() != null || expressionRuntime.store().isPresent())
                lifecycle.endUse();
            return;
        }
        checkModelRenderTargetUpdate();
    }

    protected final void checkModelRenderTargetUpdate() {
        if (!isRunRequired())
            return;
        registerDemandIfAvailable();
    }

    public final com.elfmcys.ysm.client.entity.EntityModelDemand demandScope() {
        registerDemandIfAvailable();
        return java.util.Objects.requireNonNull(demandScope, "Client demand owner is unavailable");
    }

    private void registerDemandIfAvailable() {
        if (demandScope == null || demandScope.isClosed()) {
            var coordinator = modelBinding.coordinator();
            if (coordinator != null) {
                var client = Minecraft.getInstance();
                demandScope = coordinator.register(this,
                    client != null && entity == client.player ? WorkPriority.LOCAL_PLAYER : WorkPriority.BACKGROUND);
            }
        }
    }

    private void closeDemandScope() {
        var previous = demandScope;
        demandScope = null;
        if (previous != null)
            previous.close();
    }

    protected void refreshAssignment() {}

    @Override
    public final com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Assignment assignment() {
        lifecycle.assertPublicAccess();
        lifecycle.tryInstallation(() -> {
            refreshAssignment();
            return true;
        });
        var input = modelBinding.assignment(requestedRenderTargetId(), requestedTextureName(), fallbackRenderTargetId(),
            resourceDemandMode(), resourcePurpose(), resourceSubjectType());
        if (modelBinding.resourceHolder() != null && !modelBinding.permitsCurrentUse())
            lifecycle.tryInstallation(() -> {
                lifecycle.endUse();
                return true;
            });
        return new com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Assignment(input.request(), input.fallback(),
            input.mode(), isRunRequired(), input.operation());
    }

    @Override
    public final boolean matches(ResourceRequest request, boolean full) {
        lifecycle.assertStateAccess();
        return !lifecycle.isEndingUse() && modelBinding.matches(request, full);
    }

    @Override
    public final boolean hasInstallation() {
        return hasInstalledModel();
    }

    public final boolean matchesSelection(com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Target target) {
        lifecycle.assertStateAccess();
        return !lifecycle.isEndingUse() && modelBinding.matchesSelection(target);
    }

    public final void selectModel(ModelId hash, String texture, Object operation) {
        rejectNewUse();
        waitForAsyncUpdate();
        modelBinding.select(hash, requestedRenderTargetId(), texture, operation);
        registerDemandIfAvailable();
    }

    public final Object selectionOperation() {
        return modelBinding.selectionOperation();
    }

    public final boolean installedForSelection(Object operation) {
        lifecycle.assertStateAccess();
        return !lifecycle.isEndingUse() && modelBinding.installedForSelection(operation);
    }

    protected final String selectedTexture() {
        return modelBinding.selectedTexture();
    }

    @Override
    public final Throwable failure(ResourceRequest request) {
        lifecycle.assertStateAccess();
        return modelBinding.failure(request);
    }

    /**
     * Attempts installation in the current idle window without waiting for a task.
     *
     * <p>
     * Ensures: INSTALLED means that publication committed. A later observer failure does not undo publication.
     *
     * <p>
     * Failure: BUSY leaves the lease with the caller, including when a task finished but remains unjoined. An entered
     * attempt can return STALE or REJECTED. Required-default installation failures retain their exceptional boundary.
     *
     * <p>
     * Ownership: every entered attempt consumes the lease, including rejection and failure. The installed holder owns
     * the lease after publication; otherwise the attempt closes the lease.
     *
     * <p>
     * Seam: implements [installation publication](docs/architecture/animation/entity-lifecycle.md#安装发布结果).
     *
     * @param request the exact selected request to accept
     * @param lease the caller-owned lease for this attempt
     * @param fallback whether the request is the selected fallback
     * @param full whether the selection requires FULL preparation
     * @return the publication or refusal result
     */
    @Override
    public final com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult
        install(ResourceRequest request, ResourceLease lease, boolean fallback, boolean full, Object operation) {
        if (selectionOperation() != operation) {
            lease.close();
            return com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult.STALE;
        }
        return install(request, lease, fallback, full);
    }

    public final com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult
        install(ResourceRequest request, ResourceLease lease, boolean fallback, boolean full) {
        return lifecycle.tryInstallation(() -> {
            var current = assignment();
            if (!current.permitted() || !request.equals(fallback ? current.fallback() : current.request())) {
                lease.close();
                return com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult.STALE;
            }
            var result = modelBinding.accept(request, lease, fallback, full, this::prepareInstallation);
            if (result == com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult.INSTALLED
                && demandScope != null)
                demandScope.reportInstalledUse(modelBinding.installedRequest());
            return result;
        }).orElse(com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult.BUSY);
    }

    protected final void installReadyForPreview(ResourceRequest request, ResourceLease lease) {
        lifecycle.rejectRecursiveCall();
        waitForAsyncUpdate();
        updateModelHash(request.modelId() instanceof com.elfmcys.ysm.model.domain.PublishedModelId.Model model
            ? model.hash() : null);
        modelBinding.installReadyForPreview(request, lease, this::prepareInstallation);
    }

    private static void reportCleanupFailure(Throwable failure) {
        com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Entity role cleanup failed", failure);
    }

    private ResourceHolder requireInstallation() {
        lifecycle.assertStateAccess();
        var installed = modelBinding.resourceHolder();
        if (installed == null || lifecycle.isEndingUse())
            throw new IllegalStateException("Entity has no usable installation");
        return installed;
    }

    private boolean isCurrent(ResourceHolder installation) {
        return canEvaluate() && modelBinding.resourceHolder() == installation;
    }

    protected final void rejectCandidateMutation() {
        lifecycle.rejectCandidateMutation();
    }

    protected final void rejectNewUse() {
        lifecycle.rejectCandidateMutation();
        if (lifecycle.isRetired())
            throw new IllegalStateException("Retired entity cannot be assigned");
    }

    protected final <R> R inCall(Supplier<R> operation) {
        return lifecycle.inCall(operation);
    }

    protected final Object installationIdentity() {
        return modelBinding.resourceHolder();
    }

    protected final boolean canConsumeInstallation(Object identity) {
        return identity != null && identity == modelBinding.resourceHolder() && canEvaluate()
            && isPresentationAllowed();
    }

    public final boolean canPrepare(RenderContext context) {
        return isPresentationAllowed() && canEvaluate() && (!isFakePlayer() || hasMatchingInstallation());
    }

    public final boolean canConsume(GeoRenderData data, RenderContext context) {
        if (lifecycle.hasTask() || data == null || data.owner != this || !canPrepare(context)
            || data.installation != modelBinding.resourceHolder() || !data.modelState.isValid()
            || !context.equals(data.ctx))
            return false;
        var session = requireSession();
        if (session.physics != null && data.physicsSampleEpoch != PlayerPhysicsSession.NO_SAMPLE_EPOCH)
            session.physics.invalidateIngress(entity.level(), PlayerTeleportState.capture(entity).epoch());
        return data.modelState.getModel() == session.geoModel.getModel() && matchesPhysicsEpoch(data, session.physics);
    }

    private static boolean matchesPhysicsEpoch(GeoRenderData data, @Nullable PlayerPhysicsSession physics) {
        return physics == null || data.physicsSampleEpoch == PlayerPhysicsSession.NO_SAMPLE_EPOCH
            || data.physicsSampleEpoch == physics.sampleEpoch();
    }

    /** Visits an accepted sample only inside the draw borrow for this exact installation and epoch. */
    public final boolean visitPhysicsSample(GeoRenderData data,
        BiConsumer<NativePhysicsWorld, PhysicsAssetMetadata> visitor) {
        lifecycle.assertDrawBorrow();
        if (data == null || data.physicsSampleEpoch == PlayerPhysicsSession.NO_SAMPLE_EPOCH
            || !canConsume(data, data.ctx))
            return false;
        var physics = requireSession().physics;
        if (physics == null || !physics.hasSample())
            return false;
        visitor.accept(physics.world(), requireInstallation().model.physicsMetadata());
        return true;
    }

    public final BlockReason getConsumptionBlockReason(GeoRenderData data, RenderContext context) {
        var reason = getPreparationBlockReason(context);
        return reason != BlockReason.NONE ? reason
            : canConsume(data, context) ? BlockReason.NONE : BlockReason.STALE_RESULT;
    }

    /** The callback borrows this exact result until return; recursive draws are rejected. */
    public final boolean consumePrepared(GeoRenderData data, java.util.function.Predicate<GeoRenderData> consumer) {
        if (data == null)
            return false;
        waitForAsyncUpdate();
        return inDrawBorrow(() -> {
            if (!canConsume(data, data.ctx))
                return false;
            return consumer.test(data);
        });
    }

    private EntityExpressionRuntime.ProjectionSink localProjection;

    protected EntityExpressionRuntime.ProjectionSink prepareLocalProjection() {
        return (delta, finalWindow) -> {
            onNormalRoamingChanges(delta);
            return true;
        };
    }

    /** Completes ordered owner inputs before ordinary animation can borrow this installation. */
    protected void prepareStateInputs() {}

    private void prepareEvaluation() {
        if (!canEvaluate())
            return;
        prepareStateInputs();
        if (!canEvaluate())
            return;
        expressionRuntime.prepareExecution();
        localProjection = prepareLocalProjection();
        getAnimationProcessor().prepareTransientWork();
    }

    protected final <R> R inDrawBorrow(Supplier<R> operation) {
        return lifecycle.inDraw(operation);
    }

    /**
     * Reports outstanding execution authority, including completed work that has not been joined.
     *
     * @return a current observation, not a reservation for a later operation
     */
    public final boolean hasPendingUpdate() {
        return lifecycle.hasTask();
    }

    protected final void assertExecutionStateAccess() {
        lifecycle.assertStateAccess();
    }

    protected final void assertPublicMutationAccess() {
        lifecycle.rejectCandidateMutation();
    }

    /**
     * Seals a GUI owner for one task before an executor accepts that task.
     *
     * <p>
     * Requires: the caller is on the main thread and the GUI owner is active, idle, and has no task. The operation must
     * use only the supplied permit's internal operations on this owner.
     *
     * <p>
     * Execution: the operation runs inline in GuiTask.run(), at most once, on the selected execution thread. The owner
     * remains sealed after completion until explicit join. Rejection is only for a task that never started.
     *
     * @param operation the synchronous operation that borrows the exact task permit
     * @return the task whose run, rejection, and join obligations belong to the caller
     */
    public final <R> GuiTask<R> reserveGuiTask(java.util.function.Function<GuiExecution, R> operation) {
        lifecycle.rejectRecursiveCall();
        Objects.requireNonNull(operation, "operation");
        if (resourcePurpose() != com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.GUI)
            throw new IllegalStateException("Only a GUI entity can reserve GUI execution");
        var previousInstallation = modelBinding.resourceHolder();
        var execution = new GuiExecution(assignment());
        var task = lifecycle.reserveGuiTask(true, () -> operation.apply(execution));
        execution.task = task;
        return new GuiTask<>(task, () -> {
            finishProjections(true);
            if (modelBinding.resourceHolder() != previousInstallation && demandScope != null) {
                var failure =
                    attemptCleanup(null, () -> demandScope.reportInstalledUse(modelBinding.installedRequest()));
                failure =
                    attemptCleanup(failure, () -> demandScope.observeInstallation(modelBinding.installedRequest()));
                throwCleanupFailure(failure);
            }
        });
    }

    /**
     * Grants internal access only while this permit's exact task executes.
     *
     * <p>
     * Ownership: the callback must not retain this permit for later use or use it for another owner. The permit grants
     * neither public lifecycle access nor draw access, even when the task runs on the main thread.
     */
    public final class GuiExecution {
        private final com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Assignment assignment;
        private EntityExecutionLifecycle.GuiTask<?> task;

        private GuiExecution(com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Assignment assignment) {
            this.assignment = assignment;
        }

        /**
         * Applies the captured GUI assignment inside this exact task's installation window.
         *
         * <p>
         * Ownership: the operation consumes the lease on every entered path. A permit or thread violation occurs before
         * entry and leaves the supplied lease with the caller.
         *
         * @return INSTALLED after publication, or STALE or REJECTED after consuming the lease
         */
        public com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult install(ResourceRequest request,
            ResourceLease lease, boolean fallback, boolean full) {
            return install(request, lease, fallback, full, assignment.operation());
        }

        public com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult install(ResourceRequest request,
            ResourceLease lease, boolean fallback, boolean full, Object operation) {
            return lifecycle.inGuiExecution(task, () -> {
                if (!isRunRequired() || lifecycle.isEndingUse() || assignment.operation() != operation
                    || modelBinding.selectionOperation() != operation || !assignment.permitted()
                    || !request.equals(fallback ? assignment.fallback() : assignment.request())) {
                    lease.close();
                    return com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult.STALE;
                }
                return modelBinding.accept(request, lease, fallback, full, AnimatableEntity.this::prepareInstallation);
            });
        }

        /**
         * Prepares CPU pose output while this exact GUI task owns the entity.
         *
         * <p>
         * Failure: allocation failure invalidates this extraction and returns null. Other failures follow the task's
         * exceptional completion. This operation does not submit a host draw.
         *
         * <p>
         * Ownership: the returned pose borrows this installation's output. The consumer must join the task before draw
         * consumption and must not retain the pose across a later owner update or ended use.
         *
         * @return the prepared pose, or null when this task produced no consumable pose
         */
        public @Nullable GeoRenderData update(float partialTicks, RenderContext context) {
            return lifecycle.inGuiExecution(task, () -> {
                prepareEvaluation();
                var resolved = resolveRenderContext(context);
                try {
                    return lifecycle.inCall(() -> computePose(partialTicks, resolved));
                } catch (OutOfMemoryError failure) {
                    currentFrameExtracted = false;
                    return null;
                }
            });
        }
    }

    /**
     * Carries one sealed GUI execution and its explicit authority-return operation.
     *
     * <p>
     * Execution: run() executes once at most. The caller must reject an unaccepted, unstarted task, or join an accepted
     * task on the main thread. Completion alone does not return owner access. A callback must not join its own task.
     */
    public static final class GuiTask<R> implements Runnable {
        private final EntityExecutionLifecycle.GuiTask<R> task;
        private final Runnable finish;

        private GuiTask(EntityExecutionLifecycle.GuiTask<R> task, Runnable finish) {
            this.task = task;
            this.finish = finish;
        }

        @Override
        public void run() {
            task.run();
        }

        public void reject() {
            task.reject();
        }

        /**
         * Waits for the complete task and returns execution authority before adopting final projections.
         *
         * <p>
         * Failure: task and cleanup failures leave the task joined; combined GUI failures preserve fatal-error
         * priority. Interruptions do not abandon the wait; the method restores the interrupt flag after the terminal
         * handoff.
         *
         * @return the callback result after the task and its cleanup complete
         */
        public R join() {
            Throwable primary = null;
            try {
                return task.join();
            } catch (RuntimeException | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                try {
                    finish.run();
                } catch (RuntimeException | Error failure) {
                    var combined = EntityExecutionLifecycle.appendGuiFailure(primary, failure);
                    if (combined != primary) {
                        if (combined instanceof RuntimeException runtime)
                            throw runtime;
                        if (combined instanceof Error error)
                            throw error;
                    }
                }
            }
        }
    }

    /** Stable installation routing; this identity does not grant access to its mutable state. */
    public final Object projectionIdentity() {
        return modelBinding.resourceHolder();
    }

    public final boolean canAdoptProjection(Object identity) {
        return lifecycle.canAdoptProjection() && identity != null && identity == modelBinding.resourceHolder()
            && canEvaluate();
    }

    /**
     * Retains this owner once for the open world invocation, before submission or synchronous projection adoption.
     *
     * <p>
     * Requires: the caller uses the main thread inside an open invocation. Registration lasts through all task and
     * final projection handoffs, including reset and retirement.
     */
    public final void retainForAnimationInvocation() {
        if (!invocationRetained) {
            AnimationParallelTicker.retainForFinalProjection(this);
            invocationRetained = true;
        }
    }

    /** Requires the coordinator's main-thread drain of all tasks and final projections to have completed. */
    public final void releaseAnimationInvocation() {
        invocationRetained = false;
    }

    public final void finishProjections(boolean finalWindow) {
        lifecycle.assertMainThread();
        if (!finalWindow && AnimationParallelTicker.isInvocationActive())
            retainForAnimationInvocation();
        if (!lifecycle.hasTask())
            expressionRuntime.adoptProjections(finalWindow);
    }

    /**
     * Starts this invocation's owner update, or updates synchronously outside an invocation.
     *
     * <p>
     * Execution: the main-thread caller first joins prior work and prepares the owner before submission. Successful
     * submission transfers private-state access until waitForAsyncUpdate() completes. Submission failure propagates
     * without leaving a pending task.
     *
     * @param partialTicks the frame interpolation fraction supplied by the host
     */
    public final void beginAsyncUpdate(float partialTicks) {
        lifecycle.rejectRecursiveCall();
        if (!AnimationParallelTicker.isInvocationActive()) {
            update(partialTicks, RenderContext.levelImmutable());
            return;
        }
        waitForAsyncUpdate();
        checkModelUpdate();
        if (!canEvaluate())
            return;
        prepareEvaluation();
        if (!canEvaluate())
            return;
        lifecycle.submit(() -> computePose(partialTicks, RenderContext.levelImmutable()));
    }

    /**
     * Joins ordinary work before returning private-state access and adopting eligible projections.
     *
     * <p>
     * Execution: the main-thread call can block. Task completion includes the task's finally blocks. Interruptions do
     * not release the owner early; the method restores the interrupt flag after the wait. A reserved GUI task requires
     * GuiTask.join() instead.
     */
    public final void waitForAsyncUpdate() {
        waitForUpdate(false);
    }

    /**
     * Joins ordinary animation for an entity render, executing the original task if it is still queued. All task and
     * projection handoff obligations remain the same as {@link #waitForAsyncUpdate()}.
     */
    public final void waitForRenderUpdate() {
        waitForUpdate(true);
    }

    private void waitForUpdate(boolean forRender) {
        try {
            if (forRender)
                lifecycle.awaitTaskForRender();
            else
                lifecycle.awaitTask();
        } finally {
            if (!lifecycle.hasTask())
                finishProjections(!AnimationParallelTicker.isInvocationActive());
        }
    }

    private void handleAsyncFailure(Throwable failure) {
        if (failure instanceof OutOfMemoryError) {
            currentFrameExtracted = false;
            return;
        }
        if (failure instanceof FailedExtract failedExtract)
            handleFailedExtract(failedExtract);
        else if (failure instanceof FailedUse failedUse)
            handleFailedUse(failedUse);
        else if (failure instanceof ExecutionFailure expressionFailure)
            handleExpressionFailure(expressionFailure);
        else if (failure instanceof Error error)
            throw error;
        else if (failure instanceof RuntimeException runtime)
            throw runtime;
        else if (failure != null)
            throw new IllegalStateException("Entity animation task failed", failure);
    }

    private static boolean containsComponentFailure(Throwable failure) {
        if (failure instanceof ComponentLifecycleException)
            return true;
        for (var suppressed : failure.getSuppressed())
            if (containsComponentFailure(suppressed))
                return true;
        return false;
    }

    private void handleFailedExtract(FailedExtract failure) {
        if (modelBinding.resourceHolder() != failure.installation)
            return;
        if (modelBinding.isIntrinsicDefault())
            throw (RuntimeException)failure.getCause();
        // The logical round has already committed; ending its runtime would discard pending projections.
        com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Entity output extraction failed", failure);
    }

    private static final class FailedExtract extends RuntimeException {
        private final ResourceHolder installation;

        private FailedExtract(ResourceHolder installation, AnimationEvent<?> event, RuntimeException cause) {
            super("Output extraction failed: model=" + installation.model.modelHash() + " target="
                + installation.model.renderTargetId() + " context=" + event.getRenderContext() + " tick="
                + event.getEntityTickCount() + " time=" + event.renderTicks, cause);
            this.installation = installation;
        }
    }

    private void handleFailedUse(FailedUse failure) {
        var notice = modelBinding.rejectActiveInstallation(failure.installation, (RuntimeException)failure.getCause());
        lifecycle.endUse();
        if (notice == null)
            return;
        com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Entity component lifecycle failed: installation={}",
            failure.installation, failure);
        if (notice.intrinsicDefault())
            throw notice.cause();
        modelBinding.reportFailure(notice, fallbackRenderTargetId() != null);
        if (isAssigned())
            registerDemandIfAvailable();
    }

    private static final class FailedUse extends RuntimeException {
        private final ResourceHolder installation;

        private FailedUse(AnimatableEntity<?> owner, ResourceHolder installation, AnimationEvent<?> event,
            RuntimeException cause) {
            super("Component lifecycle failed: owner=" + owner.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(owner)) + " request=" + installation.model.modelHash()
                + " target=" + installation.model.modelHash() + "/" + installation.model.renderTargetId() + " context="
                + event.getRenderContext() + " tick=" + event.getEntityTickCount() + " time=" + event.renderTicks,
                cause);
            this.installation = installation;
        }
    }

    public final void setDebugInfo(@Nullable DebugInfo debugInfo) {
        waitForAsyncUpdate();
        if (hasInstalledModel())
            getAnimationProcessor().setDebugInfo(debugInfo);
    }

    @Nullable
    public final UserFunctionProgram getCompiledUserFunction(String name) {
        var target = getModelRenderTarget();
        return target == null ? null : requireSession().store.functions().get(name);
    }

    private AnimationSession requireSession() {
        requireInstallation();
        return Objects.requireNonNull(animationSession, "Entity has no animation session");
    }

    private static final class AnimationSession implements AutoCloseable {
        private AnimationStore store;
        private AnimatedGeoModel geoModel;
        private AnimationData animations;
        private AnimationProcessor<?> processor;
        private ComponentScope components;
        private boolean componentsSampled;
        private EntityExpressionRuntime.PreparedInstallation expressions;
        private @Nullable PlayerPhysicsSession physics;
        private float canonicalBonePoseFrameTime = Float.NEGATIVE_INFINITY;
        private float canonicalBonePosePartialTick;
        private boolean observingPose;
        private float[] savedBoneAttributes;

        private AnimationSession(AnimationStore store) {
            this.store = store;
        }

        private float[] saveBoneAttributes() {
            var attributes = geoModel.getBoneAttributes();
            if (savedBoneAttributes == null)
                savedBoneAttributes = new float[attributes.length];
            System.arraycopy(attributes, 0, savedBoneAttributes, 0, attributes.length);
            return savedBoneAttributes;
        }

        @Override
        public void close() {
            var failure = attemptCleanup(null, () -> {
                if (physics != null)
                    physics.close();
            });
            failure = attemptCleanup(failure, () -> {
                if (processor != null)
                    processor.clearModel();
            });
            failure = attemptCleanup(failure, () -> {
                if (animations != null)
                    animations.reset();
            });
            failure = attemptCleanup(failure, () -> {
                if (components != null)
                    components.invalidate();
            });
            failure = attemptCleanup(failure, () -> {
                if (expressions != null)
                    expressions.close();
            });
            expressions = null;
            physics = null;
            savedBoneAttributes = null;
            processor = null;
            animations = null;
            components = null;
            geoModel = null;
            store = null;
            throwCleanupFailure(failure);
        }
    }

    /** A replaceable target binding. Only a full candidate owns unpublished private state. */
    static class ResourceHolder implements Closeable {
        private ResourceLease lease;
        public ModelRenderTarget model;
        private boolean closed;
        private boolean sharedStateActivationStarted;
        private Runnable abortSharedStateActivation;
        private BiConsumer<Runnable, BooleanSupplier> activation = (publication, qualified) -> publication.run();

        ResourceHolder(ResourceLease lease) {
            this.lease = lease;
            if (!(lease.poll() instanceof AcquireResult.Ready ready))
                throw new IllegalArgumentException("Installation requires a Ready lease");
            model = ready.target();
        }

        ResourceLease lease() {
            return lease;
        }

        void activate(Runnable publication, BooleanSupplier qualified) {
            var prepared = Objects.requireNonNull(activation, "installation already activated");
            activation = null;
            prepared.accept(publication, qualified);
        }

        boolean sharedStateActivationStarted() {
            return sharedStateActivationStarted;
        }

        void abortSharedStateActivation() {
            if (abortSharedStateActivation != null)
                abortSharedStateActivation.run();
        }

        @Override
        public void close() {
            if (closed)
                return;
            closed = true;
            var previous = lease;
            lease = null;
            model = null;
            activation = null;
            abortSharedStateActivation = null;
            if (previous != null)
                previous.close();
        }
    }

    private static final class FullInstallationCandidate extends ResourceHolder {
        private AnimationSession session;

        private FullInstallationCandidate(ResourceLease lease) {
            super(lease);
        }

        @Override
        public void close() {
            var retired = session;
            session = null;
            Throwable failure = null;
            try {
                if (retired != null)
                    retired.close();
            } catch (RuntimeException | Error cleanup) {
                failure = cleanup;
            }
            try {
                super.close();
            } catch (RuntimeException | Error cleanup) {
                failure = CleanupFailures.merge(failure, cleanup);
            }
            throwCleanupFailure(failure);
        }
    }

}
