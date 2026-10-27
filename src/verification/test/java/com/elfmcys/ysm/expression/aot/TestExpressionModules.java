package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.expression.host.HostLinkage;
import com.elfmcys.ysm.expression.host.TestComponentHolders;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import dev.semaweave.CompiledModuleInstance;
import dev.semaweave.CompiledModuleResource;
import dev.semaweave.ResourceLease;
import dev.semaweave.RuntimeLinkage;
import dev.semaweave.RegistryRuntimeLinkage;
import dev.semaweave.RuntimeDiagnostics;
import dev.semaweave.RuntimeSymbolTable;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.CompilationUnit;
import dev.semaweave.compilation.CompilerOptions;
import dev.semaweave.compilation.ExpectedArtifactIdentity;
import dev.semaweave.compilation.ProgramCatalog;
import dev.semaweave.compilation.TransientAccessException;
import dev.semaweave.compilation.TransientAccessRequest;
import dev.semaweave.compilation.TransientLayout;
import dev.semaweave.program.PortableProgramCodecs;

import java.util.Map;
import java.util.Set;
import java.util.List;

/** Test-only target resource that keeps unrelated lifecycle tests on the real target interface. */
public final class TestExpressionModules {
    static final ExpressionArtifactIdentity IDENTITY = identity(1);

    private TestExpressionModules() {}

    static dev.semaweave.compilation.ProgramRef reference(int id, dev.semaweave.ProgramShape shape,
        dev.semaweave.compilation.ResourceGenerationToken generation) {
        try {
            var constructor = dev.semaweave.compilation.ProgramRef.class.getDeclaredConstructor(int.class, int.class,
                dev.semaweave.ProgramShape.class, dev.semaweave.compilation.ResourceGenerationToken.class);
            constructor.setAccessible(true);
            return constructor.newInstance(id, id, shape, generation);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }}

    public static Hash256 consumerId() {
        var bytes = new byte[32];
        bytes[0] = 1;
        return new Hash256(bytes);
    }

    public static RenderTargetExpressionModule create() {
        return create(null);
    }

    public static RenderTargetExpressionModule create(Set<String> roamingNames) {
        return create(IDENTITY, roamingNames, Map.of());
    }

    public static void replace(EntityExpressionRuntime runtime, RenderTargetExpressionModule module,
        Map<String, ? extends List<? extends EventProgram>> handlers) {
        runtime.replace(module, handlers, linkage(module));
    }

    public static void replace(EntityExpressionRuntime runtime, RenderTargetExpressionModule module,
        Map<String, ? extends List<? extends EventProgram>> handlers, dev.semaweave.RoamingRole role) {
        runtime.replace(module, handlers, linkage(module), role);
    }

