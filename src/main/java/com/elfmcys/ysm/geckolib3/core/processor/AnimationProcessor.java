package com.elfmcys.ysm.geckolib3.core.processor;

import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.EventProgram;
import com.elfmcys.ysm.expression.aot.TransientExpressionSession;
import com.elfmcys.ysm.expression.host.ExecutionFailure;
import com.elfmcys.ysm.expression.host.EventArguments;
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
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    public SoundInstanceManager globalSoundManager() { return globalSoundManager; }

    public RandomSource randomSource() { return random; }

    private final SoundInstanceManager globalSoundManager = new SoundInstanceManager();
    private final RandomSource random = new XoroshiroRandomSource(RandomSupport.generateUniqueSeed());
    private final ArrayDeque<TransientTask> readyTransients = new ArrayDeque<>();
    private final AtomicInteger ownedTransientRequests = new AtomicInteger();
    private final AtomicInteger pendingTransientCompiles = new AtomicInteger();
    private CompileInbox<TransientTask> transientInbox = new CompileInbox<>();
    private CompileInbox<WatchCompilation> watchInbox = new CompileInbox<>();
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
        return new EntityExpressionRuntime.InvocationInputs(production, com.elfmcys.ysm.expression.host.EffectSink.direct(),
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
            for (var controller : animations.getAnimationControllers())
                controller.invalidateAnimationSource();
        };
    }

    public void enqueueTransient(String source, TransientExpressionSession.Privilege privilege, boolean beforeAnimation,
        @Nullable Consumer<String> resultConsumer) {
        var runtime = animatable.getExpressionRuntime();
        if (runtime.module().isEmpty()) {
            acceptResult(resultConsumer, "Error: no current expression target");
            return;
        }
        if (ownedTransientRequests.incrementAndGet() > MAX_PENDING_TRANSIENTS) {
            ownedTransientRequests.decrementAndGet();
            acceptResult(resultConsumer, "Error: transient request queue is full");
            return;
        }
        if (pendingTransientCompiles.incrementAndGet() > MAX_PENDING_TRANSIENTS) {
            pendingTransientCompiles.decrementAndGet();
            ownedTransientRequests.decrementAndGet();
            acceptResult(resultConsumer, "Error: transient compile queue is full");
            return;
        }
        EntityExpressionRuntime.TransientCompilation request;
        try {
            request = runtime.prepareTransient(runtime.generation(), source, privilege,
                TransientExpressionSession.Purpose.ONE_SHOT,
                "transient:" + privilege.name().toLowerCase(java.util.Locale.ROOT));
        } catch (RuntimeException | Error failure) {
            pendingTransientCompiles.decrementAndGet();
            ownedTransientRequests.decrementAndGet();
            throw failure;
        }
        var inbox = transientInbox;
        var pending = pendingTransientCompiles;
        var owned = ownedTransientRequests;
        try {
            TRANSIENT_COMPILER.execute(() -> compileTransient(request, inbox, beforeAnimation, resultConsumer,
                pending, owned));
        } catch (RuntimeException rejected) {
            request.close();
            pending.decrementAndGet();
            owned.decrementAndGet();
            acceptResult(resultConsumer, "Error: transient compiler is busy");
        }
    }

    private static void compileTransient(EntityExpressionRuntime.TransientCompilation request,
        CompileInbox<TransientTask> inbox, boolean before, Consumer<String> consumer,
        AtomicInteger pending, AtomicInteger owned) {
        TransientExpressionSession session = null;
        Throwable failure = null;
        try {
            try {
                session = request.compile(inbox::isClosed);
            } catch (Throwable error) {
                failure = error;
            }
            if (!inbox.publish(new TransientTask(request, session, failure, before, consumer))) {
                try {
                    if (session != null)
                        session.close();
                } finally {
                    owned.decrementAndGet();
                }
            }
        } finally {
            pending.decrementAndGet();
        }
    }

    /** The main-thread preparation window is the only place that attaches instance access. */
    public void prepareTransientWork() {
        var runtime = animatable.getExpressionRuntime();
        for (var result : transientInbox.drain()) {
            var task = result;
            if (result.session() != null) {
                try {
                    runtime.attachTransient(result.request(), result.session());
                } catch (Throwable failure) {
                    try {
                        result.session().close();
                    } catch (Throwable cleanup) {
                        if (cleanup != failure) failure.addSuppressed(cleanup);
                    }
                    task = new TransientTask(result.request(), null, failure, result.beforeAnimation(),
                        result.resultConsumer());
                }
            }
            readyTransients.addLast(task);
        }
        var owner = debugInfo;
        var retained = new HashSet<String>();
        if (owner != null) {
            for (var entry : owner.entries()) {
                retained.add(entry.name());
                var state = watches.get(entry.name());
                if (state != null && state.entry.revision() != entry.revision()) {
                    state.close();
                    watches.remove(entry.name());
                    state = null;
                }
                if (state == null)
                    scheduleWatch(entry);
            }
        }
        for (var iterator = watches.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            if (!retained.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
        acceptWatchCompilations();
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
        var owner = debugInfo;
        if (owner == null)
            return;
        for (var state : watches.values()) {
            var entry = state.entry;
            if (state.session == null || state.failed
                || (entry.phase() == DebugInfo.Phase.PRE_ANIMATION) != beforeAnimation)
                continue;
            try {
                var result = evaluation.executeTransient(state.session);
                owner.result(entry.name(), entry.revision(), display(result, state.session.resultType()));
            } catch (Throwable failure) {
                state.failed = true;
                try {
                    state.closeSession();
                } catch (Throwable cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                owner.result(entry.name(), entry.revision(), error(failure));
                rethrowSevere(failure);
            }
        }
    }

    private void scheduleWatch(DebugInfo.Entry entry) {
        var runtime = animatable.getExpressionRuntime();
        var state = new WatchState(entry);
        watches.put(entry.name(), state);
        if (pendingTransientCompiles.incrementAndGet() > MAX_PENDING_TRANSIENTS) {
            pendingTransientCompiles.decrementAndGet();
            state.failed = true;
            if (debugInfo != null)
                debugInfo.result(entry.name(), entry.revision(), "Error: transient compile queue is full");
            return;
        }
        EntityExpressionRuntime.TransientCompilation request;
        try {
            request = runtime.prepareTransient(runtime.generation(), entry.source(),
                TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                "transient:local-watch:" + entry.name());
        } catch (RuntimeException | Error failure) {
            pendingTransientCompiles.decrementAndGet();
            throw failure;
        }
        var inbox = watchInbox;
        var pending = pendingTransientCompiles;
        try {
            TRANSIENT_COMPILER.execute(() -> compileWatch(request, inbox, entry, pending));
        } catch (RuntimeException rejected) {
            request.close();
            pending.decrementAndGet();
            state.failed = true;
            if (debugInfo != null)
                debugInfo.result(entry.name(), entry.revision(), "Error: transient compiler is busy");
        }
    }

    private static void compileWatch(EntityExpressionRuntime.TransientCompilation request,
        CompileInbox<WatchCompilation> inbox, DebugInfo.Entry entry, AtomicInteger pending) {
        TransientExpressionSession session = null;
        Throwable failure = null;
        try {
            try {
                session = request.compile(inbox::isClosed);
            } catch (Throwable error) {
                failure = error;
            }
            if (!inbox.publish(new WatchCompilation(entry.name(), entry.revision(), request, session, failure))
                && session != null)
                session.close();
        } finally {
            pending.decrementAndGet();
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
        Throwable terminalFailure = null;
        for (var result : watchInbox.drain()) {
            var state = watches.get(result.name());
            try {
                if (state == null || state.entry.revision() != result.revision()) {
                    result.close();
                    continue;
                }
                if (result.failure() != null)
                    throw result.failure();
                animatable.getExpressionRuntime().attachTransient(result.request(), result.session());
                state.session = result.session();
            } catch (Throwable failure) {
                try {
                    result.close();
                } catch (Throwable cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                if (state != null) state.failed = true;
                if (debugInfo != null)
                    debugInfo.result(result.name(), result.revision(), error(failure));
                try {
                    rethrowSevere(failure);
                } catch (RuntimeException | Error severe) {
                    if (terminalFailure == null) terminalFailure = severe;
                    else if (terminalFailure != severe) terminalFailure.addSuppressed(severe);
                }
            }
        }
        if (terminalFailure instanceof RuntimeException error) throw error;
        if (terminalFailure instanceof Error error) throw error;
    }

    private void closeTransientWork() {
        var previous = transientInbox;
        transientInbox = new CompileInbox<>();
        var pending = previous.close();
        readyTransients.addAll(pending);
        Throwable failure = null;
        while (!readyTransients.isEmpty()) {
            var task = readyTransients.removeFirst();
            try {
                task.close();
                acceptResult(task.resultConsumer(), "Error: transient request cancelled");
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            } finally {
                ownedTransientRequests.decrementAndGet();
            }
        }
        try {
            closeWatches();
        } catch (RuntimeException | Error next) {
            if (failure == null) failure = next;
            else if (failure != next) failure.addSuppressed(next);
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private void closeWatches() {
        var previous = watchInbox;
        watchInbox = new CompileInbox<>();
        Throwable failure = null;
        var resources = new ArrayList<AutoCloseable>(previous.close());
        resources.addAll(watches.values());
        watches.clear();
        for (var resource : resources) {
            try {
                resource.close();
            } catch (Exception | LinkageError next) {
                if (failure == null) failure = next;
                else if (failure != next) failure.addSuppressed(next);
            }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Failed to close watch programs", failure);
    }

    /** This lock transfers only independently compiled programs, never entity execution state. */
    private static final class CompileInbox<T> {
        private final ArrayDeque<T> ready = new ArrayDeque<>();
        private boolean closed;
        synchronized boolean isClosed() { return closed; }
        synchronized boolean publish(T value) {
            if (closed) return false;
            ready.addLast(value);
            return true;
        }
        synchronized List<T> drain() {
            var result = List.copyOf(ready);
            ready.clear();
            return result;
        }
        synchronized List<T> close() {
            closed = true;
            return drain();
        }
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

    private record TransientTask(EntityExpressionRuntime.TransientCompilation request,
        @Nullable TransientExpressionSession session, @Nullable Throwable failure,
        boolean beforeAnimation, @Nullable Consumer<String> resultConsumer) implements AutoCloseable {
        @Override public void close() { if (session != null) session.close(); }
    }

    private record WatchCompilation(String name, long revision, EntityExpressionRuntime.TransientCompilation request,
        @Nullable TransientExpressionSession session, @Nullable Throwable failure) implements AutoCloseable {
        @Override public void close() { if (session != null) session.close(); }
    }

    private static final class WatchState implements AutoCloseable {
        private final DebugInfo.Entry entry;
        @Nullable
        private TransientExpressionSession session;
        private boolean failed;

        private WatchState(DebugInfo.Entry entry) {
            this.entry = entry;
        }

        private void closeSession() {
            if (session != null) {
                var previous = session;
                session = null;
                previous.close();
            }
        }

        @Override
        public void close() {
            closeSession();
        }
    }
}
