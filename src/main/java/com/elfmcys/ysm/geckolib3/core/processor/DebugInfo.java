package com.elfmcys.ysm.geckolib3.core.processor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** User-visible watch definitions; compiled leases remain entity-runtime owned. */
public final class DebugInfo {
    private static final int MAX_WATCHES = 64;
    private final LinkedHashMap<String, Entry> items = new LinkedHashMap<>();
    private long revision;

    public synchronized void add(Phase phase, String name, String source) {
        Objects.requireNonNull(phase, "phase");
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("watch name must not be blank");
        }
        if (!items.containsKey(name) && items.size() == MAX_WATCHES) {
            throw new IllegalStateException("watch count exceeds " + MAX_WATCHES);
        }
        items.put(name, new Entry(name, Objects.requireNonNull(source, "source"), phase, ++revision, "Compiling..."));
    }

    public synchronized void remove(String name) {
        if (items.remove(name) != null) {
            revision++;
        }
    }

    public synchronized void clear() {
        if (!items.isEmpty()) {
            items.clear();
            revision++;
        }
    }

    synchronized List<Entry> entries() {
        return List.copyOf(new ArrayList<>(items.values()));
    }

    synchronized void result(String name, long itemRevision, String result) {
        var current = items.get(name);
        if (current != null && current.revision() == itemRevision) {
            items.put(name, new Entry(current.name(), current.source(), current.phase(), current.revision(),
                Objects.requireNonNull(result, "result")));
        }
    }

    public synchronized void enumerate(BiConsumer<String, String> enumerator) {
        items.values().forEach(item -> enumerator.accept(item.name(), item.result()));
    }

    record Entry(String name, String source, Phase phase, long revision, String result) {
    }

    public enum Phase {
        PRE_ANIMATION, POST_ANIMATION
    }
}
