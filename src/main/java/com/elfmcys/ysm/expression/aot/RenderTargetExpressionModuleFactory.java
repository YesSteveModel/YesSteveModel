package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.compatibility.AbiVersion;

import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.render.BakedAnimationMapper;
import com.elfmcys.ysm.expression.frontend.MolangNames;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.network.protocol.ProtocolLimits;
import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.expression.host.HostLinkage;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.proto.baked.asset.BakedAnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import dev.semaweave.EntryResult;
import dev.semaweave.ProgramKey;
import dev.semaweave.ProgramShape;
import dev.semaweave.SymbolKey;
import dev.semaweave.TopLevelEntrySignature;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.BackendException;
import dev.semaweave.compilation.CompilationUnit;
import dev.semaweave.compilation.CompilerOptions;
import dev.semaweave.compilation.DefaultExpressionAotCompiler;
import dev.semaweave.compilation.DiagnosticLocation;
import dev.semaweave.compilation.ExpectedArtifactIdentity;
import dev.semaweave.compilation.ModuleLimitException;
import dev.semaweave.compilation.ModuleLoaders;
import dev.semaweave.compilation.ProgramSource;
import dev.semaweave.compilation.SemanticCompilationException;
import dev.semaweave.program.EncodedProgram;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.PortablePrograms;
import dev.semaweave.program.ProgramNode;
import dev.semaweave.program.ProgramStructuralException;
import dev.semaweave.program.ReadOnlyBytes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Complete off-thread compile/cache/verify/load/bind pipeline for one target candidate. */
public final class RenderTargetExpressionModuleFactory {
    private static final int MAX_FAILURES = 64;
    private static final CompilerOptions OPTIONS = CompilerOptions.DEFAULT;

    private final ExpressionArtifactStore artifacts;
    private final ExpressionHost host;
    private final MolangEventCatalog eventCatalog;
    private final HostLinkage productionLinkage;
    private final ExpressionEngineMetrics metrics;
    private final JaninoJavaCompilerBackend backend = new JaninoJavaCompilerBackend();
    private final DefaultExpressionAotCompiler<MolangContext> compiler;
    private final dev.semaweave.compilation.ModuleLoader<MolangContext> loader;
    private final RenderTargetExpressionModuleFactory builtinOnly;
    private final LinkedHashMap<FailureKey, AssetLoadException> deterministicFailures =
        new LinkedHashMap<>(16, 0.75f, true);

    public RenderTargetExpressionModuleFactory(ExpressionArtifactStore artifacts, ExpressionHost host,
        ExpressionEngineMetrics metrics) {
        this(artifacts, host, MolangEventCatalog.production(), HostLinkage.production(host), metrics);
    }

