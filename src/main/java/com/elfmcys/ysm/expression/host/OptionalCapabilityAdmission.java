package com.elfmcys.ysm.expression.host;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Startup-only owner for optional built-in qualification. */
public final class OptionalCapabilityAdmission {
    private final Predicate<String> loadedMod;
    private final Map<String, String> versions;
    private final ClassLoader loader;
    private final boolean allowUnknownVersions;
    private final Set<OptionalCapability> attempted = EnumSet.noneOf(OptionalCapability.class);
    private final Set<OptionalCapability> accepted = EnumSet.noneOf(OptionalCapability.class);
    private final List<String> diagnostics = new ArrayList<>();
    private boolean finished;

    public OptionalCapabilityAdmission(Predicate<String> loadedMod, Map<String, String> versions, ClassLoader loader) {
        this(loadedMod, versions, loader, false);
    }

    public OptionalCapabilityAdmission(Predicate<String> loadedMod, Map<String, String> versions, ClassLoader loader,
        boolean allowUnknownVersions) {
        this.loadedMod = Objects.requireNonNull(loadedMod, "loadedMod");
        this.versions = Map.copyOf(Objects.requireNonNull(versions, "versions"));
        this.loader = Objects.requireNonNull(loader, "loader");
        this.allowUnknownVersions = allowUnknownVersions;
    }

    public void initialize(OptionalCapability capability, Runnable initializer, BooleanSupplier compatible) {
        requireOpen();
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(initializer, "initializer");
        Objects.requireNonNull(compatible, "compatible");
        if (!attempted.add(capability)) {
            throw new IllegalStateException("optional capability initialized twice: " + capability);
        }
        if (!loadedMod.test(capability.modId())) {
            return;
        }
        var version = versions.get(capability.modId());
        if (!capability.supportsVersion(version, allowUnknownVersions)) {
            diagnostics.add(
                capability.modId() + ": unsupported version " + version + ", minimum " + capability.minimumVersion());
            return;
        }
        try {
            OptionalLinkageVerifier.verify(capability, loader);
            initializer.run();
            if (!compatible.getAsBoolean()) {
                return;
            }
            accepted.add(capability);
        } catch (RuntimeException | LinkageError failure) {
            diagnostics.add(capability.modId() + ": " + failure);
        }
    }

    public Result finish() {
        requireOpen();
        for (var capability : OptionalCapability.values()) {
            if (loadedMod.test(capability.modId()) && !attempted.contains(capability)) {
                throw new IllegalStateException("loaded optional capability was not qualified: " + capability);
            }
        }
        finished = true;
        return new Result(new HostAvailability(accepted, versions), diagnostics);
    }

    private void requireOpen() {
        if (finished) {
            throw new IllegalStateException("optional capability admission is frozen");
        }
    }

    public record Result(HostAvailability availability, List<String> diagnostics) {
        public Result {
            Objects.requireNonNull(availability, "availability");
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
