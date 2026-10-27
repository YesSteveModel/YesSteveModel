package com.elfmcys.ysm.expression.aot;

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
import dev.semaweave.ProgramId;
import dev.semaweave.ProgramKey;
import dev.semaweave.RegistryRuntimeLinkage;
import dev.semaweave.ResourceLease;
import dev.semaweave.RoamingPayload;
import dev.semaweave.RoamingRole;
import dev.semaweave.RuntimeDiagnostics;
import dev.semaweave.RuntimeLinkage;
import dev.semaweave.RuntimeSymbolTable;
import dev.semaweave.SymbolKey;
import dev.semaweave.compilation.BakedConstantTable;
import dev.semaweave.compilation.CompilationReport;
import dev.semaweave.compilation.ProgramRef;
import dev.semaweave.compilation.TransientAccessRequest;
import dev.semaweave.compilation.TransientLayout;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Target-generation owner for one verified resource and its immutable program bindings. */
public final class RenderTargetExpressionModule implements AutoCloseable {
    private static final AtomicLong NEXT_GENERATION_ID = new AtomicLong();

    private final BakedAnimationSet animations;
    private final ExpressionArtifactIdentity identity;
    private final CompiledModuleResource<MolangContext> resource;
    private final ExpressionHost host;
    private final BakedConstantTable constants;
    private final CompilationReport compilationReport;
    private final Map<ProgramKey, ModelProgramInventory.SemanticLocation> programLocations;
    private final ExpressionBinding expressionBinding;
    private final Map<ModelProgramInventory.SemanticLocation, ProgramRef> programs;
    private final Map<SymbolKey, ProgramKey> userFunctions;
    private final ExpressionArtifactStore.Source source;
    private final MolangEventCatalog eventCatalog;
    private final java.util.Set<String> roamingFields;
    private final HostLinkage productionLinkage;
    private final long generationId;
    private boolean closed;

    RenderTargetExpressionModule(ExpressionArtifactIdentity identity, CompiledModuleResource<MolangContext> resource,
        ExpressionHost host, BakedConstantTable constants, CompilationReport compilationReport,
        Map<ProgramKey, ModelProgramInventory.SemanticLocation> programLocations,
        Map<ModelProgramInventory.SemanticLocation, ProgramRef> programs, Map<SymbolKey, ProgramKey> userFunctions,
        ExpressionArtifactStore.Source source, MolangEventCatalog eventCatalog, HostLinkage productionLinkage) {
        this(identity, resource, host, constants, compilationReport, programLocations, programs, userFunctions, source,
            eventCatalog, productionLinkage, identity.expressionBinding());
    }

    RenderTargetExpressionModule(ExpressionArtifactIdentity identity, CompiledModuleResource<MolangContext> resource,
        ExpressionHost host, BakedConstantTable constants, CompilationReport compilationReport,
        Map<ProgramKey, ModelProgramInventory.SemanticLocation> programLocations,
        Map<ModelProgramInventory.SemanticLocation, ProgramRef> programs, Map<SymbolKey, ProgramKey> userFunctions,
        ExpressionArtifactStore.Source source, MolangEventCatalog eventCatalog, HostLinkage productionLinkage,
        ExpressionBinding expressionBinding) {
        this(identity, resource, host, constants, compilationReport, programLocations, programs, userFunctions, source,
            eventCatalog, productionLinkage, expressionBinding, BakedAnimationSet.empty());
    }

    RenderTargetExpressionModule(ExpressionArtifactIdentity identity, CompiledModuleResource<MolangContext> resource,
        ExpressionHost host, BakedConstantTable constants, CompilationReport compilationReport,
        Map<ProgramKey, ModelProgramInventory.SemanticLocation> programLocations,
        Map<ModelProgramInventory.SemanticLocation, ProgramRef> programs, Map<SymbolKey, ProgramKey> userFunctions,
        ExpressionArtifactStore.Source source, MolangEventCatalog eventCatalog, HostLinkage productionLinkage,
        ExpressionBinding expressionBinding, BakedAnimationSet animations) {
        this.animations = Objects.requireNonNull(animations, "animations");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.resource = Objects.requireNonNull(resource, "resource");
        this.host = Objects.requireNonNull(host, "host");
        this.constants = Objects.requireNonNull(constants, "constants");
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
        this.roamingFields = java.util.Set.copyOf(resource.newRoamingState(RoamingRole.CONSUMER).fields());
        this.productionLinkage = Objects.requireNonNull(productionLinkage, "productionLinkage");
        this.generationId = NEXT_GENERATION_ID.incrementAndGet();
    }

