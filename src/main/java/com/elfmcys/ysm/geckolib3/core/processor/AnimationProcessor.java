package com.elfmcys.ysm.geckolib3.core.processor;

import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.EventProgram;
import com.elfmcys.ysm.expression.aot.TransientExpressionSession;
import com.elfmcys.ysm.expression.host.ExecutionFailure;
import com.elfmcys.ysm.expression.host.EventArguments;
import com.elfmcys.ysm.expression.host.OwnerEffectSink;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.elfmcys.ysm.geckolib3.core.util.MathUtil;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.util.ThreadScheduling;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadRole;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMaps;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class AnimationProcessor<TEntity extends Entity> {
    private static final int MAX_PENDING_TRANSIENTS = 32;
    private static final ThreadPoolExecutor TRANSIENT_COMPILER =
        new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), runnable -> {
            var thread =
                new Thread(ThreadScheduling.entry(ThreadRole.TRANSIENT_COMPILER, ThreadPolicy.BELOW_NORMAL, runnable),
                    "YSM Transient Compiler");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    private final AnimatableEntity<TEntity> animatable;
    private final ReferenceArrayList<BoneTopLevelSnapshot> modelBones = new ReferenceArrayList<>();
    private Object2ReferenceMap<String, List<EventProgram>> eventHandlers = Object2ReferenceMaps.emptyMap();
    private final Int2ReferenceOpenHashMap<BoneTopLevelSnapshot> modelBonesMap = new Int2ReferenceOpenHashMap<>();
    private final ArrayDeque<BoneTopLevelSnapshot> activeModelBones = new ArrayDeque<>(); // 即使更新开销大也比链表更优

    private final SoundInstanceManager globalSoundManager = new SoundInstanceManager();
    private final RandomSource random = new XoroshiroRandomSource(RandomSupport.generateUniqueSeed());
    private final ConcurrentLinkedQueue<TransientTask> readyTransients = new ConcurrentLinkedQueue<>();
    private final AtomicInteger ownedTransientRequests = new AtomicInteger();
    private final AtomicInteger pendingTransientCompiles = new AtomicInteger();
    private final AtomicLong transientEpoch = new AtomicLong();
    private final AtomicLong watchEpoch = new AtomicLong();
    private final ConcurrentLinkedQueue<WatchCompilation> readyWatches = new ConcurrentLinkedQueue<>();
    private final Map<String, WatchState> watches = new HashMap<>();

    private float lastTrimTime = 0;
    @Nullable
    private DebugInfo debugInfo;

    public AnimationProcessor(AnimatableEntity<TEntity> animatable) {
        this.animatable = animatable;
    }

    @SuppressWarnings("unchecked")
    public void tickAnimation(AnimationEvent<AnimatableEntity<TEntity>> event, boolean shouldTick,
        boolean executeEvents, EntityExpressionRuntime.Evaluation evaluation) {
        var renderTicks = event.renderTicks;

        if (renderTicks - lastTrimTime >= 1200) {
            globalSoundManager.trim();
            lastTrimTime = renderTicks;
        } else if (lastTrimTime > renderTicks) {
            lastTrimTime = renderTicks;
        }

        AnimationData manager = this.animatable.getAnimationData();
        for (IAnimationController<AnimatableEntity<TEntity>> controller : manager.getAnimationControllers()) {
            if (shouldTick) {
                // 将当前控制器设置为动画测试事件
                // 处理动画并向点队列添加新值
                controller.process(event, evaluation, executeEvents);
            }
            // 解决一个历史遗留问题而保留的动画混合
            @Deprecated
            boolean blendRotation = controller.blendRotation();
            // 遍历每个骨骼，并对属性进行插值计算
            controller.visitBoneAnimationQueues(boneAnimation -> {
                BoneTopLevelSnapshot snapshot = boneAnimation.getSnapshot();
                if (!snapshot.hasAnimation) {
                    snapshot.hasAnimation = true;
                    activeModelBones.add(snapshot);
                }

                boneAnimation.pollRotationPoint(evaluation).ifPresent(rot -> {
                    @Deprecated
                    var pointData = snapshot.cachedPointData;
                    if (!snapshot.isCurrentlyRunningRotationAnimation) {
                        snapshot.isCurrentlyRunningRotationAnimation = true;
                        snapshot.rotation.set(0, 0, 0);
                    }
                    snapshot.lastRotationUpdateTime = renderTicks;
                    if (blendRotation) {
                        // 此处假设旧版 blendRotation 只有高并行动画在用，并且该类型动画永不结束，所以尾过渡进度永远为 0。实际上也理应如此
                        pointData.add(rot);
                        snapshot.rotation.set(pointData);
                    } else {
                        rot.applyRotation(snapshot.rotation, boneAnimation.getSnapshot().bone.getInitialRotation());
                        pointData.set(snapshot.rotation);
                    }
                });

                boneAnimation.pollPositionPoint(evaluation).ifPresent(position -> {
                    if (!snapshot.isCurrentlyRunningPositionAnimation) {
                        snapshot.isCurrentlyRunningPositionAnimation = true;
                        snapshot.position.set(0, 0, 0);
                    }
                    snapshot.lastPositionUpdateTime = renderTicks;
                    position.apply(snapshot.position);
                });

                boneAnimation.pollScalePoint(evaluation).ifPresent(scale -> {
                    if (!snapshot.isCurrentlyRunningScaleAnimation) {
                        snapshot.isCurrentlyRunningScaleAnimation = true;
                        snapshot.scale.set(1, 1, 1);
                    }
                    snapshot.lastScaleUpdateTime = renderTicks;
                    scale.apply(snapshot.scale);
                });
            });
        }

        // 追踪哪些骨骼应用了动画，并最终将没有动画的骨骼过渡到默认值
        var activeBoneIterator = activeModelBones.iterator();
        while (activeBoneIterator.hasNext()) {
            var snapshot = activeBoneIterator.next();
            var active = false;

            // 处理旋转尾过渡
            if (snapshot.isCurrentlyRunningRotationAnimation) {
                active = true;
                snapshot.isCurrentlyRunningRotationAnimation = false;
                snapshot.rotationOffset = null;
            } else {
                if (snapshot.rotationOffset == null) {
                    snapshot.rotationOffset = new Vector3f(snapshot.rotation);
                }
                var progress = (renderTicks - snapshot.lastRotationUpdateTime) / manager.getResetSpeed();
                if (progress < 1f) {
                    active = true;
                    MathUtil.lerpRotationValues(progress, snapshot.rotationOffset, MathUtil.ZERO,
                        snapshot.bone.getInitialRotation(), snapshot.rotation);
                } else {
                    snapshot.rotation.set(MathUtil.ZERO);
                }
            }

            // 处理位移尾过渡
            if (snapshot.isCurrentlyRunningPositionAnimation) {
                active = true;
                snapshot.isCurrentlyRunningPositionAnimation = false;
                snapshot.positionOffset = null;
            } else {
                if (snapshot.positionOffset == null) {
                    snapshot.positionOffset = new Vector3f(snapshot.position);
                }
                var progress = (renderTicks - snapshot.lastPositionUpdateTime) / manager.getResetSpeed();
                if (progress < 1f) {
                    active = true;
                    MathUtil.lerpValues(progress, snapshot.positionOffset, MathUtil.ZERO, snapshot.position);
                } else {
                    snapshot.position.set(0, 0, 0);
                }
            }

            // 处理缩放尾过渡，终点为 1,1,1
            if (snapshot.isCurrentlyRunningScaleAnimation) {
                active = true;
                snapshot.isCurrentlyRunningScaleAnimation = false;
                snapshot.scaleOffset = null;
            } else {
                if (snapshot.scaleOffset == null) {
                    snapshot.scaleOffset = new Vector3f(snapshot.scale);
                }
                var progress = (renderTicks - snapshot.lastScaleUpdateTime) / manager.getResetSpeed();
                if (progress < 1f) {
                    active = true;
                    MathUtil.lerpValues(progress, snapshot.scaleOffset, MathUtil.ONE, snapshot.scale);
                } else {
                    snapshot.scale.set(1, 1, 1);
                }
            }

            snapshot.commit();
            if (!active) {
                snapshot.hasAnimation = false;
                activeBoneIterator.remove();
            }
        }

    }

    public EntityExpressionRuntime.InvocationInputs invocationInputs(AnimationEvent<AnimatableEntity<TEntity>> event) {
        var production = new MinecraftInvocationInputs(animatable, event, random, globalSoundManager, null, null);
        return new EntityExpressionRuntime.InvocationInputs(production, new OwnerEffectSink(production),
            () -> animatable.getEntity().isRemoved());
    }

    public void dispatchSync(EventArguments arguments) {
        if (animatable.getExpressionRuntime().module().isEmpty()) {
            throw new IllegalStateException("sync ingress has no current expression target");
        }
        animatable.getExpressionRuntime().action(ingressInvocationInputs(), evaluation -> {
            evaluation.dispatchSync(arguments);
            return null;
        });
    }

    public boolean readConfigBoolean(long expectedGeneration, ConfigActionRoute route) {
        return animatable.getExpressionRuntime().readConfigBoolean(ingressInvocationInputs(), expectedGeneration,
            route);
    }

    public float readConfigFloat(long expectedGeneration, ConfigActionRoute route) {
        return animatable.getExpressionRuntime().readConfigFloat(ingressInvocationInputs(), expectedGeneration, route);
    }

    public void writeConfigBoolean(long expectedGeneration, ConfigActionRoute route, boolean value) {
        animatable.getExpressionRuntime().writeConfigBoolean(ingressInvocationInputs(), expectedGeneration, route,
            value);
    }

    public void writeConfigFloat(long expectedGeneration, ConfigActionRoute route, float value) {
        animatable.getExpressionRuntime().writeConfigFloat(ingressInvocationInputs(), expectedGeneration, route, value);
    }

    public void executeConfigLabel(long expectedGeneration, ConfigActionRoute route, String labelName, int labelIndex) {
        animatable.getExpressionRuntime().executeConfigLabel(ingressInvocationInputs(), expectedGeneration, route,
            labelName, labelIndex);
    }

    private EntityExpressionRuntime.InvocationInputs ingressInvocationInputs() {
        return invocationInputs(animatable.createIngressAnimationEvent());
    }

    @Nullable
    public BoneView getBone(int boneName) {
        BoneTopLevelSnapshot bone = modelBonesMap.get(boneName);
        return bone != null ? bone.bone : null;
    }

    public void clearModel() {
        this.modelBonesMap.clear();
        this.activeModelBones.clear();
        this.modelBones.clear();
        this.eventHandlers = Object2ReferenceMaps.emptyMap();
        closeTransientWork();

    }

    public void loadModel(AnimatedGeoModel model, Object2ReferenceMap<String, List<EventProgram>> eventHandlers,
        AnimationData animations, java.util.function.Function<String,
            com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData> definitions) {
        clearModel();
        if (!model.getSortedBones().isEmpty()) {
            this.modelBones.ensureCapacity(model.getSortedBones().size());
            this.modelBones.add(null);
            var rootName = model.getSortedBones().get(0).getPooledName();
            Int2ReferenceMaps.fastForEach(model.getBoneMap(), entry -> {
                BoneTopLevelSnapshot snapshot = new BoneTopLevelSnapshot(entry.getValue());
                this.modelBonesMap.put(entry.getValue().getPooledName(), snapshot);
                if (entry.getIntKey() == rootName) {
                    this.modelBones.set(0, snapshot);
                } else {
                    this.modelBones.add(snapshot);
                }
            });
        }
        this.eventHandlers = eventHandlers;
        for (var controller : animations.getAnimationControllers()) {
            controller.updateModel(this.modelBones, eventHandlers, definitions);
        }
    }

    public boolean isModelEmpty() {
        return modelBones.isEmpty();
    }

    public Runnable prepareResourceBinding(Object2ReferenceMap<String, List<EventProgram>> eventHandlers,
        AnimationData animations, java.util.function.Function<String,
            com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData> definitions) {
        var bindings = new java.util.ArrayList<Runnable>(animations.getAnimationControllers().size());
        for (var controller : animations.getAnimationControllers())
            bindings.add(controller.prepareResourceBinding(eventHandlers, definitions));
        return () -> {
            this.eventHandlers = eventHandlers;
            for (var binding : bindings)
                binding.run();
        };
    }

    public void enqueueTransient(String source, TransientExpressionSession.Privilege privilege, boolean beforeAnimation,
        @Nullable Consumer<String> resultConsumer) {
        var runtime = animatable.getExpressionRuntime();
        var module = runtime.module().orElse(null);
        var generation = runtime.generation();
        if (module == null || generation <= 0) {
            acceptResult(resultConsumer, "Error: no current expression target");
            return;
        }
        if (ownedTransientRequests.incrementAndGet() > MAX_PENDING_TRANSIENTS) {
            ownedTransientRequests.decrementAndGet();
            acceptResult(resultConsumer, "Error: transient request queue is full");
            return;
        }
        var pending = pendingTransientCompiles.incrementAndGet();
        if (pending > MAX_PENDING_TRANSIENTS) {
            pendingTransientCompiles.decrementAndGet();
            ownedTransientRequests.decrementAndGet();
            acceptResult(resultConsumer, "Error: transient compile queue is full");
            return;
        }
        var epoch = transientEpoch.get();
        try {
            CompletableFuture.runAsync(() -> {
                TransientExpressionSession session = null;
                Throwable failure = null;
                try {
                    session = runtime.compileTransient(generation, source, privilege,
                        TransientExpressionSession.Purpose.ONE_SHOT,
                        "transient:" + privilege.name().toLowerCase(java.util.Locale.ROOT),
                        () -> epoch != transientEpoch.get() || animatable.getEntity().isRemoved());
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    pendingTransientCompiles.decrementAndGet();
                }
                if (epoch != transientEpoch.get()) {
                    if (session != null)
                        session.close();
                    ownedTransientRequests.decrementAndGet();
                    return;
                }
                var ready = new TransientTask(session, failure, beforeAnimation, resultConsumer);
                readyTransients.add(ready);
                if (epoch != transientEpoch.get() && readyTransients.remove(ready)) {
                    if (session != null)
                        session.close();
                    ownedTransientRequests.decrementAndGet();
                }
            }, TRANSIENT_COMPILER);
        } catch (RuntimeException rejected) {
            pendingTransientCompiles.decrementAndGet();
            ownedTransientRequests.decrementAndGet();
            acceptResult(resultConsumer, "Error: transient compiler is busy");
        }
    }

    public void processTransientTasks(EntityExpressionRuntime.Evaluation evaluation, boolean beforeAnimation) {
        for (var iterator = readyTransients.iterator(); iterator.hasNext();) {
            var task = iterator.next();
            if (task.beforeAnimation() != beforeAnimation)
                continue;
            iterator.remove();
            try {
                if (task.failure() != null) {
                    acceptResult(task.resultConsumer(), error(task.failure()));
                    continue;
                }
                try (var session = task.session()) {
                    var result = evaluation.executeTransient(session);
                    acceptResult(task.resultConsumer(), display(result, session.resultType()));
                } catch (Throwable failure) {
                    acceptResult(task.resultConsumer(), error(failure));
                    rethrowSevere(failure);
                }
            } finally {
                ownedTransientRequests.decrementAndGet();
            }
        }
        processWatches(evaluation, beforeAnimation);
    }

    public void setDebugInfo(@Nullable DebugInfo value) {
        if (debugInfo != value) {
            closeWatches();
            debugInfo = value;
        }
    }

    private void processWatches(EntityExpressionRuntime.Evaluation evaluation, boolean beforeAnimation) {
        acceptWatchCompilations();
        var owner = debugInfo;
        if (owner == null)
            return;
        var entries = owner.entries();
        var retained = new HashSet<String>();
        for (var entry : entries) {
            retained.add(entry.name());
            var state = watches.get(entry.name());
            if (state != null && state.revision != entry.revision()) {
                state.close();
                watches.remove(entry.name());
                state = null;
            }
            if (state == null) {
                scheduleWatch(entry);
                continue;
            }
            if (state.session == null || state.failed
                || (entry.phase() == DebugInfo.Phase.PRE_ANIMATION) != beforeAnimation)
                continue;
            try {
                var result = evaluation.executeTransient(state.session);
                owner.result(entry.name(), entry.revision(), display(result, state.session.resultType()));
            } catch (Throwable failure) {
                state.failed = true;
                state.closeSession();
                owner.result(entry.name(), entry.revision(), error(failure));
                rethrowSevere(failure);
            }
        }
        var iterator = watches.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!retained.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    private void scheduleWatch(DebugInfo.Entry entry) {
        var runtime = animatable.getExpressionRuntime();
        var module = runtime.module().orElse(null);
        var generation = runtime.generation();
        if (module == null || generation <= 0 || pendingTransientCompiles.incrementAndGet() > MAX_PENDING_TRANSIENTS) {
            if (module != null && generation > 0)
                pendingTransientCompiles.decrementAndGet();
            var state = new WatchState(entry.revision());
            state.failed = true;
            watches.put(entry.name(), state);
            if (debugInfo != null)
                debugInfo.result(entry.name(), entry.revision(), "Error: transient compile queue is full");
            return;
        }
        var state = new WatchState(entry.revision());
        watches.put(entry.name(), state);
        var epoch = transientEpoch.get();
        var expectedWatchEpoch = watchEpoch.get();
        try {
            CompletableFuture.runAsync(() -> {
                TransientExpressionSession session = null;
                Throwable failure = null;
                try {
                    session = runtime.compileTransient(generation, entry.source(),
                        TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                        "transient:local-watch:" + entry.name(), () -> epoch != transientEpoch.get()
                            || expectedWatchEpoch != watchEpoch.get() || animatable.getEntity().isRemoved());
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    pendingTransientCompiles.decrementAndGet();
                }
                if (epoch != transientEpoch.get() || expectedWatchEpoch != watchEpoch.get()) {
                    if (session != null)
                        session.close();
                    return;
                }
                var ready = new WatchCompilation(entry.name(), entry.revision(), session, failure);
                readyWatches.add(ready);
                if ((epoch != transientEpoch.get() || expectedWatchEpoch != watchEpoch.get())
                    && readyWatches.remove(ready) && session != null) {
                    session.close();
                }
            }, TRANSIENT_COMPILER);
        } catch (RuntimeException rejected) {
            pendingTransientCompiles.decrementAndGet();
            state.failed = true;
            if (debugInfo != null)
                debugInfo.result(entry.name(), entry.revision(), "Error: transient compiler is busy");
        }
    }

    private static void rethrowSevere(Throwable failure) {
        if (failure instanceof ExecutionFailure expression
            && expression.reason() == ExecutionFailure.Reason.NON_NULL_RETURN) {
            throw expression;
        }
        if (failure instanceof VirtualMachineError fatal)
            throw fatal;
        if (failure instanceof ThreadDeath death)
            throw death;
    }

    private void acceptWatchCompilations() {
        WatchCompilation result;
        while ((result = readyWatches.poll()) != null) {
            var state = watches.get(result.name());
            if (state == null || state.revision != result.revision()) {
                if (result.session() != null)
                    result.session().close();
                continue;
            }
            if (result.failure() != null) {
                state.failed = true;
                if (debugInfo != null)
                    debugInfo.result(result.name(), result.revision(), error(result.failure()));
            } else {
                state.session = result.session();
            }
        }
    }

    private void closeTransientWork() {
        transientEpoch.incrementAndGet();
        TransientTask task;
        while ((task = readyTransients.poll()) != null) {
            if (task.session() != null)
                task.session().close();
            try {
                acceptResult(task.resultConsumer(), "Error: transient request cancelled");
            } finally {
                ownedTransientRequests.decrementAndGet();
            }
        }
        closeWatches();
        WatchCompilation compiled;
        while ((compiled = readyWatches.poll()) != null) {
            if (compiled.session() != null)
                compiled.session().close();
        }
    }

    private void closeWatches() {
        watchEpoch.incrementAndGet();
        watches.values().forEach(WatchState::close);
        watches.clear();
    }

    private static String display(Object value, dev.semaweave.ValueType type) {
        String result;
        if (value == dev.semaweave.ExpressionValues.UNSET)
            result = "UNSET";
        else if (type == dev.semaweave.BuiltinType.STRING)
            result = "'" + (String)value + "'";
        else if (type == dev.semaweave.BuiltinType.F32)
            result = Float.toString((Float)value);
        else if (type == dev.semaweave.BuiltinType.BOOL)
            result = Boolean.toString((Boolean)value);
        else
            result = type.stableId();
        return bounded(result);
    }

    private static String error(Throwable failure) {
        var message = failure.getMessage();
        return bounded("Error: " + (message == null ? failure.getClass().getSimpleName() : message));
    }

    private static String bounded(String value) {
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    private static void acceptResult(@Nullable Consumer<String> consumer, String result) {
        if (consumer != null)
            consumer.accept(result);
    }

    private record TransientTask(@Nullable TransientExpressionSession session, @Nullable Throwable failure,
        boolean beforeAnimation, @Nullable Consumer<String> resultConsumer) {
    }

    private record WatchCompilation(String name, long revision, @Nullable TransientExpressionSession session,
        @Nullable Throwable failure) {
    }

    private static final class WatchState implements AutoCloseable {
        private final long revision;
        @Nullable
        private TransientExpressionSession session;
        private boolean failed;

        private WatchState(long revision) {
            this.revision = revision;
        }

        private void closeSession() {
            if (session != null) {
                session.close();
                session = null;
            }
        }

        @Override
        public void close() {
            closeSession();
        }
    }
}
