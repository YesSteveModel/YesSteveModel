package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.compatibility.AbiVersion;

import dev.semaweave.CompiledRoamingState;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.ReadOnlyBytes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Closed registry-to-production linkage and pre-cutover compatibility gate. Runtime dispatch is enum-indexed and
 * carries no evaluator or current-entity state.
 */
public final class HostLinkage {
    public static final int ABI_VERSION = ExpressionAbi.ANIMATION_NUMBER;
    public static final int EFFECT_SINK_ABI_VERSION = ExpressionAbi.ANIMATION_NUMBER;
    private static final String DOMAIN = "ysm:production-host-linkage:" + AbiVersion.CACHE_ANIMATION;

    private final int abiVersion;
    private final List<Binding> manifest;
    private final List<DescriptorBinding> descriptorManifest;
    private final boolean[] bySymbol;
    private final Fingerprint registryFingerprint;
    private final Fingerprint fingerprint;

    private HostLinkage(int abiVersion, List<Binding> bindings, List<DescriptorBinding> descriptorBindings,
        Fingerprint registryFingerprint) {
        this.abiVersion = abiVersion;
        this.registryFingerprint = Objects.requireNonNull(registryFingerprint, "registryFingerprint");
        var copy = new ArrayList<>(Objects.requireNonNull(bindings, "bindings"));
        copy.sort(Comparator.comparing((Binding value) -> value.symbol().stableId()).thenComparing(Binding::adapter));
        this.manifest = List.copyOf(copy);
        var descriptors = new ArrayList<>(Objects.requireNonNull(descriptorBindings, "descriptorBindings"));
        descriptors.sort(Comparator.comparing(DescriptorBinding::stableId).thenComparing(DescriptorBinding::accessKind)
            .thenComparing(DescriptorBinding::adapter));
        this.descriptorManifest = List.copyOf(descriptors);
        this.bySymbol = new boolean[HostSymbol.values().length];
        var exactMembers = new HashSet<String>();
        for (var binding : copy) {
            if (!exactMembers.add(binding.symbol().stableId() + '\0' + binding.adapter())) {
                throw new IllegalArgumentException("duplicate production linkage " + binding.symbol().stableId());
            }
            bySymbol[binding.symbol().ordinal()] = true;
        }
        this.fingerprint = fingerprint(abiVersion, this.manifest, this.descriptorManifest, registryFingerprint);
    }

    public static HostLinkage production(ExpressionHost host) {
        Objects.requireNonNull(host, "host");
        var bindings = host.builtins().stream().filter(value -> HostSymbol.fromStableId(value.stableId()) != null)
            .map(Binding::production).toList();
        var byStableId = new java.util.HashMap<String, Binding>();
        bindings.forEach(value -> byStableId.put(value.symbol().stableId(), value));
        var descriptors = host.manifest().stream().filter(ExpressionHost.ManifestEntry::available).map(entry -> {
            var symbol = byStableId.get(entry.stableId());
            return new DescriptorBinding(entry.stableId(), entry.accessKind(), entry.javaMember(),
                symbol == null ? "DIRECT_REGISTRY_BINDING" : symbol.effectMode());
        }).toList();
        var result = new HostLinkage(ABI_VERSION, bindings, descriptors, host.registry().fingerprint());
        result.requireCompatible(host);
        return result;
    }

    public int abiVersion() {
        return abiVersion;
    }

    public List<Binding> manifest() {
        return manifest;
    }

    public Fingerprint fingerprint() {
        return fingerprint;
    }

    public List<DescriptorBinding> descriptorManifest() {
        return descriptorManifest;
    }

    public void requireCompatible(ExpressionHost host) {
        Objects.requireNonNull(host, "host");
        if (abiVersion != ABI_VERSION) {
            throw new IllegalArgumentException("production linkage ABI mismatch");
        }
        if (!registryFingerprint.equals(host.registry().fingerprint())) {
            throw new IllegalArgumentException("production linkage registry mismatch");
        }
        var expected = host.builtins().stream().filter(BuiltinDescriptor::available)
            .map(value -> HostSymbol.fromStableId(value.stableId())).filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        var actual = new HashSet<HostSymbol>();
        for (var binding : manifest) {
            actual.add(binding.symbol());
            if (binding.accessKind() != binding.symbol().accessKind()
                || binding.optionalCapability() != binding.symbol().optionalCapability()
                || binding.adapter().contains("ExpressionEvaluator")
                || binding.adapter().contains("ControlledHostFixture")) {
                throw new IllegalArgumentException("invalid production linkage " + binding.symbol().stableId());
            }
        }
        if (!actual.equals(expected)) {
            var missing = new HashSet<>(expected);
            missing.removeAll(actual);
            var orphan = new HashSet<>(actual);
            orphan.removeAll(expected);
            throw new IllegalArgumentException(
                "production linkage is not closed; missing=" + missing + ", orphan=" + orphan);
        }
        var expectedDescriptors = host.manifest().stream().filter(ExpressionHost.ManifestEntry::available)
            .map(value -> value.stableId() + '\0' + value.accessKind() + '\0' + value.javaMember())
            .collect(java.util.stream.Collectors.toSet());
        var actualDescriptors = new HashSet<String>();
        for (var descriptor : descriptorManifest) {
            if (!actualDescriptors
                .add(descriptor.stableId() + '\0' + descriptor.accessKind() + '\0' + descriptor.adapter())
                || descriptor.adapter().contains("ExpressionEvaluator")
                || descriptor.adapter().contains("ControlledHostFixture")) {
                throw new IllegalArgumentException("invalid production descriptor linkage " + descriptor.stableId());
            }
        }
        if (!actualDescriptors.equals(expectedDescriptors)) {
            throw new IllegalArgumentException("production descriptor linkage is not bidirectionally closed");
        }
    }

