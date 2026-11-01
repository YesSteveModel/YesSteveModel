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

    /** Retirement is permanent; reload/reset only end the current use. */
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
        return stateTracker;
    }

    public float getSeekTime() {
        return seekTime;
    }

    public final AnimationData getAnimationData() {
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

    public final TEntity getEntity() {
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
                    next.abortSharedStateActivation = lifecycle::endUse;
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
        var current = modelBinding.resourceHolder();
        return current == null || lifecycle.isEndingUse() ? null : current.geoModel;
    }

    public final boolean hasInstalledModel() {
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
        this.codedAnimationStates.put(controllerName, state);
    }

    public AnimationState getCodedAnimationStates(String controllerName) {
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
        return !lifecycle.isEndingUse() && modelBinding.matches(request, full);
    }

    @Override
    public final boolean hasInstallation() {
        return hasInstalledModel();
    }

    public final boolean matchesSelection(com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Target target) {
        return !lifecycle.isEndingUse() && modelBinding.matchesSelection(target);
    }

    @Override
    public final Throwable failure(ResourceRequest request) {
        return modelBinding.failure(request);
    }

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

    public final boolean hasPendingUpdate() { return lifecycle.hasTask(); }

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
