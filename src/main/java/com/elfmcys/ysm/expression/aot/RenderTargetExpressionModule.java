package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.expression.host.BuiltinDeclarations;
import com.elfmcys.ysm.expression.host.ComponentScope;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.expression.host.HostLinkage;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.format.schema.baked.asset.BakedAnimationSet;
import com.elfmcys.ysm.model.resource.client.AnimationStore;

import dev.semaweave.CompiledModuleInstance;
import dev.semaweave.CompiledModuleResource;
import dev.semaweave.CompiledRoamingState;
import dev.semaweave.ProgramKey;
import dev.semaweave.RegistryRuntimeLinkage;
import dev.semaweave.ResourceLease;
import dev.semaweave.RoamingRole;
import dev.semaweave.RuntimeDiagnostics;
import dev.semaweave.RuntimeLinkage;
import dev.semaweave.RuntimeSymbolTable;
import dev.semaweave.SymbolKey;
import dev.semaweave.compilation.CompilationReport;
import dev.semaweave.compilation.ProgramRef;
import dev.semaweave.compilation.TransientAccessRequest;
import dev.semaweave.compilation.TransientLayout;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Execution backend owned by one AnimationStore after assembly. */
public final class RenderTargetExpressionModule implements AutoCloseable {
    private static final AtomicLong NEXT_GENERATION_ID = new AtomicLong();

    private final BakedAnimationSet animations;
    private final InterpretedExpressionPrograms interpretedPrograms;
    private final com.elfmcys.ysm.model.domain.Hash256 containerId;
    private final String targetId;
    private final com.elfmcys.ysm.expression.inventory.ExpressionSet set;
    private final dev.semaweave.identity.Fingerprint configFingerprint;
    private final ExpressionArtifactIdentity identity;
    private final CompiledModuleResource<MolangContext> resource;
    private final ExpressionHost host;
    private final CompilationReport compilationReport;
    private final Map<ProgramKey, ModelProgramInventory.SemanticLocation> programLocations;
    private final ExpressionBinding expressionBinding;
    private final Map<ModelProgramInventory.SemanticLocation, ProgramRef> programs;
    private final Map<SymbolKey, ProgramKey> userFunctions;
    private final ExpressionArtifactStore.Source source;
    private final MolangEventCatalog eventCatalog;
    private final java.util.List<String> roamingFields;
    private final HostLinkage productionLinkage;
    private final long generationId;
    private boolean closed;

    RenderTargetExpressionModule(ExpressionArtifactIdentity identity, CompiledModuleResource<MolangContext> resource,
        ExpressionHost host, CompilationReport compilationReport,
        Map<ProgramKey, ModelProgramInventory.SemanticLocation> programLocations,
        Map<ModelProgramInventory.SemanticLocation, ProgramRef> programs, Map<SymbolKey, ProgramKey> userFunctions,
        ExpressionArtifactStore.Source source, MolangEventCatalog eventCatalog, HostLinkage productionLinkage) {
        this(identity, resource, host, compilationReport, programLocations, programs, userFunctions, source,
            eventCatalog, productionLinkage, identity.expressionBinding());
    }

    RenderTargetExpressionModule(ExpressionArtifactIdentity identity, CompiledModuleResource<MolangContext> resource,
        ExpressionHost host, CompilationReport compilationReport,
        Map<ProgramKey, ModelProgramInventory.SemanticLocation> programLocations,
        Map<ModelProgramInventory.SemanticLocation, ProgramRef> programs, Map<SymbolKey, ProgramKey> userFunctions,
        ExpressionArtifactStore.Source source, MolangEventCatalog eventCatalog, HostLinkage productionLinkage,
        ExpressionBinding expressionBinding) {
        this(identity, resource, host, compilationReport, programLocations, programs, userFunctions, source,
            eventCatalog, productionLinkage, expressionBinding, BakedAnimationSet.empty());
    }

