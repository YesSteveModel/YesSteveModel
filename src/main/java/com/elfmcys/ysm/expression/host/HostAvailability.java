package com.elfmcys.ysm.expression.host;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Immutable optional-provider availability captured before registry freeze. */
public record HostAvailability(Set<OptionalCapability> capabilities, Map<String, String> versions) {
    public HostAvailability(Set<OptionalCapability> capabilities) {
        this(capabilities, defaultVersions(capabilities));
    }

    public HostAvailability {
        Objects.requireNonNull(capabilities, "capabilities");
        Set<OptionalCapability> acceptedCapabilities =
            capabilities.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(capabilities));
        var stableVersions = new TreeMap<String, String>();
        Objects.requireNonNull(versions, "versions").forEach((modId, version) -> {
            stableVersions.put(modId, Objects.requireNonNull(version, "version"));
        });
        for (var capability : acceptedCapabilities) {
            stableVersions.putIfAbsent(capability.modId(), "unknown");
        }
        capabilities = acceptedCapabilities;
        versions = Map.copyOf(stableVersions);
    }

    public static HostAvailability none() {
        return new HostAvailability(Set.of());
    }

    public static HostAvailability all() {
        return new HostAvailability(EnumSet.allOf(OptionalCapability.class));
    }

    public static HostAvailability detect(Predicate<String> loadedMod) {
        return detect(loadedMod, Map.of());
    }

    public static HostAvailability detect(Predicate<String> loadedMod, Map<String, String> versions) {
        Objects.requireNonNull(loadedMod, "loadedMod");
        var capabilities = EnumSet.noneOf(OptionalCapability.class);
        for (var capability : OptionalCapability.values()) {
            if (loadedMod.test(capability.modId())) {
                capabilities.add(capability);
            }
        }
        return new HostAvailability(capabilities, versions);
    }

    public boolean supports(OptionalCapability capability) {
        return capability == null || capabilities.contains(capability);
    }

    private static Map<String, String> defaultVersions(Set<OptionalCapability> capabilities) {
        var values = new TreeMap<String, String>();
        for (var capability : capabilities) {
            values.put(capability.modId(), "unknown");
        }
        return values;
    }
}
