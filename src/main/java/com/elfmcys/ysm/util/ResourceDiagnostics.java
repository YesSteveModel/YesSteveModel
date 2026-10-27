package com.elfmcys.ysm.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resource observations never own the resources they describe. */
public final class ResourceDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger("ysm");
    private static final Registry REGISTRY = new Registry();
    private static volatile boolean enabled = System.getenv("YSM_DEBUG_RESOURCE") != null;

    private ResourceDiagnostics() {}

    public enum Kind {
        GEO, ANIM, TEXTURE, RENDERTARGET, GUI_ENTITY, CLIENT_LEVEL
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void track(Kind kind, Object owner, String description) {
        REGISTRY.track(kind, owner, description);
    }

    public static String untrack(Kind kind, Object owner) {
        return REGISTRY.untrack(kind, owner);
    }

    public static void log(String message, Object... arguments) {
        if (enabled) {
            LOGGER.info("[resource] " + message, arguments);
        }
    }

    public static Operation operation(String action, Object detail) {
        return enabled ? new Operation(action, detail) : Operation.DISABLED;
    }

    public static void pollCollections() {
        if (enabled) {
            REGISTRY.pollCollections();
        }
    }

    /** Explicit dumps remain available while automatic logging is disabled. */
    public static int dump(Kind kind) {
        var entries = REGISTRY.snapshot(kind);
        LOGGER.info("[resource] dump {}: {} live resources", kind, entries.size());
        for (var entry : entries) {
            LOGGER.info("[resource] {}", entry);
        }
        return entries.size();
    }

    public static final class Operation implements AutoCloseable {
        private static final Operation DISABLED = new Operation();
        private final String action;
        private final Object detail;
        private final long started;
        private boolean successful;

        private Operation() {
            action = null;
            detail = null;
            started = 0;
        }

        private Operation(String action, Object detail) {
            this.action = action;
            this.detail = detail;
            started = System.nanoTime();
            LOGGER.info("[resource] {} begin: {}", action, detail);
        }

        public void succeeded() {
            if (action != null) {
                successful = true;
            }
        }

        @Override
        public void close() {
            if (action != null && enabled) {
                LOGGER.info("[resource] {} {}: {} ({} ms)", action, successful ? "success" : "failed", detail,
                    (System.nanoTime() - started) / 1_000_000.0);
            }
        }
    }

    static final class Registry {
        private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
        private final Map<Kind, Map<IdentityReference, String>> entries = new EnumMap<>(Kind.class);

        synchronized void track(Kind kind, Object owner, String description) {
            prune();
            entries.computeIfAbsent(kind, ignored -> new HashMap<>()).put(new IdentityReference(kind, owner, collected),
                description);
        }

        synchronized String untrack(Kind kind, Object owner) {
            prune();
            var resources = entries.get(kind);
            if (resources != null) {
                return resources.remove(new IdentityReference(kind, owner, null));
            }
            return null;
        }

        synchronized List<String> snapshot(Kind kind) {
            prune();
            var snapshot = new ArrayList<String>();
            var resources = entries.get(kind);
            if (resources != null) {
                resources.forEach((reference, description) -> {
                    if (reference.get() != null) {
                        snapshot.add(description + " @" + Integer.toHexString(reference.identity));
                    }
                });
            }
            snapshot.sort(String::compareTo);
            return List.copyOf(snapshot);
        }

        synchronized void pollCollections() {
            prune();
        }

        private void prune() {
            EnumMap<Kind, Integer> reclaimed = null;
            IdentityReference reference;
            while ((reference = (IdentityReference)collected.poll()) != null) {
                var description = entries.get(reference.kind).remove(reference);
                if (description != null && enabled) {
                    if (reclaimed == null) {
                        reclaimed = new EnumMap<>(Kind.class);
                    }
                    reclaimed.merge(reference.kind, 1, Integer::sum);
                }
            }
            if (reclaimed != null) {
                reclaimed.forEach((kind, count) -> log("resource collected: kind={} count={} live={}", kind, count,
                    entries.get(kind).size()));
            }
        }
    }

    private static final class IdentityReference extends WeakReference<Object> {
        private final Kind kind;
        private final int identity;

        private IdentityReference(Kind kind, Object owner, ReferenceQueue<Object> queue) {
            super(owner, queue);
            this.kind = kind;
            identity = System.identityHashCode(owner);
        }

        @Override
        public int hashCode() {
            return identity;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            var owner = get();
            return other instanceof IdentityReference reference && owner != null && owner == reference.get();
        }
    }
}