    public ExpressionArtifactIdentity identity() {
        return identity;
    }

    public ExpressionArtifactStore.Source source() {
        return source;
    }

    public MolangEventCatalog eventCatalog() {
        return eventCatalog;
    }

    public SharedExpressionSemantics sharedSemantics(com.elfmcys.ysm.model.domain.Hash256 consumerModelId) {
        return SharedExpressionSemantics.create(consumerModelId, identity.targetId(), identity.configFingerprint(),
            eventCatalog.fingerprint());
    }

    public java.util.Set<String> roamingFields() {
        return roamingFields;
    }

    public HostLinkage productionLinkage() {
        return productionLinkage;
    }

    public CompilationReport compilationReport() {
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
            try (var ignored = newInstance(holder)) {
                // Construction proves the generated root can bind this exact object graph.
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
        return requireProgram(new ModelProgramInventory.SemanticLocation(identity.targetId(), identity.set(), location,
            role, discriminator));
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

    public synchronized void validateRoamingFull(RoamingPayload payload) {
        requireOpen();
        resource.newRoamingState(RoamingRole.CONSUMER).validateFull(Objects.requireNonNull(payload, "payload"));
    }

    public synchronized void validateRoamingPatch(RoamingPayload payload) {
        requireOpen();
        resource.newRoamingState(RoamingRole.CONSUMER).validatePatch(Objects.requireNonNull(payload, "payload"));
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

    BakedConstantTable constants() {
        return constants;
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
            public dev.semaweave.ContextType<MolangContext> contextType() {
                return host.contextType();
            }

            @Override
            public RegistryRuntimeLinkage<MolangContext> registry() {
                return registry;
            }

            @Override
            public BakedConstantTable constants() {
                return RenderTargetExpressionModule.this.constants;
            }

            @Override
            public RuntimeDiagnostics diagnostics() {
                return RuntimeDiagnostics.none();
            }

            @Override
            public ResourceLease<MolangContext> retainResource() {
                return resource.retain();
            }
        };
    }

    public AnimationStore animations(Iterable<String> files, AnimationStore fallback) {
        requireOpen();
        return animations.bind(files, this, fallback);
    }

    private ModelProgramInventory.SemanticLocation animationLocation(int id, ProgramRole role) {
        requireOpen();
        var programId = new ProgramId(id);
        if (id >= resource.programs().size()) {
            throw new IllegalArgumentException("Invalid baked animation program id: " + id);
        }
        var descriptor = resource.programs().descriptor(programId);
        var location = programLocations.get(descriptor.key());
        if (location == null || location.role() != role) {
            throw new IllegalArgumentException("Invalid baked animation program id: " + id);
        }
        return location;
    }

    public FloatExpression.Program requireFloatProgram(int id) {
        return requireFloatProgram(animationLocation(id, ProgramRole.ANIMATION_SAMPLE));
    }

    public VoidProgram requireVoidProgram(int id) {
        return requireVoidProgram(animationLocation(id, ProgramRole.ANIMATION_INSTRUCTION));
    }

    public FloatExpression.Program requireFloatProgram(ModelProgramInventory.SemanticLocation location) {
        return new FloatExpression.Program(location, requireProgram(location), generation());
    }

    public BooleanProgram requireBooleanProgram(ModelProgramInventory.SemanticLocation location) {
        return new BooleanProgram(location, requireProgram(location), generation());
    }

    public VoidProgram requireVoidProgram(ModelProgramInventory.SemanticLocation location) {
        return new VoidProgram(location, requireProgram(location), generation());
    }

    public EventProgram requireEventProgram(ModelProgramInventory.SemanticLocation location) {
        return location.role().shape() == dev.semaweave.ProgramShape.F32 ? requireFloatProgram(location)
            : requireVoidProgram(location);
    }

    public UserFunctionProgram requireUserFunctionProgram(ModelProgramInventory.SemanticLocation location) {
        return new UserFunctionProgram(location, requireProgram(location), generation());
    }

    public dev.semaweave.compilation.ResourceGenerationToken generation() {
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

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        resource.close();
    }
}