    RenderTargetExpressionModule(ExpressionArtifactIdentity identity, CompiledModuleResource<MolangContext> resource,
        ExpressionHost host, CompilationReport compilationReport,
        Map<ProgramKey, ModelProgramInventory.SemanticLocation> programLocations,
        Map<ModelProgramInventory.SemanticLocation, ProgramRef> programs, Map<SymbolKey, ProgramKey> userFunctions,
        ExpressionArtifactStore.Source source, MolangEventCatalog eventCatalog, HostLinkage productionLinkage,
        ExpressionBinding expressionBinding, BakedAnimationSet animations) {
        this.animations = Objects.requireNonNull(animations, "animations");
        this.interpretedPrograms = null;
        this.identity = Objects.requireNonNull(identity, "identity");
        this.containerId = identity.containerId();
        this.targetId = identity.targetId();
        this.set = identity.set();
        this.configFingerprint = identity.configFingerprint();
        this.resource = Objects.requireNonNull(resource, "resource");
        this.host = Objects.requireNonNull(host, "host");
        this.compilationReport = Objects.requireNonNull(compilationReport, "compilationReport");
        this.programLocations = Map.copyOf(programLocations);
        for (var replacement : compilationReport.replacements()) {
            if (!this.programLocations.containsKey(replacement.programKey())) {
                throw new IllegalArgumentException("Expression replacement has no current semantic location");
            }
        }
        this.expressionBinding = Objects.requireNonNull(expressionBinding, "expressionBinding");
        if (!identity.expressionBinding().equals(expressionBinding)
            || identity.expected().bindingPlan() != expressionBinding.bindingPlan()) {
            throw new IllegalArgumentException("expression identity does not match its expressionBinding");
        }
        this.programs = Map.copyOf(programs);
        this.userFunctions = Map.copyOf(userFunctions);
        this.source = Objects.requireNonNull(source, "source");
        this.eventCatalog = Objects.requireNonNull(eventCatalog, "eventCatalog");
        this.roamingFields = resource.roamingFieldNames();
        this.productionLinkage = Objects.requireNonNull(productionLinkage, "productionLinkage");
        this.generationId = NEXT_GENERATION_ID.incrementAndGet();
    }

    RenderTargetExpressionModule(com.elfmcys.ysm.model.resource.client.render.AnimationInputIdentity inputIdentity,
        BakedAnimationSet animations, ExpressionHost host, ExpressionBinding binding,
        ModelProgramInventory.Prepared sources, MolangEventCatalog eventCatalog, HostLinkage linkage,
        dev.semaweave.identity.Fingerprint configFingerprint, ExpressionArtifactStore.Source source) {
        this.identity = null;
        this.resource = null;
        this.animations = animations;
        this.host = host;
        this.containerId = inputIdentity.containerId();
        this.targetId = inputIdentity.targetId();
        this.set = inputIdentity.set();
        this.configFingerprint = configFingerprint;
        this.compilationReport = null;
        this.expressionBinding = binding;
        this.programs = Map.of();
        this.userFunctions = Map.of();
        this.source = source;
        this.eventCatalog = eventCatalog;
        this.productionLinkage = linkage;
        this.roamingFields = java.util.List.of();
        this.generationId = NEXT_GENERATION_ID.incrementAndGet();
        var locations = new java.util.LinkedHashMap<ProgramKey, ModelProgramInventory.SemanticLocation>();
        animations.descriptors().forEach(value -> locations.put(value.key(), value.location()));
        this.programLocations = Map.copyOf(locations);
        this.interpretedPrograms =
            new InterpretedExpressionPrograms(host, binding, animations, sources, inputIdentity.sourceOptions());
    }

    public boolean interpreted() {
        return interpretedPrograms != null;
    }

    public Object executionGeneration() {
        return interpreted() ? interpretedPrograms.generation() : generation();
    }

    public String targetId() {
        return targetId;
    }

    public com.elfmcys.ysm.model.domain.Hash256 containerId() {
        return containerId;
    }

    public com.elfmcys.ysm.expression.inventory.ExpressionSet set() {
        return set;
    }

    public int boundInterpreterProgramCount() {
        return interpreted() ? interpretedPrograms.boundProgramCount() : 0;
    }

    public InterpreterRuntimeUse openInterpreter(ComponentScope components, RoamingRole role) {
        requireOpen();
        return interpretedPrograms.open(components, role);
    }

