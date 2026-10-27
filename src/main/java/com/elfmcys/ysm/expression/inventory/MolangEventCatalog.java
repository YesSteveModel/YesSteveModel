package com.elfmcys.ysm.expression.inventory;

import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import dev.semaweave.ProgramEntrySignature;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.ReadOnlyBytes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Closed authority for event classification and invocation policy. */
public final class MolangEventCatalog {
    public static final int SYNC_MAX_ARGUMENTS = 16;
    public static final int DEFER_MAX_ARGUMENTS = 255;

    private static final String DOMAIN = "ysm:event-catalog";
    private static final Pattern CODED_CONTROLLER = Pattern.compile("[a-z0-9_]+_ctrl_[a-z0-9_]+");
    private static final MolangEventCatalog PRODUCTION = new MolangEventCatalog(List.of(
        route("player_init", ProgramRole.EVENT_PLAYER_INIT, "NONE", 0, HandlerSelection.ALL, "DECLARED", "TYPED_VOID",
            "LOGICAL_BATCH", "event:player_init"),
        route("player_update", ProgramRole.EVENT_PLAYER_UPDATE, "BOOL", 1, HandlerSelection.ALL, "DECLARED",
            "TYPED_VOID", "LOGICAL_BATCH", "event:player_update"),
        route("sync", ProgramRole.EVENT_SYNC, "LIST<F32>", SYNC_MAX_ARGUMENTS, HandlerSelection.ALL, "DECLARED",
            "TYPED_VOID", "SYNC_BATCH", "event:sync"),
        route("defer", ProgramRole.EVENT_DEFER, "LIST<F32>", DEFER_MAX_ARGUMENTS, HandlerSelection.ALL,
            "CAPTURE_LIFO_HANDLER_DECLARED", "TYPED_VOID", "DEFER_BATCH", "event:defer"),
        route("*_ctrl_*", ProgramRole.EVENT_CODED_CONTROLLER, "NONE", 0, HandlerSelection.FIRST, "FIRST_DECLARED",
            "ABSENT=DELEGATE_PREDICATE;DETERMINISTIC_FALLBACK=DELEGATE_PREDICATE;"
                + "UNSET=DELEGATE_PREDICATE;2=CONTINUE;3=STOP;4=PAUSE;" + "OTHER_SUCCESS=DELEGATE_PREDICATE",
            "SUCCESS_DELEGATION_COMMITS;FAILED_INVOCATION_DISCARDS", "event:coded-controller")));

    private final List<Route> routes;
    private final Fingerprint fingerprint;

    public MolangEventCatalog(List<Route> routes) {
        var copy = new ArrayList<>(Objects.requireNonNull(routes, "routes"));
        copy.sort(Comparator.comparing(Route::routeId));
        if (copy.size() != 5 || copy.stream().map(Route::role).distinct().count() != 5) {
            throw new IllegalArgumentException("event catalog must declare every frozen role once");
        }
        this.routes = List.copyOf(copy);
        this.fingerprint = fingerprint(this.routes);
    }

    public static MolangEventCatalog production() {
        return PRODUCTION;
    }

    public List<Route> routes() {
        return routes;
    }

    public Fingerprint fingerprint() {
        return fingerprint;
    }

    public Classification classify(String suffix, int declaredOrder) {
        Objects.requireNonNull(suffix, "suffix");
        if (declaredOrder < 0) {
            throw new IllegalArgumentException("declaredOrder must be non-negative");
        }
        var normalized = suffix.toLowerCase(java.util.Locale.ROOT);
        Optional<Route> route = routes.stream().filter(value -> matches(value, normalized)).findFirst();
        if (route.isPresent()) {
            return new Classification.Declared(route.orElseThrow(), declaredOrder);
        }
        return new Classification.Rejected(suffix, "unknown-event-suffix:v2:" + suffix);
    }

    private static boolean matches(Route route, String suffix) {
        return route.routeId().equals(suffix)
            || route.routeId().equals("*_ctrl_*") && CODED_CONTROLLER.matcher(suffix).matches();
    }

    private static Route route(String routeId, ProgramRole role, String payload, int bound, HandlerSelection selection,
        String ordering, String fallback, String effect, String diagnostic) {
        return new Route(routeId, role, payload, role.shape().name(), bound, selection, ordering, fallback, effect,
            diagnostic, PublicReachability.HOST_ONLY);
    }

    private static Fingerprint fingerprint(List<Route> routes) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                text(output, DOMAIN);
                output.writeInt(routes.size());
                for (var route : routes) {
                    text(output, route.routeId());
                    text(output, route.role().name());
                    text(output, route.payload());
                    text(output, route.result());
                    output.writeInt(route.maximumArguments());
                    text(output, route.handlerSelection().name());
                    text(output, route.ordering());
                    text(output, route.fallback());
                    text(output, route.effect());
                    text(output, route.diagnostic());
                    text(output, route.publicReachability().name());
                    text(output, route.signature().toString());
                }
            }
            return Fingerprint.compute(ExpressionIdentityHash.SHA_256, ReadOnlyBytes.copyOf(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    public enum HandlerSelection {
        ALL, FIRST
    }

    public enum PublicReachability {
        HOST_ONLY
    }

    public record Route(String routeId, ProgramRole role, String payload, String result, int maximumArguments,
        HandlerSelection handlerSelection, String ordering, String fallback, String effect, String diagnostic,
        PublicReachability publicReachability) {
        public Route {
            Objects.requireNonNull(routeId, "routeId");
            Objects.requireNonNull(role, "role");
            if (!role.isEvent()) {
                throw new IllegalArgumentException("event route requires an event role");
            }
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(result, "result");
            if (maximumArguments < 0) {
                throw new IllegalArgumentException("maximumArguments must be non-negative");
            }
            Objects.requireNonNull(handlerSelection, "handlerSelection");
            Objects.requireNonNull(ordering, "ordering");
            Objects.requireNonNull(fallback, "fallback");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(diagnostic, "diagnostic");
            Objects.requireNonNull(publicReachability, "publicReachability");
        }

        public ProgramEntrySignature signature() {
            return role.signature(null, List.of());
        }
    }

    public sealed interface Classification {
        record Declared(Route route, int declaredOrder) implements Classification {
            public Declared {
                Objects.requireNonNull(route, "route");
                if (declaredOrder < 0) {
                    throw new IllegalArgumentException("declaredOrder must be non-negative");
                }
            }
        }

        record Rejected(String suffix, String diagnostic) implements Classification {
            public Rejected {
                Objects.requireNonNull(suffix, "suffix");
                Objects.requireNonNull(diagnostic, "diagnostic");
            }
        }
    }
}
