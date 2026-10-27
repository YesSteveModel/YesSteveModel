package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.work.ResourcePrioritySchedulers;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.google.gson.Gson;
import dev.semaweave.compilation.EngineMetrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Real resident preparation and JNI extraction; headless publication excludes GPU and entity installation. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@Fork(value = 1, jvmArgsAppend = "-Xmx3G")
@Threads(1)
public class ResourcePriorityBenchmark {
    @State(Scope.Thread)
    public static class Workload {
        @Param({"single_fifo_control", "split_equal", "split_normal"})
        public String mode;
        @Param({"saturated", "sameFlight", "sharedPreparation"})
        public String scenario;
        @Param({"derivedHot"})
        public String temperature;
        @Param({"12"})
        public int backgroundJobs;
        @Param({"40000000"})
        public long backgroundTokens;
        private final DemandPreparationBenchmark.Workload fixture = new DemandPreparationBenchmark.Workload();
        private final List<ResourceLease> leases = new ArrayList<>();
        private final List<CompletableFuture<ModelRenderTargetLoader.LoadResult>> loading = new ArrayList<>();
        private final Object owner = new Object();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicLong candidateCompleted = new AtomicLong();
        private ResourcePriorityProbe.Sample sample;
        private Map<String, Object> observation;

        @Setup(Level.Trial)
        public void trial() throws Exception {
            if (!Boolean.getBoolean("ysm.priority.probe"))
                throw new IllegalStateException("Use native-image-access-agent.jar=priority in the benchmark fork");
            if (!temperature.equals("cold") && !temperature.equals("derivedHot"))
                throw new IllegalArgumentException("Unknown artifact temperature: " + temperature);
            fixture.scenario = temperature;
            fixture.trial();
        }

        @Setup(Level.Invocation)
        public void invocation() throws Exception {
            fixture.schedulerFactory = () -> ResourcePrioritySchedulers.create(mode);
            fixture.invocation();
            starts.set(0);
            candidateCompleted.set(0);
            observation = null;
            sample = new ResourcePriorityProbe.Sample();
            ResourcePriorityProbe.active = sample;
        }

