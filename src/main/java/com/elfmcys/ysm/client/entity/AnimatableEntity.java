package com.elfmcys.ysm.client.entity;

import java.io.UncheckedIOException;
import java.io.IOException;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.expression.host.builtin.PositionComponent;
import com.elfmcys.ysm.util.ResourceDiagnostics;

import com.elfmcys.ysm.client.animation.physics.PhysicsManager;
import com.elfmcys.ysm.client.event.ClientTickEvent;
import com.elfmcys.ysm.client.sound.instance.AudioPlaybackFactory;
import com.elfmcys.ysm.geckolib3.core.AnimationState;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
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
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.provider.data.EntityModelData;
import com.elfmcys.ysm.natives.NativeProfiler;
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
import com.elfmcys.ysm.model.domain.Hash256;
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
 * <p>Invariant: each owner has one execution authority. An outstanding task keeps that authority
 * until the corresponding wait or GUI join completes, including task cleanup.
 *
 * <p>Execution: public lifecycle operations run on the render thread. Worker execution uses the
 * owner's reserved window. A GUI permit grants only its exact task's internal installation and
 * evaluation operations. Callbacks must not reenter public lifecycle operations.
 * Owner confinement does not make the live entity, level, or optional host APIs immutable or safe for concurrent access.
 *
 * <p>Ownership: resource getters and render data borrow the current installation; they do not transfer its lease.
 *
 * <p>Seam: establishes [execution authority and task handoff](docs/architecture/animation/entity-lifecycle.md#执行权与任务交接) and depends on [complete installation and replacement](docs/architecture/animation/entity-lifecycle.md#模型使用与完整安装).
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
    private com.elfmcys.ysm.client.demand.ClientDemandCoordinator.EntityScope demandScope;
    private PhysicsManager alterPhysicsManager;

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

    protected AnimatableEntity(TEntity entity) {
        this(entity, false);
    }

    protected AnimatableEntity(TEntity entity, boolean asyncUpdate) {
        this(entity, asyncUpdate, new EntityModelBinding());
    }

    AnimatableEntity(TEntity entity, boolean asyncUpdate, EntityModelBinding modelBinding) {
        this.entity = Objects.requireNonNull(entity, "entity");
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
            if (primary == null)
                return failure;
            if ((failure instanceof VirtualMachineError && !(failure instanceof OutOfMemoryError)
                || failure instanceof ThreadDeath)
                && !(primary instanceof VirtualMachineError || primary instanceof ThreadDeath)) {
                failure.addSuppressed(primary);
                return failure;
            }
            if (failure != primary)
                primary.addSuppressed(failure);
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
     * <p>Execution: joins an outstanding ordinary task before cleanup. An active draw borrow delays
     * resource cleanup until the borrow ends, but does not delay revocation.
     */
    public final void retire() {
        lifecycle.rejectCandidateMutation();
        try {
            lifecycle.retire();
        } finally {
            modelBinding.revokeAssignment();
        }
    }

    private void clearEndedUse() {
        var failure = attemptCleanup(null, this::closeDemandScope);
        failure = attemptCleanup(failure, this::clearOutputs);
        failure = attemptCleanup(failure, this::clearInstalledRuntime);
        failure = attemptCleanup(failure, modelBinding::releaseRenderTarget);
        throwCleanupFailure(failure);
    }

    private void clearInstalledRuntime() {
        var failure = attemptCleanup(null, expressionRuntime::clear);
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
        return requireInstallation().animations;
    }

    public abstract ResourceLocation getTextureLocation();

    public abstract float getWidthScale();

    public abstract float getHeightScale();

    @Nullable
    public abstract Animation getAnimation(String name);

    private AnimationStore readAnimationSource;
    private AnimationStore.ReadBatch animationReads;
    private boolean animationReadScope;

    protected AnimationStore animationSource() {
        return null;
    }

    protected final Animation readAnimation(AnimationStore source, String name) {
        return animationReadView(source).get(name);
    }

    public final AnimationStore animationResolutionSource() {
        return animationSource();
    }

    public final AnimationStore.Resolution resolveAnimation(String name) {
        var source = animationSource();
        return source == null ? new AnimationStore.Resolution(getAnimation(name), false, false)
            : animationReadView(source).resolve(name);
    }

    private AnimationStore animationReadView(AnimationStore source) {
        if (!animationReadScope || readAnimationSource != source)
            return source;
        if (animationReads == null) {
            try {
                animationReads = source.openReadPhase();
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
     * <p>Execution: a stable world subject remains readable on the main thread during ordinary work.
     * A reserved GUI task excludes public subject access until join.
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
     * <p>Ownership: the result borrows the current installation and mutable pose output. A later
     * owner update or ended use can invalidate that output. The caller must use consumePrepared()
     * to qualify consumption; a non-null return does not reserve later draw access.
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
        waitForAsyncUpdate();
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
            tickAnimation(event);
            var data = getRenderData(context);
            if (!context.immutable() || !currentFrameExtracted || !data.modelState.isValid()
                || data.modelState.getModel() != installation.geoModel.getModel() || !context.equals(data.ctx)
                || partialTicks != data.partialTicks) {
                currentFrameExtracted = false;
                data.installation = null;
                try {
                    data.modelState.extract(installation.geoModel);
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

    /** Allows a synchronous host to consume a local result without extracting the whole model. */
    protected boolean updateAnimationOnly(float partialTicks, RenderContext context) {
        RenderSystem.assertOnRenderThread();
        lifecycle.rejectRecursiveCall();
        waitForAsyncUpdate();
        checkModelUpdate();
        prepareEvaluation();
        try {
            return inCall(() -> {
                if (!canEvaluate())
                    return false;
                tickAnimation(createAnimationEvent(partialTicks, resolveRenderContext(context)));
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

    private GeoRenderData getRenderData(RenderContext context) {
        GeoRenderData renderData;
        if (context.immutable()) {
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
        var source = animationSource();
        if (source == null || animationReadScope && readAnimationSource == source) {
            tickAnimationNow(animationEvent);
            return;
        }
        var previousSource = readAnimationSource;
        var previousReads = animationReads;
        var previousScope = animationReadScope;
        readAnimationSource = source;
        animationReads = null;
        animationReadScope = true;
        Throwable primaryFailure = null;
        try {
            tickAnimationNow(animationEvent);
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
    }

    private void tickAnimationNow(AnimationEvent<AnimatableEntity<TEntity>> animationEvent) {
        var installation = requireInstallation();
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
                    expressionRuntime.animationPassProjected(inputs, shouldTick, evaluation -> {
                        installation.components.evaluate(animationEvent, () -> true, () -> {
                            if (shouldTick) {
                                currentFrameTicked = true;
                                advanceComponentTime(installation, animationEvent);
                            }
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
                        return null;
                    }, localProjection);
                } catch (RuntimeException failure) {
                    if (!containsComponentFailure(failure)) {
                        if (failure instanceof CancellationException)
                            return;
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
    }

    private void advanceComponentTime(ResourceHolder installation, AnimationEvent<?> event) {
        var firstOwnerSample = !sampledMainTimeline;
        var firstComponentSample = !installation.componentsSampled;
        int currentTick =
            firstOwnerSample ? event.getEntityTickCount() : Math.max(sampledEntityTick, event.getEntityTickCount());
        int previousTick = firstOwnerSample ? currentTick : sampledEntityTick;
        float currentTime = seekTime;
        float previousTime = firstOwnerSample ? currentTime : sampledAnimationTime;
        sampledMainTimeline = true;
        sampledEntityTick = currentTick;
        sampledAnimationTime = currentTime;
        installation.componentsSampled = true;
        if (firstOwnerSample || currentTick > previousTick)
            stateTracker.tick(previousTick, currentTick);
        if (firstComponentSample || currentTick > previousTick)
            installation.components.tick(firstComponentSample ? currentTick : previousTick, currentTick,
                () -> true);
        if (firstOwnerSample || currentTime > previousTime)
            stateTracker.update(previousTime, currentTime, event.getPartialTick());
        if (firstComponentSample || currentTime > previousTime)
            installation.components.update(firstComponentSample ? currentTime : previousTime, currentTime,
                event.getPartialTick(), () -> true);
    }

    protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<TEntity>> animationEvent,
        boolean shouldUpdate) {}

    protected void recoverLastCodedAnimation() {}

    @SuppressWarnings("unchecked")
    public final AnimationProcessor<TEntity> getAnimationProcessor() {
        return (AnimationProcessor<TEntity>)requireInstallation().processor;
    }

    public final EntityExpressionRuntime getExpressionRuntime() {
        lifecycle.assertStateAccess();
        return expressionRuntime;
    }

    protected void onNormalRoamingChanges(dev.semaweave.RoamingPayload changes) {}

    public final <T> Optional<T> exactComponent(Class<T> type) {
        var installed = modelBinding.resourceHolder();
        return lifecycle.isEndingUse() || installed == null ? Optional.empty() : installed.components.exact(type);
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
        if ((failure.reason() == ExecutionFailure.Reason.STALE_GENERATION
            || failure.reason() == ExecutionFailure.Reason.CANCELLATION) && (!isAssigned() || lifecycle.isEndingUse()))
            return;
        if (failure.reason() != ExecutionFailure.Reason.NON_NULL_RETURN
            || expressionRuntime.generation() != failure.provenance().entityGeneration())
            throw failure;
        clearOutputs();
        modelBinding.failExpression(failure, fallbackRenderTargetId(), this::prepareInstallation);
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
        if (!(lease.poll() instanceof AcquireResult.Ready ready)) {
            throw new IllegalArgumentException("Installation requires a Ready lease");
        }
        return lifecycle.inCandidate(() -> {
            var next = new ResourceHolder(lease);
            try {
                var previous = modelBinding.resourceHolder();
                var module = expressions(ready.target());
                var geometry = Objects.requireNonNull(
                    resolveGeoModel(ready.target(), intrinsicDefault ? "" : requestedTextureName()),
                    "candidate geometry");
                var compatible = previous != null && previous.model.isStateCompatible(ready.target())
                    && previous.components.owner() == this && expressionRuntime.module().orElse(null) == module
                    && !lifecycle.isEndingUse();
                if (compatible) {
                    next.ownsState = false;
                    next.geoModel = previous.geoModel;
                    next.animations = previous.animations;
                    next.processor = previous.processor;
                    next.components = previous.components;
                    next.componentsSampled = previous.componentsSampled;
                    var bindGeometry = next.geoModel.prepareModelBinding(geometry);
                    var bindResources = next.processor.prepareResourceBinding(assets(ready.target()).eventHandlers(),
                        next.animations, name -> resolveControllerData(ready.target(), name));
                    next.abortSharedStateActivation = lifecycle::endInstallationUse;
                    next.activation = (publication, qualified) -> lifecycle.inCall(() -> {
                        if (!isRunRequired() || !isCurrent(previous) || !qualified.getAsBoolean())
                            throw new CancellationException("Candidate owner no longer runs");
                        clearOutputs();
                        next.sharedStateActivationStarted = true;
                        next.components.setRenderTarget(ready.target(),
                            () -> isCurrent(previous) && qualified.getAsBoolean());
                        bindGeometry.run();
                        bindResources.run();
                        previous.ownsState = false;
                        next.ownsState = true;
                        publication.run();
                        return null;
                    });
                } else {
                    next.geoModel = new AnimatedGeoModel(geometry);
                    next.animations = new AnimationData();
                    var processor = new AnimationProcessor<TEntity>(this);
                    next.processor = processor;
                    next.components = module.prepareComponents(this, null);
                    next.components.setRenderTarget(ready.target(), () -> isRunRequired() && !lifecycle.isEndingUse());
                    if (previous != null && previous.components != null) {
                        var history = previous.components.exact(PositionComponent.class);
                        next.components.exact(PositionComponent.class)
                            .ifPresent(position -> history.ifPresent(position::copyHistoryFrom));
                    }
                    next.expressions = expressionRuntime.prepare(module, assets(ready.target()).eventHandlers(),
                        next.components, roamingRole());
                    var commitRole = prepareRoleInstallation(ready.target(), next.expressions);
                    prepareAnimationControllers(ready.target(), next.animations);
                    processor.loadModel(next.geoModel, assets(ready.target()).eventHandlers(), next.animations,
                        name -> resolveControllerData(ready.target(), name));
                    next.activation = (publication, qualified) -> {
                        if (!isRunRequired() || !qualified.getAsBoolean())
                            throw new CancellationException("Candidate owner no longer runs");
                        clearOutputs();
                        expressionRuntime.install(next.expressions, () -> {
                            next.ownsState = true;
                            resetAnimationClock(false);
                            if (commitRole != null)
                                commitRole.run();
                            publication.run();
                        });
                        next.expressions = null;
                    };
                }
                return next;
            } catch (RuntimeException | Error failure) {
                try {
                    next.close();
                } catch (RuntimeException | Error closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        });
    }

    protected RenderTargetExpressionModule expressions(ModelRenderTarget target) {
        return target.expressions();
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
        modelBinding.retryRejectedInstallations();
        reset();
    }

    @Nullable
    public final AnimatedGeoModel getLoadedGeoModel() {
        lifecycle.assertStateAccess();
        var current = modelBinding.resourceHolder();
        return current == null || lifecycle.isEndingUse() ? null : current.geoModel;
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
        return isRunRequired() && !lifecycle.isEndingUse() && modelBinding.permitsCurrentUse()
            && (fallbackRenderTargetId() != null || requestedRenderTargetId() != null);
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

    public final Hash256 getModelHash() {
        return modelBinding.modelHash();
    }

    public final String getModelId() {
        var hash = getModelHash();
        return hash == null ? "default"
            : ClientModelService.current().map(service -> service.displayPath(hash)).orElseGet(hash::toString);
    }

    public final @Nullable String getInstalledModelId() {
        var target = getModelRenderTarget();
        return target == null ? null : ClientModelService.current()
            .map(service -> service.displayPath(target.modelHash())).orElseGet(() -> target.modelHash().toString());
    }

    public final String getRequestedTextureName() {
        return requestedTextureName();
    }

    public final Throwable modelLoadFailure() {
        lifecycle.assertStateAccess();
        var installedFailure = modelBinding.failure();
        return installedFailure != null ? installedFailure : demandScope == null ? null : demandScope.failure();
    }

    protected final void updateModelHash(Hash256 hash) {
        rejectCandidateMutation();
        waitForAsyncUpdate();
        if (lifecycle.isRetired())
            throw new IllegalStateException("Retired entity cannot be assigned");
        modelBinding.updateModelHash(hash, requestedRenderTargetId(), requestedTextureName());
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
            if (demandScope != null || modelBinding.resourceHolder() != null || expressionRuntime.module().isPresent())
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

    public final com.elfmcys.ysm.client.demand.ClientDemandCoordinator.EntityScope demandScope() {
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
        if (input.request() == null && input.fallback() == null && modelBinding.resourceHolder() != null)
            lifecycle.tryInstallation(() -> {
                lifecycle.endUse();
                return true;
            });
        return new com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Assignment(input.request(), input.fallback(),
            input.mode(), isRunRequired());
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

    @Override
    public final Throwable failure(ResourceRequest request) {
        lifecycle.assertStateAccess();
        return modelBinding.failure(request);
    }

    /**
     * Attempts installation in the current idle window without waiting for a task.
     *
     * <p>Ensures: INSTALLED means that publication committed. A later observer failure does not undo publication.
     *
     * <p>Failure: BUSY leaves the lease with the caller, including when a task finished but remains unjoined.
     * An entered attempt can return STALE or REJECTED. Required-default installation failures retain their exceptional boundary.
     *
     * <p>Ownership: every entered attempt consumes the lease, including rejection and failure.
     * The installed holder owns the lease after publication; otherwise the attempt closes the lease.
     *
     * <p>Seam: implements [installation publication](docs/architecture/animation/entity-lifecycle.md#安装发布结果).
     *
     * @param request the exact selected request to accept
     * @param lease the caller-owned lease for this attempt
     * @param fallback whether the request is the selected fallback
     * @param full whether the selection requires FULL preparation
     * @return the publication or refusal result
     */
    @Override
    public final com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult
        install(ResourceRequest request, ResourceLease lease, boolean fallback, boolean full) {
        return lifecycle.tryInstallation(() -> {
            var current = assignment();
            if (!current.permitted() || !request.equals(fallback ? current.fallback() : current.request())) {
                lease.close();
                return com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult.STALE;
            }
            return modelBinding.accept(request, lease, fallback, full, this::prepareInstallation);
        }).orElse(com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult.BUSY);
    }

    protected final void installReadyForPreview(ResourceRequest request, ResourceLease lease) {
        lifecycle.rejectRecursiveCall();
        waitForAsyncUpdate();
        updateModelHash(request.modelId());
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
        return !lifecycle.hasTask() && data != null && data.owner == this && canPrepare(context)
            && data.installation == modelBinding.resourceHolder() && data.modelState.isValid()
            && context.equals(data.ctx) && getLoadedGeoModel() != null
            && data.modelState.getModel() == getLoadedGeoModel().getModel();
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
        return (delta, finalWindow) -> { onNormalRoamingChanges(delta); return true; };
    }

    private void prepareEvaluation() {
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
    public final boolean hasPendingUpdate() { return lifecycle.hasTask(); }

    protected final void assertExecutionStateAccess() { lifecycle.assertStateAccess(); }

    protected final void assertPublicMutationAccess() { lifecycle.rejectCandidateMutation(); }

    /**
     * Seals a GUI owner for one task before an executor accepts that task.
     *
     * <p>Requires: the caller is on the main thread and the GUI owner is active, idle, and has no task.
     * The operation must use only the supplied permit's internal operations on this owner.
     *
     * <p>Execution: the operation runs inline in GuiTask.run(), at most once, on the selected execution thread.
     * The owner remains sealed after completion until explicit join. Rejection is only for a task that never started.
     *
     * @param operation the synchronous operation that borrows the exact task permit
     * @return the task whose run, rejection, and join obligations belong to the caller
     */
    public final <R> GuiTask<R> reserveGuiTask(java.util.function.Function<GuiExecution, R> operation) {
        lifecycle.rejectRecursiveCall();
        Objects.requireNonNull(operation, "operation");
        if (resourcePurpose() != com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.GUI)
            throw new IllegalStateException("Only a GUI entity can reserve GUI execution");
        var execution = new GuiExecution(assignment());
        var task = lifecycle.reserveGuiTask(true, () -> operation.apply(execution));
        execution.task = task;
        return new GuiTask<>(task, () -> finishProjections(true));
    }

    /**
     * Grants internal access only while this permit's exact task executes.
     *
     * <p>Ownership: the callback must not retain this permit for later use or use it for another owner.
     * The permit grants neither public lifecycle access nor draw access, even when the task runs on the main thread.
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
         * <p>Ownership: the operation consumes the lease on every entered path. A permit or thread violation
         * occurs before entry and leaves the supplied lease with the caller.
         *
         * @return INSTALLED after publication, or STALE or REJECTED after consuming the lease
         */
        public com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult install(
            ResourceRequest request, ResourceLease lease, boolean fallback, boolean full) {
            return lifecycle.inGuiExecution(task, () -> {
                if (!assignment.permitted()
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
         * <p>Failure: allocation failure invalidates this extraction and returns null. Other failures
         * follow the task's exceptional completion. This operation does not submit a host draw.
         *
         * <p>Ownership: the returned pose borrows this installation's output. The consumer must join
         * the task before draw consumption and must not retain the pose across a later owner update or ended use.
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
     * <p>Execution: run() executes once at most. The caller must reject an unaccepted, unstarted task,
     * or join an accepted task on the main thread. Completion alone does not return owner access.
     * A callback must not join its own task.
     */
    public static final class GuiTask<R> implements Runnable {
        private final EntityExecutionLifecycle.GuiTask<R> task;
        private final Runnable finish;

        private GuiTask(EntityExecutionLifecycle.GuiTask<R> task, Runnable finish) {
            this.task = task;
            this.finish = finish;
        }

        @Override
        public void run() { task.run(); }

        public void reject() { task.reject(); }

        /**
         * Waits for the complete task and returns execution authority before adopting final projections.
         *
         * <p>Failure: task and cleanup failures leave the task joined; combined GUI failures preserve fatal-error priority.
         * Interruptions do not abandon the wait; the method restores the interrupt flag after the terminal handoff.
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
                        if (combined instanceof RuntimeException runtime) throw runtime;
                        if (combined instanceof Error error) throw error;
                    }
                }
            }
        }
    }

    /** Stable installation routing; this identity does not grant access to its mutable state. */
    public final Object projectionIdentity() { return modelBinding.resourceHolder(); }

    public final boolean canAdoptProjection(Object identity) {
        return lifecycle.canAdoptProjection() && identity != null && identity == modelBinding.resourceHolder()
            && canEvaluate();
    }

    public final void finishProjections(boolean finalWindow) {
        lifecycle.assertMainThread();
        if (!finalWindow)
            AnimationParallelTicker.retainForFinalProjection(this);
        if (!lifecycle.hasTask())
            expressionRuntime.adoptProjections(finalWindow);
    }

    /**
     * Starts this invocation's owner update, or updates synchronously outside an invocation.
     *
     * <p>Execution: the main-thread caller first joins prior work and prepares the owner before submission.
     * Successful submission transfers private-state access until waitForAsyncUpdate() completes.
     * Submission failure propagates without leaving a pending task.
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
        lifecycle.submit(() -> computePose(partialTicks, RenderContext.levelImmutable()));
    }

    /**
     * Joins ordinary work before returning private-state access and adopting eligible projections.
     *
     * <p>Execution: the main-thread call can block. Task completion includes the task's finally blocks.
     * Interruptions do not release the owner early; the method restores the interrupt flag after the wait.
     * A reserved GUI task requires GuiTask.join() instead.
     */
    public final void waitForAsyncUpdate() {
        try {
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

        private FailedUse(AnimatableEntity<?> owner, ResourceHolder installation,
            AnimationEvent<?> event, RuntimeException cause) {
            super("Component lifecycle failed: owner=" + owner.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(owner)) + " request="
                + installation.model.modelHash() + " target=" + installation.model.modelHash() + "/"
                + installation.model.renderTargetId() + " context=" + event.getRenderContext() + " tick="
                + event.getEntityTickCount() + " time=" + event.renderTicks, cause);
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
        return target == null ? null : assets(target).userFunctions().get(name);
    }

    static final class ResourceHolder implements Closeable {
        private final ResourceLease lease;
        public final ModelRenderTarget model;
        private AnimatedGeoModel geoModel;
        private AnimationData animations;
        private AnimationProcessor<?> processor;
        private ComponentScope components;
        private boolean componentsSampled;
        private EntityExpressionRuntime.PreparedInstallation expressions;
        private boolean ownsState = true;
        private boolean closed;
        private boolean sharedStateActivationStarted;
        private Runnable abortSharedStateActivation;
        private BiConsumer<Runnable, BooleanSupplier> activation = (publication, qualified) -> publication.run();

        ResourceHolder(ResourceLease lease) {
            this.lease = lease;
            if (!(lease.poll() instanceof AcquireResult.Ready ready)) {
                throw new IllegalArgumentException("Installation requires a Ready lease");
            }
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
            return sharedStateActivationStarted && !ownsState;
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
            try {
                if (expressions != null)
                    expressions.close();
            } finally {
                try {
                    if (ownsState) {
                        try {
                            if (processor != null)
                                processor.clearModel();
                        } finally {
                            try {
                                if (animations != null)
                                    animations.reset();
                            } finally {
                                if (components != null)
                                    components.invalidate();
                            }
                        }
                    }
                } finally {
                    lease.close();
                }
            }
        }
    }

}