    public EffectValidator access(InvocationInputs<?> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        return new Access(inputs);
    }

    public <E extends net.minecraft.world.entity.Entity> MolangContext<E> observation(InvocationInputs<E> inputs,
        CompiledRoamingState roaming, InvocationProvenance provenance, BooleanSupplier cancelled,
        BooleanSupplier currentGeneration) {
        return MolangContext.observation(inputs.entity(), inputs, access(inputs), roaming, provenance, cancelled,
            currentGeneration);
    }

    public <E extends net.minecraft.world.entity.Entity> MolangContext<E> logical(InvocationInputs<E> inputs,
        CompiledRoamingState roaming, MolangContext.LogicalPhase phase, InvocationProvenance provenance,
        BooleanSupplier cancelled, BooleanSupplier currentGeneration, int maximumEffectCommands) {
        return MolangContext.logical(inputs.entity(), inputs, phase, access(inputs), roaming, provenance, cancelled,
            currentGeneration, maximumEffectCommands);
    }

    /** Test-only derivation used to prove the admission gate fails before activation. */
    public HostLinkage without(HostSymbol symbol) {
        return new HostLinkage(abiVersion, manifest.stream().filter(value -> value.symbol() != symbol).toList(),
            descriptorManifest.stream().filter(value -> !value.stableId().equals(symbol.stableId())).toList(),
            registryFingerprint);
    }

    /** Test-only derivation used to prove ABI changes participate in admission and identity. */
    public HostLinkage withAbiVersion(int value) {
        return new HostLinkage(value, manifest, descriptorManifest, registryFingerprint);
    }

    private void require(HostSymbol symbol) {
        if (!bySymbol[symbol.ordinal()]) {
            throw new IllegalStateException("missing admitted production linkage " + symbol.stableId());
        }
    }

    private static Fingerprint fingerprint(int abiVersion, List<Binding> bindings,
        List<DescriptorBinding> descriptorBindings, Fingerprint registryFingerprint) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                text(output, DOMAIN);
                output.writeInt(abiVersion);
                output.writeInt(EFFECT_SINK_ABI_VERSION);
                field(output, registryFingerprint.value().bytes().toByteArray());
                output.writeInt(bindings.size());
                for (var binding : bindings) {
                    text(output, binding.symbol().stableId());
                    text(output, binding.accessKind().name());
                    text(output, binding.optionalCapability() == null ? "core" : binding.optionalCapability().name());
                    text(output, binding.adapter());
                    text(output, binding.effectMode());
                }
                output.writeInt(descriptorBindings.size());
                for (var binding : descriptorBindings) {
                    text(output, binding.stableId());
                    text(output, binding.accessKind());
                    text(output, binding.adapter());
                    text(output, binding.effectMode());
                }
            }
            return Fingerprint.compute(ExpressionIdentityHash.SHA_256, ReadOnlyBytes.copyOf(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        field(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void field(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    public record Binding(HostSymbol symbol, HostSymbol.AccessKind accessKind, OptionalCapability optionalCapability,
        String adapter, String effectMode) {
        public Binding {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(accessKind, "accessKind");
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(effectMode, "effectMode");
        }

        private static Binding production(BuiltinDescriptor descriptor) {
            var surface = descriptor.surface();
            var symbol = HostSymbol.fromStableId(surface.stableId());
            if (symbol == null) {
                throw new IllegalArgumentException(
                    "built-in has no command/diagnostic addressing token " + surface.stableId());
            }
            var binding = descriptor.binding();
            var member = binding.owner().getName() + '.' + binding.methodName()
                + binding.methodType().toMethodDescriptorString();
            return new Binding(symbol, surface.accessKind(), surface.optionalCapability(), member,
                surface.staged() ? "STAGE_TYPED_COMMAND" : "DIRECT_CONTEXT_OR_ACTION");
        }
    }

    public record DescriptorBinding(String stableId, String accessKind, String adapter, String effectMode) {
        public DescriptorBinding {
            Objects.requireNonNull(stableId, "stableId");
            Objects.requireNonNull(accessKind, "accessKind");
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(effectMode, "effectMode");
        }
    }

    private final class Access implements EffectValidator {
        private final InvocationInputs inputs;

        private Access(InvocationInputs inputs) {
            this.inputs = inputs;
        }

        @Override
        public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {
            var symbol = effectSymbol(command);
            require(symbol);
            if (!symbol.stagedEffect()) {
                throw new IllegalArgumentException("production binding is not a staged effect");
            }
            inputs.prevalidateEffect(command, scope);
        }

        private static HostSymbol effectSymbol(EffectCommand command) {
            if (command instanceof EffectCommand.Sound sound)
                return sound.action();
            if (command instanceof EffectCommand.Particle particle) {
                return particle.absolute() ? HostSymbol.YSM_ABS_PARTICLE : HostSymbol.YSM_PARTICLE;
            }
            if (command instanceof EffectCommand.Controller controller) {
                return controller.action();
            }
            if (command instanceof EffectCommand.Deferred)
                return HostSymbol.YSM_DEFER;
            if (command instanceof EffectCommand.Sync)
                return HostSymbol.YSM_SYNC;
            throw new IllegalArgumentException("roaming writes use their external owner directly");
        }
    }
}