    public void reportInterpreterPreparation() {
        if (interpreted())
            interpretedPrograms.reportPreparation();
    }

    public java.util.List<String> publishedSymbols() {
        return host.declarations().stream().flatMap(d -> d.members().stream()).map(m -> m.schema().symbol()).distinct()
            .sorted().toList();
    }

    public ExpressionArtifactIdentity identity() {
        requireAot();
        return identity;
    }

    public ExpressionArtifactStore.Source source() {
        return source;
    }

    public MolangEventCatalog eventCatalog() {
        return eventCatalog;
    }

    public SharedExpressionSemantics sharedSemantics(ModelId consumerModelId) {
        return SharedExpressionSemantics.create(consumerModelId, targetId, configFingerprint,
            eventCatalog.fingerprint());
    }

    public java.util.List<String> roamingFields() {
        return roamingFields;
    }

    public HostLinkage productionLinkage() {
        return productionLinkage;
    }

    public CompilationReport compilationReport() {
        requireAot();
        return compilationReport;
    }

    public ExpressionBinding expressionBinding() {
        return expressionBinding;
    }

    public ComponentScope prepareComponents(com.elfmcys.ysm.client.entity.AnimatableEntity<?> owner,
        ComponentScope existing) {
        Objects.requireNonNull(owner, "owner");
        var holder = existing;
        var created = false;
        if (holder == null) {
            holder = BuiltinDeclarations.createComponents(owner, host);
            created = true;
        } else if (holder.owner() != owner) {
            throw new IllegalArgumentException("component holder belongs to another owner");
        }
        try {
            if (interpreted()) {
                try (var ignored = openInterpreter(holder, RoamingRole.PRODUCER)) {
                }
            } else {
                try (var ignored = newInstance(holder)) {
                    // Construction proves the generated root can bind this exact object graph.
                }
            }
            return holder;
        } catch (RuntimeException | Error failure) {
            if (created) {
                try {
                    holder.invalidate();
                } catch (RuntimeException | Error invalidationFailure) {
                    failure.addSuppressed(invalidationFailure);
                }
            }
            throw failure;
        }
    }

    public Map<ProgramKey, ModelProgramInventory.SemanticLocation> programLocations() {
        return programLocations;
    }

    public dev.semaweave.ExecutionLimits executionLimits() {
        return host.executionLimits();
    }

    public Map<ModelProgramInventory.SemanticLocation, ProgramRef> programs() {
        return programs;
    }

    public ProgramRef requireProgram(ModelProgramInventory.SemanticLocation location) {
        Objects.requireNonNull(location, "location");
        var program = programs.get(location);
        if (program == null) {
            throw new IllegalArgumentException("Unbound expression program at " + location);
        }
        if (program.shape() != location.role().shape()) {
            throw new IllegalArgumentException("Expression program shape mismatch at " + location);
        }
        return program;
    }

    public ProgramRef requireProgram(String location, ProgramRole role, String discriminator) {
        return requireProgram(new ModelProgramInventory.SemanticLocation(targetId, set, location, role, discriminator));
    }

    java.util.Set<dev.semaweave.LocalName> transientInstanceSlots() {
        requireOpen();
        return resource.instanceSlotNames();
    }

    java.util.Collection<ProgramKey> transientUserFunctions() {
        requireOpen();
        return userFunctions.values();
    }

    public synchronized ResourceLease<MolangContext> retain() {
        requireOpen();
        return resource.retain();
    }

    public synchronized CompiledModuleInstance<MolangContext> newInstance(RuntimeLinkage<MolangContext> linkage) {
        requireOpen();
        return resource.newInstance(Objects.requireNonNull(linkage, "linkage"));
    }

    public synchronized CompiledRoamingState newRoamingState(RoamingRole role) {
        requireOpen();
        return resource.newRoamingState(Objects.requireNonNull(role, "role"));
    }

    public synchronized CompiledModuleInstance<MolangContext> newInstance(RuntimeLinkage<MolangContext> linkage,
        CompiledRoamingState roamingState) {
        requireOpen();
        return resource.newInstance(Objects.requireNonNull(linkage, "linkage"),
            Objects.requireNonNull(roamingState, "roamingState"));
    }

