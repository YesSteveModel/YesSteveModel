package com.elfmcys.ysm.model.resource.client.failure;

import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Delays and aggregates only failures confirmed by an active entity binding. */
public final class ModelFailureNotificationAggregator implements AutoCloseable {
    private static final long DELAY_MILLIS = 1500;

    private final ScheduledExecutorService scheduler;
    private final Map<ModelFileIdentity, Outcome> pending = new HashMap<>();
    private final Set<EmissionKey> emitted = new HashSet<>();
    private ScheduledFuture<?> scheduled;
    private boolean closed;

    public ModelFailureNotificationAggregator(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    public synchronized void report(ModelContent version, Outcome outcome) {
        var identity = version.representation().identity();
        if (closed || emitted.contains(new EmissionKey(identity, outcome))) {
            return;
        }
        pending.merge(identity, outcome, (left, right) -> left.ordinal() >= right.ordinal() ? left : right);
        if (scheduled == null) {
            scheduled = scheduler.schedule(this::flush, DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void recovered(ModelContent version) {
        var identity = version.representation().identity();
        pending.remove(identity);
        emitted.removeIf(key -> key.identity().equals(identity));
    }

    private void flush() {
        final Map<ModelFileIdentity, Outcome> batch;
        synchronized (this) {
            if (closed) {
                return;
            }
            batch = new HashMap<>(pending);
            pending.clear();
            scheduled = null;
            batch.forEach((identity, outcome) -> emitted.add(new EmissionKey(identity, outcome)));
        }
        if (batch.isEmpty()) {
            return;
        }
        var worst = batch.values().stream().max(Comparator.comparingInt(Enum::ordinal)).orElseThrow();
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.sendSystemMessage(Component.translatable(worst.translationKey(), batch.size()));
            }
        });
    }

    @Override
    public synchronized void close() {
        closed = true;
        pending.clear();
        emitted.clear();
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }

    public enum Outcome {
        PARTIAL("message.yes_steve_model.model.failure.partial"),
        FALLBACK("message.yes_steve_model.model.failure.fallback"),
        STOPPED("message.yes_steve_model.model.failure.stopped");

        private final String translationKey;

        Outcome(String translationKey) {
            this.translationKey = translationKey;
        }

        String translationKey() {
            return translationKey;
        }
    }

    private record EmissionKey(ModelFileIdentity identity, Outcome outcome) {
    }
}