        long run() throws Exception {
            var backgroundStarted = new CountDownLatch(1);
            var backgroundFinished = new AtomicLong();
            var work = new ArrayList<CompletableFuture<Void>>();
            var backgroundBegin = System.nanoTime();
            for (var index = 0; index < backgroundJobs; index++) {
                var completion = new CompletableFuture<Void>();
                work.add(completion);
                fixture.scheduler.backgroundExecutor().execute(() -> {
                    ResourcePriorityProbe.backgroundStarted();
                    backgroundStarted.countDown();
                    try {
                        Blackhole.consumeCPU(backgroundTokens);
                        backgroundFinished.accumulateAndGet(System.nanoTime(), Math::max);
                        completion.complete(null);
                    } catch (Throwable failure) {
                        completion.completeExceptionally(failure);
                    } finally {
                        ResourcePriorityProbe.backgroundFinished();
                    }
                });
            }
            if (!backgroundStarted.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Background load did not start");
            var primaryTexture = scenario.equals("sharedPreparation") ? fixture.b.texture() : fixture.a.texture();
            var primary = request(primaryTexture);
            if (!scenario.equals("saturated"))
                leases.add(acquire(request(fixture.a.texture()), WorkPriority.BACKGROUND,
                    !scenario.equals("sharedPreparation")));
            var requestAt = System.nanoTime();
            var priority = mode.equals("single_fifo_control") ? WorkPriority.BACKGROUND : WorkPriority.LOCAL_PLAYER;
            var local = acquire(primary, priority, true);
            leases.add(local);
            awaitReady(List.of(local));
            var readyAt = System.nanoTime();
            var vertices = receive(local);
            var receivedAt = System.nanoTime();
            CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            awaitReady(leases);
            // A Ready future can wake this observer just before its worker exits the measured stage.
            var callbackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (sample.activeWork.get() != 0) {
                if (System.nanoTime() > callbackDeadline)
                    throw new IllegalStateException("Measured compute callbacks did not finish");
                Thread.onSpinWait();
            }
            var expectedBuilds = scenario.equals("sharedPreparation") ? 2 : 1;
            if (starts.get() != expectedBuilds || sample.producers.get() != 1 || sample.builds.get() != expectedBuilds)
                throw new IllegalStateException("Preparation sharing oracle failed: starts=" + starts + " producers="
                    + sample.producers + " builds=" + sample.builds);
            if (sample.bakes.get() != expectedBuilds * 2 || sample.peakWork.get() > 2 || sample.activeWork.get() != 0)
                throw new IllegalStateException("Native bake or fixed compute capacity oracle failed");
            if (sample.producerStarted.get() < requestAt)
                throw new IllegalStateException("The producer started before the measured local interest joined");
            var usedLocalWorker = sample.workThreads.contains("YSM Local Player Resource Worker");
            if (usedLocalWorker == mode.equals("single_fifo_control"))
                throw new IllegalStateException("The controlled scheduler lane oracle failed");
            var metrics = fixture.metrics.snapshot();
            var expressionStages = new LinkedHashMap<String, Long>();
            for (var stage : EngineMetrics.Stage.values())
                expressionStages.put(stage.name(), metrics.stageCalls(stage));
            observation = new LinkedHashMap<>(Map.ofEntries(Map.entry("mode", mode), Map.entry("scenario", scenario),
                Map.entry("temperature", temperature), Map.entry("activeCapacity", 2),
                Map.entry("physicalWorkers", mode.equals("single_fifo_control") ? 3 : 2),
                Map.entry("unusedDedicatedWorker", mode.equals("single_fifo_control")),
                Map.entry("requestToProducerStartMs", (sample.producerStarted.get() - requestAt) / 1e6),
                Map.entry("aggregateComputeMs", sample.computeNanos.get() / 1e6),
                Map.entry("completionToReadyObservationMs", (readyAt - candidateCompleted.get()) / 1e6),
                Map.entry("requestToReceiverMs", (receivedAt - requestAt) / 1e6),
                Map.entry("backgroundJobs", backgroundJobs), Map.entry("backgroundTokensPerJob", backgroundTokens),
                Map.entry("backgroundElapsedMs", (backgroundFinished.get() - backgroundBegin) / 1e6),
                Map.entry("backgroundJobsPerSecond", backgroundJobs * 1e9 / (backgroundFinished.get() - backgroundBegin)),
                Map.entry("loaderStarts", starts.get()), Map.entry("producers", sample.producers.get()),
                Map.entry("consumerBuilds", sample.builds.get()), Map.entry("nativeBakes", sample.bakes.get()),
                Map.entry("peakCompute", sample.peakWork.get()), Map.entry("expressionStages", expressionStages),
                Map.entry("workerThreads", sample.workThreads), Map.entry("producerThread", sample.producerThread),
                Map.entry("vertices", vertices), Map.entry("publication", "headless"),
                Map.entry("receiver", "native-extract; excludes entity installation")));
            return vertices;
        }

        private ResourceRequest request(String texture) {
            return fixture.resourceRequest(fixture.content.modelId(), "player", texture, ModelResourcePurpose.FULL,
                null);
        }

        private ResourceLease acquire(ResourceRequest request, WorkPriority priority, boolean observeCompletion) {
            var key = new RenderTargetKey(request.targetId(), request.requestedTexture(), request.bakeProfile(),
                request.expressionBinding(), request.purpose());
            return fixture.cache.getOrStart(request, fixture.content, key, (cancelled, dispatch) -> {
                starts.incrementAndGet();
                var source = fixture.loader.loadAsync(cancelled, dispatch, fixture.content, key, true,
                    ModelResourceFailures.none());
                loading.add(source);
                return observeCompletion ? source.whenComplete((outcome, failure) -> {
                    candidateCompleted.set(System.nanoTime());
                }) : source;
            }, (identity, selected, asked) -> true, owner, priority);
        }

        private void awaitReady(List<ResourceLease> interests) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (true) {
                fixture.cache.tick();
                var ready = true;
                for (var interest : interests) {
                    var result = interest.poll();
                    if (result instanceof AcquireResult.Failed failed)
                        throw new IllegalStateException("Real loader failed", failed.failure().cause());
                    ready &= result instanceof AcquireResult.Ready;
                }
                if (ready)
                    return;
                if (System.nanoTime() > deadline)
                    throw new IllegalStateException("Real loader exceeded 60 seconds");
                LockSupport.parkNanos(100_000);
            }
        }

        private static long receive(ResourceLease lease) {
            var target = ((AcquireResult.Ready)lease.poll()).target();
            var variant = target.playerResources().defaultVariant();
            if (variant.armModel() == null)
                throw new IllegalStateException("The measured receiver requires a FULL target");
            long vertices = 0;
            try (var output = NativeModelState.create()) {
                for (var model : List.of(variant.mainModel(), variant.armModel())) {
                    if (!output.extract(model.bakedModel(), new AnimatedGeoModel(model).getBoneAttributes()))
                        throw new IllegalStateException("Native receiver extract failed");
                    vertices += output.getTotalVertexCount();
                }
            }
            if (vertices <= 0)
                throw new IllegalStateException("Native receiver returned no vertices");
            return vertices;
        }

        @TearDown(Level.Invocation)
        public void retire() throws Exception {
            try {
                if (observation != null)
                    System.out.println("RESOURCE_PRIORITY_SAMPLE " + new Gson().toJson(observation));
                leases.forEach(ResourceLease::close);
                leases.clear();
                loading.forEach(future -> future.cancel(false));
                loading.clear();
                fixture.retireResources();
            } finally {
                ResourcePriorityProbe.active = null;
            }
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            fixture.close();
        }
    }

    @Benchmark
    public long prepare(Workload workload) throws Exception {
        return workload.run();
    }
}