    synchronized TransientLayout<MolangContext> createTransientLayout(TransientAccessRequest request) {
        requireOpen();
        return resource.createTransientLayout(Objects.requireNonNull(request, "request"));
    }

    ExpressionHost host() {
        return host;
    }

    /** Creates one entity-owned instance from the holder admitted with this target. */
    public synchronized CompiledModuleInstance<MolangContext> newInstance(ComponentScope components) {
        requireOpen();
        return resource.newInstance(runtimeLinkage(components));
    }

    public synchronized CompiledModuleInstance<MolangContext> newInstance() {
        requireOpen();
        throw new IllegalStateException("entity-owned instances require a component holder");
    }

    public synchronized CompiledModuleInstance<MolangContext> newInstance(ComponentScope components,
        CompiledRoamingState roamingState) {
        requireOpen();
        return resource.newInstance(runtimeLinkage(components), Objects.requireNonNull(roamingState, "roamingState"));
    }

    private RuntimeLinkage<MolangContext> runtimeLinkage(ComponentScope components) {
        Objects.requireNonNull(components, "components");
        RegistryRuntimeLinkage<MolangContext> registry = RegistryRuntimeLinkage
            .create(components.providers(host, expressionBinding.bindingPlan()), RuntimeSymbolTable.empty());
        return new RuntimeLinkage<>() {
            @Override
            public RegistryRuntimeLinkage<MolangContext> registry() {
                return registry;
            }

            @Override
            public RuntimeDiagnostics diagnostics() {
                return RuntimeDiagnostics.none();
            }

        };
    }

    public AnimationStore.Index animationIndex(Iterable<String> files) {
        requireOpen();
        return animations.bind(files, this);
    }

    public TransientExpressionSession.CompilationInputs
        prepareTransientInputs(TransientExpressionSession.Privilege privilege) {
        requireOpen();
        requireAot();
        return TransientExpressionSession.prepare(this, privilege);
    }

    public Diagnostics diagnostics() {
        return new Diagnostics(identity, source, compilationReport, programLocations, publishedSymbols());
    }

    public record Diagnostics(ExpressionArtifactIdentity identity, ExpressionArtifactStore.Source source,
        CompilationReport compilationReport, Map<ProgramKey, ModelProgramInventory.SemanticLocation> programLocations,
        java.util.List<String> publishedSymbols) {
    }

    public FloatExpression.Program requireFloatProgram(ModelProgramInventory.SemanticLocation location) {
        return new FloatExpression.Program(location, binding(location));
    }

    public BooleanProgram requireBooleanProgram(ModelProgramInventory.SemanticLocation location) {
        return new BooleanProgram(location, binding(location));
    }

    public VoidProgram requireVoidProgram(ModelProgramInventory.SemanticLocation location) {
        return new VoidProgram(location, binding(location));
    }

    public EventProgram requireEventProgram(ModelProgramInventory.SemanticLocation location) {
        return location.role().shape() == dev.semaweave.ProgramShape.F32 ? requireFloatProgram(location)
            : requireVoidProgram(location);
    }

    public UserFunctionProgram requireUserFunctionProgram(ModelProgramInventory.SemanticLocation location) {
        return new UserFunctionProgram(location, binding(location));
    }

    private ProgramBinding binding(ModelProgramInventory.SemanticLocation location) {
        requireOpen();
        return interpreted() ? interpretedPrograms.reference(location)
            : new ProgramBinding.Aot(requireProgram(location), generation());
    }

    public dev.semaweave.compilation.ResourceGenerationToken generation() {
        requireAot();
        return resource.generation();
    }

    /** Process-local diagnostic identity shared by every entity instance of this target. */
    public long generationId() {
        return generationId;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Render-target expression module is closed");
        }
    }

    private void requireAot() {
        if (interpreted())
            throw new IllegalStateException("Interpreter module has no AOT artifact or resource");
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        if (interpreted())
            interpretedPrograms.close();
        else
            resource.close();
    }
}