    static RuntimeLinkage<MolangContext> linkage(RenderTargetExpressionModule module) {
        var host = module.host();
        var registry = RegistryRuntimeLinkage.<MolangContext>create(
            TestComponentHolders.providers(host, module.expressionBinding().bindingPlan()), RuntimeSymbolTable.empty());
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
            public dev.semaweave.compilation.BakedConstantTable constants() {
                return module.constants();
            }

            @Override
            public RuntimeDiagnostics diagnostics() {
                return RuntimeDiagnostics.none();
            }

            @Override
            public ResourceLease<MolangContext> retainResource() {
                return module.retain();
            }
        };
    }

    public static RenderTargetExpressionModule createLocalVariant(int localSalt, int syncProgramId) {
        return createLocalVariant(localSalt, syncProgramId, defaultSettings());
    }

    public static RenderTargetExpressionModule createLocalVariant(int localSalt, int syncProgramId,
        SettingsProto settings) {
        if (localSalt < 0 || localSalt > 255 || syncProgramId < 0) {
            throw new IllegalArgumentException("local test identities must be non-negative bytes/ids");
        }
        var identity = identity(localSalt, settings);
        var location = new com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation("test",
            "function/@sync", com.elfmcys.ysm.expression.inventory.ProgramRole.EVENT_SYNC, "sync:0");
        var program =
            reference(syncProgramId,
            com.elfmcys.ysm.expression.inventory.ProgramRole.EVENT_SYNC.shape(), null);
        return create(identity, null, Map.of(location, program));
    }

    private static RenderTargetExpressionModule create(ExpressionArtifactIdentity identity, Set<String> roamingNames,
        Map<com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation,
            dev.semaweave.compilation.ProgramRef> programs) {
        var host = ExpressionHost.create(HostAvailability.none());
        return new RenderTargetExpressionModule(identity, new Resource(identity, roamingNames), host,
            new dev.semaweave.compilation.BakedConstantTable(java.util.List.of(),
                dev.semaweave.program.ReadOnlyBytes.copyOf(new byte[0])),
            emptyReport(), Map.of(), programs, Map.of(), ExpressionArtifactStore.Source.COLD,
            MolangEventCatalog.production(), HostLinkage.production(host));
    }

    static dev.semaweave.compilation.CompilationReport emptyReport() {
        return dev.semaweave.compilation.ReportTestFixtures.emptyReport();
    }

    private static ExpressionArtifactIdentity identity(int localSalt) {
        return identity(localSalt, defaultSettings());
    }

    private static ExpressionArtifactIdentity identity(int localSalt, SettingsProto settings) {
        try {
            var host = ExpressionHost.create(HostAvailability.none());
            var inventory = com.elfmcys.ysm.expression.inventory.ModelProgramInventory.collect("test",
                ModelDataProto.newBuilder().build(), null, null);
            var codec = PortableProgramCodecs.v1();
            var encoded = codec.encode(dev.semaweave.program.PortablePrograms
                .ofRoots(java.util.List.of(new dev.semaweave.program.ProgramNode.Unset())));
            var source = new dev.semaweave.compilation.ProgramSource(
                new dev.semaweave.ProgramKey(dev.semaweave.program.ReadOnlyBytes.copyOf(new byte[] {(byte)localSalt})),
                encoded,
                new dev.semaweave.TopLevelEntrySignature(dev.semaweave.ProgramShape.VOID,
                    new dev.semaweave.EntryResult.Void(), java.util.List.of(), java.util.List.of()),
                java.util.Optional.of(encoded), new dev.semaweave.compilation.DiagnosticLocation("test"));
            var unit = CompilationUnit.builder(host.compilationPolicy(), codec, ExpressionIdentityHash.SHA_256)
                .addProgram(source).build();
            var backend = new JaninoJavaCompilerBackend();
            var expressionBinding = host.expressionBinding();
            var expected =
                ExpectedArtifactIdentity.create(unit, host.hostAbi(), host.registry(), CompilerOptions.DEFAULT,
                    expressionBinding.bindingPlan(), backend.descriptor(), ExpressionIdentityHash.SHA_256);
            var bytes = new byte[Hash256.SIZE];
            bytes[0] = 1;
            return ExpressionArtifactIdentity.create(expected,
                new ModelFileIdentity(new Hash256(bytes), new Hash256(bytes)), "test", "test", new BakeProfile("test"),
                expressionBinding, false, com.elfmcys.ysm.util.ProtoUtil.serializeToArray(settings));
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static SettingsProto defaultSettings() {
        return SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("").setDisablePreviewRotation(false)
            .build();
    }

    private static final class Resource implements CompiledModuleResource<MolangContext> {
        private final ExpressionArtifactIdentity identity;
        private final Set<String> roamingNames;

        private Resource(ExpressionArtifactIdentity identity, Set<String> roamingNames) {
            this.identity = identity;
            this.roamingNames = roamingNames;
        }

        @Override
        public dev.semaweave.compilation.CompiledArtifactIdentity identity() {
            return identity.expected().identity();
        }

        @Override
        public dev.semaweave.SubjectBinding bindSubject(Object subject) {
            return new dev.semaweave.SubjectBinding() {
                public Object subject() {
                    return subject;
                }

                public dev.semaweave.compilation.ResourceGenerationToken generation() {
                    return Resource.this.generation();
                }
            };
        }

        @Override
        public dev.semaweave.compilation.ProgramRef bindProgram(dev.semaweave.ProgramId source,
            dev.semaweave.ProgramShape shape) {
            return reference(source.value(), shape, generation());
        }

        @Override
        public ProgramCatalog programs() {
            return null;
        }

        @Override
        public dev.semaweave.compilation.ResourceGenerationToken generation() {
            return null;
        }

        @Override
        public TransientLayout<MolangContext> createTransientLayout(TransientAccessRequest request)
            throws TransientAccessException {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResourceLease<MolangContext> retain() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.semaweave.CompiledRoamingState newRoamingState(dev.semaweave.RoamingRole role) {
            return roamingNames == null ? com.elfmcys.ysm.expression.TestRoamingStates.create(generation(), role)
                : com.elfmcys.ysm.expression.TestRoamingStates.create(generation(), role, roamingNames);
        }

        @Override
        public CompiledModuleInstance<MolangContext> newInstance(RuntimeLinkage<MolangContext> linkage) {
            return instance();
        }

        @Override
        public CompiledModuleInstance<MolangContext> newInstance(RuntimeLinkage<MolangContext> linkage,
            dev.semaweave.CompiledRoamingState roamingState) {
            return instance();
        }

        @Override
        public void close() {}

        private CompiledModuleInstance<MolangContext> instance() {
            return new CompiledModuleInstance<>() {
                @Override
                public dev.semaweave.compilation.ProgramRef bindProgram(dev.semaweave.ProgramId source,
                    dev.semaweave.ProgramShape shape) {
                    return Resource.this.bindProgram(source, shape);
                }

                @Override
                public dev.semaweave.SubjectBinding bindSubject(Object subject) {
                    return Resource.this.bindSubject(subject);
                }

                @Override
                public dev.semaweave.compilation.CompiledArtifactIdentity identity() {
                    return Resource.this.identity();
                }

                @Override
                public dev.semaweave.compilation.TransientExecutionAccess<MolangContext> openTransientAccess(
                    TransientLayout<MolangContext> layout,
                    dev.semaweave.compilation.TransientDynamicAccess dynamicAccess) throws TransientAccessException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Object executeValue(dev.semaweave.compilation.ProgramRef id, MolangContext context,
                    dev.semaweave.SubjectBinding subjectBinding, dev.semaweave.ExecutionFrame frame,
                    dev.semaweave.ProgramArguments arguments) {
                    return dev.semaweave.ExpressionValues.UNSET;
                }

                @Override
                public float executeFloat(dev.semaweave.compilation.ProgramRef id, MolangContext context,
                    dev.semaweave.SubjectBinding subjectBinding, dev.semaweave.ExecutionFrame frame,
                    dev.semaweave.ProgramArguments arguments) {
                    return 0;
                }

                @Override
                public boolean executeBoolean(dev.semaweave.compilation.ProgramRef id, MolangContext context,
                    dev.semaweave.SubjectBinding subjectBinding, dev.semaweave.ExecutionFrame frame,
                    dev.semaweave.ProgramArguments arguments) {
                    return false;
                }

                @Override
                public void executeVoid(dev.semaweave.compilation.ProgramRef id, MolangContext context,
                    dev.semaweave.SubjectBinding subjectBinding, dev.semaweave.ExecutionFrame frame,
                    dev.semaweave.ProgramArguments arguments) {}

                @Override
                public void close() {}
            };
        }
    }
}