    public RenderTargetExpressionModuleFactory(ExpressionArtifactStore artifacts, ExpressionHost host,
        MolangEventCatalog eventCatalog, HostLinkage productionLinkage, ExpressionEngineMetrics metrics) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.host = Objects.requireNonNull(host, "host");
        this.eventCatalog = Objects.requireNonNull(eventCatalog, "eventCatalog");
        this.productionLinkage = Objects.requireNonNull(productionLinkage, "productionLinkage");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        compiler = new DefaultExpressionAotCompiler<>(backend, metrics);
        loader = ModuleLoaders.standard(metrics);
        builtinOnly = host.builtinOnly() == host ? this : new RenderTargetExpressionModuleFactory(artifacts,
            host.builtinOnly(), eventCatalog, HostLinkage.production(host.builtinOnly()), metrics);
    }

    public RenderTargetExpressionModuleFactory withArtifacts(ExpressionArtifactStore store) {
        return artifacts == store ? this
            : new RenderTargetExpressionModuleFactory(store, host, eventCatalog, productionLinkage, metrics);
    }

    public RenderTargetExpressionModuleFactory forDefaultModel(boolean defaultModel) {
        return defaultModel ? builtinOnly : this;
    }

    public ExpressionBinding expressionBinding(boolean defaultModel) {
        return forDefaultModel(defaultModel).expressionBinding();
    }

    public ExpressionBinding expressionBinding(boolean defaultModel, Class<?> subjectType) {
        return forDefaultModel(defaultModel).expressionBinding(subjectType);
    }

    public ExpressionBinding expressionBinding() {
        return host.expressionBinding();
    }

    public ExpressionBinding expressionBinding(Class<?> subjectType) {
        return host.expressionBinding(subjectType);
    }

    /** A negative probe avoids preparing input for a partition that has no cached artifact. */
    public boolean mayHaveCachedArtifacts(Hash256 containerId, ExpressionBinding binding, ModelResourcePurpose purpose) {
        return artifacts.mayHaveCachedArtifacts(containerId, binding, purpose);
    }

    public RenderTargetExpressionModule create(BooleanSupplier cancelled, ModelFileIdentity model, String targetId,
        String selectedTexture, BakeProfile bakeProfile, boolean defaultModel, ModelDataProto definition,
        StringDataProto strings, SettingsProto settings, byte[] configBytes) throws IOException {
        return create(cancelled, model, targetId, selectedTexture, bakeProfile, expressionBinding(defaultModel),
            defaultModel, definition, strings, settings, configBytes);
    }

    public RenderTargetExpressionModule create(BooleanSupplier cancelled, ModelFileIdentity model, String targetId,
        String selectedTexture, BakeProfile bakeProfile, ExpressionBinding expressionBinding, boolean defaultModel,
        ModelDataProto definition, StringDataProto strings, SettingsProto settings, byte[] configBytes)
        throws IOException {
        return forDefaultModel(defaultModel).createSet(cancelled, model.containerId(), targetId, ExpressionSet.MAIN,
            expressionBinding, ExpressionSet.MAIN.select(definition), strings, settings, configBytes, false);
    }

    public RenderTargetExpressionModule createSet(BooleanSupplier cancelled, Hash256 containerId, String targetId,
        ExpressionSet set, ExpressionBinding expressionBinding, ModelDataProto definition, StringDataProto strings,
        SettingsProto settings, byte[] configBytes, boolean cacheOnly) throws IOException {
        return createSet(cancelled, containerId, targetId, set, expressionBinding, definition, strings, settings,
            configBytes, cacheOnly, ModelResourcePurpose.FULL);
    }

    public RenderTargetExpressionModule createSet(BooleanSupplier cancelled, Hash256 containerId, String targetId,
        ExpressionSet set, ExpressionBinding expressionBinding, ModelDataProto definition, StringDataProto strings,
        SettingsProto settings, byte[] configBytes, boolean cacheOnly, ModelResourcePurpose purpose)
        throws IOException {
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(containerId, "containerId");
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(expressionBinding, "expressionBinding");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(configBytes, "configBytes");
        Objects.requireNonNull(purpose, "purpose");
        if (expressionBinding.environment() != host.declarationEnvironment()) {
            throw new IllegalArgumentException("expressionBinding belongs to another frozen host environment");
        }
        var failureKey = FailureKey.create(containerId, targetId, set, expressionBinding, configBytes, purpose);
        var previousFailure = previousFailure(failureKey);
        if (previousFailure != null) {
            throw AssetLoadException.content("Expression target candidate previously failed deterministically",
                previousFailure);
        }

        try {
            requireActive(cancelled);
            requireCompatibleLinkage(targetId);
            final ExpressionArtifactLookup lookup;
            final ModelProgramInventory.Prepared prepared;
            try {
                prepared = ModelProgramInventory.prepare(targetId, set, definition, strings, settings, eventCatalog);
                lookup = ExpressionArtifactLookup.create(containerId, targetId, set, expressionBinding,
                    prepared.sourceFingerprint(), configBytes, host, eventCatalog, productionLinkage, OPTIONS,
                    backend.descriptor(), purpose);
            } catch (ModelProgramInventory.InventoryException | IllegalArgumentException failure) {
                throw AssetLoadException.content("Invalid expression target candidate: " + targetId, failure);
            }
            var locations = prepared.locations();
            var stored = cacheOnly ? artifacts.loadExisting(lookup) : artifacts.loadOrCompile(lookup,
                () -> compileCold(cancelled, lookup, targetId, set, definition, prepared, expressionBinding));
            if (stored == null)
                throw AssetLoadException.access("Expression artifact is not locally cached");
            requireActive(cancelled);
            var loadStarted = System.nanoTime();
            var resource = loader.load(stored.artifact(), stored.identity().expected(), host.registry(),
                host.registry().visibilityRoot(), ExpressionIdentityHash.SHA_256);
            try {
                var bindings = bind(locations, resource);
                requireActive(cancelled);
                var module =
                    new RenderTargetExpressionModule(stored.identity(), resource, host, stored.artifact().constants(),
                        stored.artifact().report(), programLocations(locations), bindings, userFunctions(locations),
                        stored.source(), eventCatalog, productionLinkage, expressionBinding, stored.animations());
                ResourceDiagnostics.log("Molang load success: {}/{}/{} source={} ({} ms)", containerId, targetId,
                    set.id(), stored.source(), (System.nanoTime() - loadStarted) / 1_000_000.0);
                return module;
            } catch (Throwable failure) {
                try { resource.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } catch (CancellationException failure) {
            throw failure;
        } catch (AssetLoadException failure) {
            if (failure.reason() == AssetLoadException.Reason.CONTENT) {
                rememberFailure(failureKey, failure);
            }
            throw failure;
        } catch (IOException failure) {
            throw AssetLoadException.access("Failed to access expression target: " + targetId, failure);
        } catch (RuntimeException failure) {
            throw AssetLoadException.access("Failed to verify or load expression target: " + targetId, failure);
        }
    }

    private void requireCompatibleLinkage(String targetId) throws AssetLoadException {
        try {
            productionLinkage.requireCompatible(host);
        } catch (IllegalArgumentException failure) {
            throw AssetLoadException.access("Expression host linkage is incompatible for target: " + targetId, failure);
        }
    }

    private ExpressionArtifactStore.Produced compileCold(BooleanSupplier cancelled, ExpressionArtifactLookup lookup,
        String targetId, ExpressionSet set, ModelDataProto definition, ModelProgramInventory.Prepared prepared,
        ExpressionBinding expressionBinding) throws IOException {
        var started = System.nanoTime();
        try {
            requireActive(cancelled);
            metrics.recordPortableProgramDecodeInventory();
            var inventory = prepared.collect();
            var unit = compilationUnit(inventory, targetId, lookup.fingerprint());
            var expected = ExpectedArtifactIdentity.create(unit, host.hostAbi(), host.registry(), OPTIONS,
                expressionBinding.bindingPlan(), backend.descriptor(), ExpressionIdentityHash.SHA_256);
            var identity = ExpressionArtifactIdentity.create(expected, lookup);
            var artifact = compile(cancelled, unit, expressionBinding);
            var ids = new LinkedHashMap<ModelProgramInventory.SemanticLocation, Integer>();
            for (var location : prepared.locations()) {
                ids.put(location.location(),
                    artifact.catalog().requireId(location.key()).value());
            }
            var animations = new LinkedHashMap<String, java.util.List<BakedAnimationProto>>();
            for (var file : definition.animationFiles().object2ObjectEntrySet()) {
                var baked = new LinkedHashMap<String, BakedAnimationProto>();
                for (var animation : file.getValue().animations()) {
                    if (animation.name().isBlank())
                        throw new IllegalArgumentException("Empty animation name");
                    var value = BakedAnimationMapper.bake(animation, "animation/" + file.getKey(),
                        location -> ids.get(new ModelProgramInventory.SemanticLocation(targetId, set, location,
                            ProgramRole.ANIMATION_SAMPLE, "")),
                        location -> ids.get(new ModelProgramInventory.SemanticLocation(targetId, set, location,
                            ProgramRole.ANIMATION_INSTRUCTION, "")));
                    baked.put(animation.name(), value);
                }
                animations.put(file.getKey(), java.util.List.copyOf(baked.values()));
            }
            var produced = new ExpressionArtifactStore.Produced(identity, artifact, animations);
            ResourceDiagnostics.log("Molang compile success: {}/{}/{} ({} ms)", lookup.containerId(), targetId,
                set.id(), (System.nanoTime() - started) / 1_000_000.0);
            return produced;
        } catch (ModelProgramInventory.InventoryException | ProgramStructuralException
            | IllegalArgumentException failure) {
            throw AssetLoadException.content("Invalid expression cold-compilation input: " + targetId, failure);
        } catch (ModuleLimitException failure) {
            throw AssetLoadException.access("Expression cold-compilation limit exceeded: " + targetId, failure);
        }
    }

    private CompilationUnit compilationUnit(ModelProgramInventory.Result inventory, String targetId,
        dev.semaweave.identity.Fingerprint sourceFingerprint) throws ProgramStructuralException, ModuleLimitException {
        requireWireCompatibleRoamingLayout(inventory);
        var builder = CompilationUnit
            .builder(host.compilationPolicy(), PortableProgramCodecs.v1(), ExpressionIdentityHash.SHA_256)
            .verifiedSourceFingerprint(sourceFingerprint);
        inventory.roamingSlots().forEach(builder::addRoamingInstanceSlot);
        if (inventory.programs().isEmpty()) {
            builder.addProgram(emptyTargetProgram(targetId));
        } else {
            builder.addPrograms(inventory.programs());
        }
        return builder.build();
    }

    private static void requireWireCompatibleRoamingLayout(ModelProgramInventory.Result inventory) {
        if (inventory.roamingSlots().size() > ProtocolLimits.MAX_ROAMING_VARIABLES) {
            throw new IllegalArgumentException("roaming layout exceeds wire field capacity");
        }
        for (var name : inventory.roamingSlots().values()) {
            if (name.getBytes(StandardCharsets.UTF_8).length > ProtocolLimits.MAX_ROAMING_VARIABLE_NAME_BYTES) {
                throw new IllegalArgumentException("roaming field name exceeds wire capacity: " + name);
            }
        }
    }

    private dev.semaweave.compilation.CompiledArtifact compile(BooleanSupplier cancelled, CompilationUnit unit,
        ExpressionBinding expressionBinding) throws IOException {
        try {
            return compiler.compile(cancelled::getAsBoolean, unit, host.hostAbi(), host.registry(), OPTIONS,
                expressionBinding.bindingPlan(), ExpressionIdentityHash.SHA_256);
        } catch (SemanticCompilationException failure) {
            throw AssetLoadException.content("Expression cold compilation failed", failure);
        } catch (ModuleLimitException failure) {
            throw AssetLoadException.access("Expression compilation budget rejected: " + failure.limitKind()
                + " (limit=" + failure.maximum() + ", phase=" + failure.phase() + ")", failure);
        } catch (BackendException failure) {
            throw AssetLoadException.access("Expression backend compilation failed", failure);
        }
    }

    private static Map<ModelProgramInventory.SemanticLocation, dev.semaweave.compilation.ProgramRef> bind(
        java.util.List<ModelProgramInventory.LocatedProgram> locations,
        dev.semaweave.CompiledModuleResource<MolangContext> resource) {
        var expectedCatalogSize = locations.isEmpty() ? 1 : locations.size();
        if (resource.programs().size() != expectedCatalogSize) {
            throw new IllegalArgumentException(
                "Expression program catalog is incomplete or contains unexpected entries");
        }
        var bindings =
            new LinkedHashMap<ModelProgramInventory.SemanticLocation, dev.semaweave.compilation.ProgramRef>();
        for (var binding : locations) {
            var reference = resource.bindProgram(binding.key(), binding.shape());
            if (bindings.put(binding.location(), reference) != null) {
                throw new IllegalArgumentException("Duplicate expression semantic location: " + binding.location());
            }
        }
        return Map.copyOf(bindings);
    }

    private static Map<SymbolKey, ProgramKey>
        userFunctions(java.util.List<ModelProgramInventory.LocatedProgram> locations) {
        var functions = new LinkedHashMap<SymbolKey, ProgramKey>();
        for (var located : locations) {
            if (located.location().role() != ProgramRole.USER_FUNCTION) {
                continue;
            }
            var location = located.location().location();
            var name = location.substring("function/".length());
            var separator = name.indexOf('@');
            var callableName = separator < 0 ? name : name.substring(0, separator);
            var symbol = new SymbolKey(MolangNames.userFunctionSymbol(callableName));
            var previous = functions.putIfAbsent(symbol, located.key());
            if (previous != null && !previous.equals(located.key())) {
                throw new IllegalArgumentException("Ambiguous expression user function: " + callableName);
            }
        }
        return Map.copyOf(functions);
    }

    private static Map<ProgramKey, ModelProgramInventory.SemanticLocation>
        programLocations(java.util.List<ModelProgramInventory.LocatedProgram> locations) {
        var result = new LinkedHashMap<ProgramKey, ModelProgramInventory.SemanticLocation>();
        for (var located : locations) {
            if (result.put(located.key(), located.location()) != null) {
                throw new IllegalArgumentException("Duplicate expression program key: " + located.key());
            }
        }
        return Map.copyOf(result);
    }

    private static ProgramSource emptyTargetProgram(String targetId) throws ProgramStructuralException {
        var codec = PortableProgramCodecs.v1();
        EncodedProgram noOp = codec.encode(PortablePrograms.ofRoots(java.util.List.of(new ProgramNode.Unset())));
        var key = new ProgramKey(ReadOnlyBytes.copyOf(
            (("ysm:empty-target:" + AbiVersion.CACHE_ANIMATION + "\0") + targetId).getBytes(StandardCharsets.UTF_8)));
        return new ProgramSource(key, noOp,
            new TopLevelEntrySignature(ProgramShape.VOID, new EntryResult.Void(), java.util.List.of(),
                java.util.List.of()),
            Optional.of(noOp), new DiagnosticLocation(targetId + "/internal/empty-target"),
            dev.semaweave.EntryPermissions.CONTEXT);
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Expression target build was cancelled");
        }
    }

    private synchronized AssetLoadException previousFailure(FailureKey key) {
        return deterministicFailures.get(key);
    }

    private synchronized void rememberFailure(FailureKey key, AssetLoadException failure) {
        deterministicFailures.putIfAbsent(key, failure);
        while (deterministicFailures.size() > MAX_FAILURES) {
            var iterator = deterministicFailures.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private record FailureKey(Hash256 containerId, String targetId, ExpressionSet set,
        ExpressionBinding expressionBinding, String configHash, ModelResourcePurpose purpose) {
        private static FailureKey create(Hash256 containerId, String targetId, ExpressionSet set,
            ExpressionBinding expressionBinding, byte[] configBytes, ModelResourcePurpose purpose) {
            var fingerprint = dev.semaweave.identity.Fingerprint.compute(ExpressionIdentityHash.SHA_256,
                ReadOnlyBytes.copyOf(configBytes));
            return new FailureKey(containerId, targetId, set, expressionBinding, fingerprint.value().toString(),
                purpose);
        }
    }
}
