package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.compatibility.AbiVersion;
import dev.semaweave.CompiledRoamingState;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.ReadOnlyBytes;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exact production projection of the frozen registry; it owns no business dispatch. */
public final class HostLinkage {
    public static final int ABI_VERSION = ExpressionAbi.ANIMATION_NUMBER;
    public static final int EFFECT_SINK_ABI_VERSION = ExpressionAbi.ANIMATION_NUMBER;
    private final int abiVersion;
    private final List<DescriptorBinding> descriptors;
    private final Fingerprint registryFingerprint;
    private final Fingerprint fingerprint;

    private HostLinkage(int abiVersion, List<DescriptorBinding> descriptors, Fingerprint registryFingerprint) {
        this.abiVersion = abiVersion;
        this.descriptors = descriptors.stream().sorted(Comparator.comparing(DescriptorBinding::stableId)
            .thenComparing(DescriptorBinding::accessKind).thenComparing(DescriptorBinding::adapter)).toList();
        this.registryFingerprint = registryFingerprint;
        var text = "ysm:production-host-linkage:" + AbiVersion.CACHE_ANIMATION + ':' + abiVersion + ':'
            + EFFECT_SINK_ABI_VERSION + ':' + registryFingerprint + ':' + this.descriptors;
        fingerprint = Fingerprint.compute(ExpressionIdentityHash.SHA_256,
            ReadOnlyBytes.copyOf(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static HostLinkage production(ExpressionHost host) {
        var result = new HostLinkage(ABI_VERSION, projection(host), host.registry().fingerprint());
        result.requireCompatible(host);
        return result;
    }

    private static List<DescriptorBinding> projection(ExpressionHost host) {
        return host.manifest().stream().filter(ExpressionHost.ManifestEntry::available)
            .map(m -> new DescriptorBinding(m.stableId(), m.accessKind(), m.javaMember(), m.effects().toString()))
            .sorted(Comparator.comparing(DescriptorBinding::stableId).thenComparing(DescriptorBinding::accessKind)
                .thenComparing(DescriptorBinding::adapter)).toList();
    }

    public int abiVersion() { return abiVersion; }
    public Fingerprint fingerprint() { return fingerprint; }
    public List<DescriptorBinding> descriptorManifest() { return descriptors; }

    public void requireCompatible(ExpressionHost host) {
        if (abiVersion != ABI_VERSION || !registryFingerprint.equals(host.registry().fingerprint())
            || !descriptors.equals(projection(host)))
            throw new IllegalArgumentException("production declaration linkage mismatch");
    }

    public EffectValidator access(InvocationInputs<?> inputs) { return Objects.requireNonNull(inputs); }

    public <E extends net.minecraft.world.entity.Entity> MolangContext<E> observation(InvocationInputs<E> inputs,
        CompiledRoamingState roaming, InvocationProvenance provenance, BooleanSupplier cancelled,
        BooleanSupplier currentGeneration) {
        return MolangContext.observation(inputs.entity(), inputs, access(inputs), roaming, provenance, cancelled,
            currentGeneration);
    }

    public <E extends net.minecraft.world.entity.Entity> MolangContext<E> logical(InvocationInputs<E> inputs,
        CompiledRoamingState roaming, MolangContext.LogicalPhase phase, InvocationProvenance provenance,
        BooleanSupplier cancelled, BooleanSupplier currentGeneration, int maximumEffects) {
        return MolangContext.logical(inputs.entity(), inputs, phase, access(inputs), roaming, provenance, cancelled,
            currentGeneration, maximumEffects);
    }

    public HostLinkage without(String symbol) {
        return new HostLinkage(abiVersion, descriptors.stream().filter(d -> !d.stableId().equals(symbol)).toList(),
            registryFingerprint);
    }

    public HostLinkage withAbiVersion(int version) { return new HostLinkage(version, descriptors, registryFingerprint); }

    public record DescriptorBinding(String stableId, String accessKind, String adapter, String effectMode) {}
}
