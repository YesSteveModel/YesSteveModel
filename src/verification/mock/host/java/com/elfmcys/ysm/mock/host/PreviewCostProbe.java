package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher;
import com.elfmcys.ysm.model.resource.client.preview.ClientPreviewGenerator;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** Forwarding observers around the real target and GL host; never produce substitute results. */
final class PreviewCostProbe {
    private static final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private static java.lang.ref.WeakReference<ClientPreviewGenerator> observed =
        new java.lang.ref.WeakReference<>(null);

    @SuppressWarnings("unchecked")
    static void install(ClientPreviewGenerator generator) throws ReflectiveOperationException {
        counters.clear();
        if (observed.get() == generator)
            return;
        var targetField = generator.getClass().getDeclaredField("managementTargets");
        targetField.setAccessible(true);
        var planner = (BiFunction<ModelContent, RemoteChunkFetcher, Object>)targetField.get(generator);
        targetField.set(generator, (BiFunction<ModelContent, RemoteChunkFetcher, Object>)(content, fetcher) -> {
            var counter = counters.computeIfAbsent(content.modelId().toString(), ignored -> new Counter());
            counter.containerId = content.representation().containerId().toString();
            counter.targetStartNanos = System.nanoTime();
            counter.targets.incrementAndGet();
            var plan = planner.apply(content, fetcher);
            counter.plan = plan.getClass().getSimpleName();
            return plan;
        });
        var hostField = generator.getClass().getDeclaredField("hostRenderer");
        hostField.setAccessible(true);
        var renderer = hostField.get(generator);
        hostField.set(generator, Proxy.newProxyInstance(hostField.getType().getClassLoader(),
            new Class<?>[] {hostField.getType()}, (proxy, method, arguments) -> {
                method.setAccessible(true);
                if (method.getDeclaringClass() == Object.class)
                    return method.invoke(renderer, arguments);
                var request = (ResourceRequest)arguments[0];
                var counter = counters.computeIfAbsent(request.modelId().toString(), ignored -> new Counter());
                counter.draws.incrementAndGet();
                var started = System.nanoTime();
                try {
                    return method.invoke(renderer, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                } finally {
                    counter.drawNanos += System.nanoTime() - started;
                    counter.drawEndNanos = System.nanoTime();
                }
            }));
        observed = new java.lang.ref.WeakReference<>(generator);
    }

    static Map<String, ?> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        counters.forEach((model, counter) -> result.put(model,
            Map.of("containerId", counter.containerId, "targetPlans", counter.targets.get(), "plan", counter.plan,
                "draws", counter.draws.get(), "hostDrawMillis", counter.drawNanos / 1_000_000.0, "targetToDrawMillis",
                (counter.drawEndNanos - counter.targetStartNanos) / 1_000_000.0)));
        return result;
    }

    private static final class Counter {
        private String containerId = "";
        private String plan = "";
        private final AtomicInteger targets = new AtomicInteger();
        private final AtomicInteger draws = new AtomicInteger();
        private long targetStartNanos;
        private long drawEndNanos;
        private long drawNanos;
    }
}
