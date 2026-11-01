package com.elfmcys.ysm.verification;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import com.elfmcys.ysm.expression.host.BuiltinDeclarations;
import com.elfmcys.ysm.expression.host.EntityView;
import com.elfmcys.ysm.expression.host.EffectValidator;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.expression.host.InvocationProvenance;
import com.elfmcys.ysm.expression.host.MolangExtensions;
import com.elfmcys.ysm.expression.host.RenderView;
import com.elfmcys.ysm.internal.expression.semaweave.ArgumentScratch;
import com.elfmcys.ysm.internal.expression.semaweave.BuiltinType;
import com.elfmcys.ysm.internal.expression.semaweave.CompilationPolicy;
import com.elfmcys.ysm.internal.expression.semaweave.CompiledRoamingState;
import com.elfmcys.ysm.internal.expression.semaweave.ContextTypes;
import com.elfmcys.ysm.internal.expression.semaweave.EntryResult;
import com.elfmcys.ysm.internal.expression.semaweave.ExecutionFrame;
import com.elfmcys.ysm.internal.expression.semaweave.HostAbi;
import com.elfmcys.ysm.internal.expression.semaweave.LocalName;
import com.elfmcys.ysm.internal.expression.semaweave.ProgramKey;
import com.elfmcys.ysm.internal.expression.semaweave.ProgramShape;
import com.elfmcys.ysm.internal.expression.semaweave.ProviderInstanceTable;
import com.elfmcys.ysm.internal.expression.semaweave.RegistryRuntimeLinkage;
import com.elfmcys.ysm.internal.expression.semaweave.ResourceLease;
import com.elfmcys.ysm.internal.expression.semaweave.RoamingRole;
import com.elfmcys.ysm.internal.expression.semaweave.RuntimeDiagnostics;
import com.elfmcys.ysm.internal.expression.semaweave.RuntimeLinkage;
import com.elfmcys.ysm.internal.expression.semaweave.RuntimeSymbolTable;
import com.elfmcys.ysm.internal.expression.semaweave.TopLevelEntrySignature;
import com.elfmcys.ysm.internal.expression.semaweave.backend.janino.JaninoJavaCompilerBackend;
import com.elfmcys.ysm.internal.expression.semaweave.compilation.ArtifactCodecs;
import com.elfmcys.ysm.internal.expression.semaweave.compilation.CompilationUnit;
import com.elfmcys.ysm.internal.expression.semaweave.compilation.CompilerOptions;
import com.elfmcys.ysm.internal.expression.semaweave.compilation.DefaultExpressionAotCompiler;
import com.elfmcys.ysm.internal.expression.semaweave.compilation.DiagnosticLocation;
import com.elfmcys.ysm.internal.expression.semaweave.compilation.ExpectedArtifactIdentity;
import com.elfmcys.ysm.internal.expression.semaweave.compilation.ModuleLoaders;
import com.elfmcys.ysm.internal.expression.semaweave.compilation.ProgramSource;
import com.elfmcys.ysm.internal.expression.semaweave.compilation.TaskContext;
import com.elfmcys.ysm.internal.expression.semaweave.identity.Fingerprint;
import com.elfmcys.ysm.internal.expression.semaweave.identity.Hash256;
import com.elfmcys.ysm.internal.expression.semaweave.identity.HashAlgorithmDescriptor;
import com.elfmcys.ysm.internal.expression.semaweave.identity.IdentityHashAlgorithm;
import com.elfmcys.ysm.internal.expression.semaweave.program.PortableProgramCodecs;
import com.elfmcys.ysm.internal.expression.semaweave.program.PortablePrograms;
import com.elfmcys.ysm.internal.expression.semaweave.program.ProgramNode;
import com.elfmcys.ysm.internal.expression.semaweave.program.ReadOnlyBytes;
import com.elfmcys.ysm.internal.expression.semaweave.registry.ExpressionRegistry;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class PackagedExpressionRuntimeSmoke {
    private PackagedExpressionRuntimeSmoke() {
    }

    public static void main(String[] args) throws Throwable {
        verifyRelocatedRuntime();
        verifyIndependentProvider();
        System.out.println("PACKAGED_EXPRESSION_RUNTIME_OK");
    }

    private static void verifyRelocatedRuntime() throws Throwable {
        var hash = Sha256.INSTANCE;
        var context = ContextTypes.create("ysm:packaged-smoke", 1, Object.class, hash);
        var registry = ExpressionRegistry.builder(context, hash).freeze();
        var host =
                new HostAbi("ysm:packaged-smoke", "1", Fingerprint.compute(hash, ReadOnlyBytes.copyOf(new byte[]{1})));
        var key = new ProgramKey(ReadOnlyBytes.copyOf(new byte[]{2}));
        var codec = PortableProgramCodecs.v1();
        var source = new ProgramSource(key,
                codec.encode(PortablePrograms.ofRoots(List.of(new ProgramNode.Binary(ProgramNode.BinaryOperator.ADD,
                        ProgramNode.F32.of(1.25f), ProgramNode.F32.of(2.0f))))),
                new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(), List.of()),
                Optional.empty(), new DiagnosticLocation("packaged-smoke"));
        var unit = CompilationUnit.builder(CompilationPolicy.DEFAULT, codec, hash).addProgram(source).build();
        var backend = new JaninoJavaCompilerBackend();
        var expected =
                ExpectedArtifactIdentity.create(unit, host, registry, CompilerOptions.DEFAULT, backend.descriptor(), hash);
        var cold = new DefaultExpressionAotCompiler<Object>(backend).compile(TaskContext.NONE, unit, host, registry,
                CompilerOptions.DEFAULT, hash);
        var manifest = cold.manifest();
        if (!manifest.generatedPackage().startsWith("com.elfmcys.ysm.internal.expression.semaweave.generated.")
                || !cold.classes().containsKey(manifest.rootBinaryName())
                || cold.classes().keySet().stream().anyMatch(name -> !name.startsWith(manifest.generatedPackage() + '.'))) {
            throw new AssertionError("generated classes do not follow the packaged runtime namespace: " + manifest);
        }
        var artifactBytes = ArtifactCodecs.v1().encode(cold, hash);
        var artifact = ArtifactCodecs.v1().decodeAndVerify(artifactBytes, expected, hash);
        try (var resource =
                     ModuleLoaders.<Object>standard().load(artifact, expected, registry, registry.visibilityRoot(), hash)) {
            var providers = ProviderInstanceTable.create(registry, Map.of());
            var registryLinkage = RegistryRuntimeLinkage.create(providers, RuntimeSymbolTable.empty());
            RuntimeLinkage<Object> linkage = new RuntimeLinkage<>() {
                @Override
                public com.elfmcys.ysm.internal.expression.semaweave.ContextType<Object> contextType() {
                    return registry.contextType();
                }

                @Override
                public RegistryRuntimeLinkage<Object> registry() {
                    return registryLinkage;
                }

                @Override
                public com.elfmcys.ysm.internal.expression.semaweave.compilation.BakedConstantTable constants() {
                    return artifact.constants();
                }

                @Override
                public RuntimeDiagnostics diagnostics() {
                    return RuntimeDiagnostics.none();
                }

                @Override
                public ResourceLease<Object> retainResource() {
                    return resource.retain();
                }
            };
            try (var instance = resource.newInstance(linkage)) {
                var value = instance.executeFloat(
                    resource.bindProgram(key, ProgramShape.F32), new Object(),
                        instance.bindSubject(null), new Frame());
                if (Float.floatToRawIntBits(value) != Float.floatToRawIntBits(3.25f)) {
                    throw new AssertionError("relocated runtime returned " + value);
                }
            }
        }
    }

    private static void verifyIndependentProvider() throws Throwable {
        String schemaResource = "META-INF/ysm/molang/independent.PackagedMolangProvider.bin";
        var origin = PackagedExpressionRuntimeSmoke.class.getClassLoader().getResource(schemaResource);
        if (origin == null || !origin.getProtocol().equals("jar"))
            throw new AssertionError("packaged provider safe resource is not in a JAR");
        var connection = (java.net.JarURLConnection) origin.openConnection();
        connection.setUseCaches(false);
        var providerFile = java.nio.file.Path.of(connection.getJarFileURL().toURI());
        var admission = MolangExtensions.admit(HostAvailability.none(),
                List.of(new MolangExtensions.Source("packaged-expression-provider-smoke.jar", Set.of("combat_bridge"),
                        List.of(new MolangExtensions.DeclarationRef("independent.PackagedMolangProvider", "weapon", "")),
                        PackagedExpressionRuntimeSmoke.class.getClassLoader(), providerFile)));
        if (!admission.rejectedMods().isEmpty()
                || !admission.diagnostics().stream().noneMatch(d -> d.contains("independent.PackagedMolangProvider"))) {
            throw new AssertionError("packaged provider admission failed: " + admission);
        }

        var host = admission.host();
        var declaration = host.declarations().stream()
                .filter(d -> d.schema().owner().equals("independent.PackagedMolangProvider")).findFirst().orElseThrow();
        if (!declaration.verifiedModId().equals("combat_bridge") || !declaration.schema().modId().isEmpty()
                || !declaration.schema().namespace().equals("weapon")
                || host.registry()
                .function(new com.elfmcys.ysm.internal.expression.semaweave.SymbolKey("weapon_actions:action"))
                .isEmpty())
            throw new AssertionError("namespace, source ownership or member override changed");
        var codec = PortableProgramCodecs.v1();
        var finiteKey = new ProgramKey(ReadOnlyBytes.copyOf(new byte[]{11}));
        var nonFiniteKey = new ProgramKey(ReadOnlyBytes.copyOf(new byte[]{12}));
        var tailKey = new ProgramKey(ReadOnlyBytes.copyOf(new byte[]{13}));
        var recordKey = new ProgramKey(ReadOnlyBytes.copyOf(new byte[]{14}));
        var pair = new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "weapon:pair"),
                ProgramNode.AccessForm.INVOKE, List.of());
        var finite = invokeSum(pair, ProgramNode.F32.of(3.0f), ProgramNode.F32.of(4.0f));
        var nonFinite = invokeSum(pair,
                new ProgramNode.Binary(ProgramNode.BinaryOperator.DIV, ProgramNode.F32.of(0.0f), ProgramNode.F32.of(0.0f)),
                ProgramNode.F32.of(99.0f));
        var signature = new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(),
                List.of(new LocalName("pair")));
        var unit = CompilationUnit.builder(host.compilationPolicy(), codec, ExpressionIdentityHash.SHA_256)
                .addProgram(new ProgramSource(finiteKey, codec.encode(PortablePrograms.ofRoots(List.of(finite))), signature,
                        Optional.empty(), new DiagnosticLocation("packaged-provider/finite")))
                .addProgram(new ProgramSource(nonFiniteKey, codec.encode(PortablePrograms.ofRoots(List.of(nonFinite))),
                        signature, Optional.empty(), new DiagnosticLocation("packaged-provider/non-finite")))
                .addProgram(new ProgramSource(tailKey, codec.encode(PortablePrograms.ofRoots(List.of(new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "weapon:sum"), ProgramNode.AccessForm.INVOKE, List.of(ProgramNode.F32.of(9), ProgramNode.F32.of(3), ProgramNode.F32.of(4)))))), signature, Optional.empty(), new DiagnosticLocation("packaged-provider/tail")))
                .addProgram(new ProgramSource(recordKey, codec.encode(PortablePrograms.ofRoots(List.of(new ProgramNode.Member(pair, "value")))), signature, Optional.empty(), new DiagnosticLocation("packaged-provider/record")))
                .build();
        var backend = new JaninoJavaCompilerBackend();
        var expressionBinding = host.expressionBinding();
        var bindingPlan = expressionBinding.bindingPlan();
        var expected = ExpectedArtifactIdentity.create(unit, host.hostAbi(), host.registry(), CompilerOptions.DEFAULT,
                bindingPlan, backend.descriptor(), ExpressionIdentityHash.SHA_256);
        var cold = new DefaultExpressionAotCompiler<MolangContext>(backend).compile(TaskContext.NONE, unit,
                host.hostAbi(), host.registry(), CompilerOptions.DEFAULT, bindingPlan, ExpressionIdentityHash.SHA_256);
        var encoded = ArtifactCodecs.v1().encode(cold, ExpressionIdentityHash.SHA_256);
        var artifact = ArtifactCodecs.v1().decodeAndVerify(encoded, expected, ExpressionIdentityHash.SHA_256);
        try (var resource = ModuleLoaders.<MolangContext>standard().load(artifact, expected, host.registry(),
                host.registry().visibilityRoot(), ExpressionIdentityHash.SHA_256)) {
            var subject = new net.minecraft.world.entity.Entity();
            var components = BuiltinDeclarations
                    .createComponents(new com.elfmcys.ysm.client.entity.AnimatableEntity<>(subject), host);
            var registryLinkage = RegistryRuntimeLinkage.<MolangContext>create(components.providers(host, bindingPlan),
                    RuntimeSymbolTable.empty());
            RuntimeLinkage<MolangContext> linkage = new RuntimeLinkage<>() {
                @Override
                public com.elfmcys.ysm.internal.expression.semaweave.ContextType<MolangContext> contextType() {
                    return host.contextType();
                }

                @Override
                public RegistryRuntimeLinkage<MolangContext> registry() {
                    return registryLinkage;
                }

                @Override
                public com.elfmcys.ysm.internal.expression.semaweave.compilation.BakedConstantTable constants() {
                    return artifact.constants();
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
            var roaming = resource.newRoamingState(RoamingRole.PRODUCER);
            var invocation = packagedInvocation(roaming, subject);
            try (var instance = resource.newInstance(linkage, roaming)) {
                invocation.configureLimits(host.executionLimits());
                var finiteId = resource.bindProgram(finiteKey, ProgramShape.F32);
                invocation.bindExpression("packaged-finite", finiteId.sourceId());
                float finiteValue;
                try {
                    finiteValue = instance.executeFloat(finiteId, invocation,
                            instance.bindSubject(MolangContext.subjectOrNull(invocation)), invocation);
                } finally {
                    invocation.releaseExpression();
                }
                if (Float.floatToRawIntBits(finiteValue) != Float.floatToRawIntBits(19.0f)) {
                    throw new AssertionError("packaged provider returned " + finiteValue);
                }
                if (Boolean.getBoolean("ysm.payloadAllocation")) {
                    var binding = instance.bindSubject(MolangContext.subjectOrNull(invocation));
                    for (var entry : Map.of("tail", tailKey, "record", recordKey, "tail-record", finiteKey).entrySet()) {
                        var id = resource.bindProgram(entry.getValue(), ProgramShape.F32);
                        float truth = entry.getKey().equals("record") ? 2 : 19;
                        String payloadLocation = "payload-" + entry.getKey();
                        measure(entry.getKey(), 20000, () -> {
                            invocation.bindExpression(payloadLocation, id.sourceId());
                            try {
                                float value = instance.executeFloat(id, invocation, binding, invocation);
                                if (value != truth) throw new AssertionError("payload oracle");
                                return value;
                            } finally {
                                invocation.releaseExpression();
                            }
                        });
                    }
                    measure("empty-round", 20000, () -> {
                        var round = packagedInvocation(roaming, subject);
                        round.configureLimits(host.executionLimits());
                        round.closeRound();
                        return 1;
                    });
                    var failureId = resource.bindProgram(nonFiniteKey, ProgramShape.F32);
                    measure("failure-round", 2000, () -> {
                        var round = packagedInvocation(roaming, subject);
                        round.configureLimits(host.executionLimits());
                        round.bindExpression("payload-failure", failureId.sourceId());
                        try {
                            instance.executeFloat(failureId, round, binding, round);
                            throw new AssertionError("failure expected");
                        } catch (RuntimeException expectedFailure) {
                            if (round.firstFailure() == null || !round.firstFailure().provenance().programLocation().equals("payload-failure"))
                                throw new AssertionError("failure snapshot missing");
                            allocationObject = round.firstFailure();
                            return 1;
                        } finally {
                            round.releaseExpression();
                            round.closeRound();
                        }
                    });
                    for (boolean deferred : new boolean[]{false, true}) {
                        measure(deferred ? "defer-round" : "effect-round", 20000, () -> {
                            var round = MolangContext.logical(new EntityView() {
                                public net.minecraft.world.entity.Entity entity() {
                                    return subject;
                                }
                            }, new RenderView() {
                            }, MolangContext.LogicalPhase.ACTION, (command, scope) -> {
                            }, roaming, new InvocationProvenance("payload", "effect", 0, 1, 1), () -> false, () -> true, 16);
                            round.configureLimits(host.executionLimits());
                            round.deferredSubmit((request, source) -> allocationObject = request);
                            round.bindExpression("payload-effect", 0);
                            round.beginTopLevel(true);
                            if (deferred)
                                round.submit(new com.elfmcys.ysm.expression.host.DeferredRequest(
                                    com.elfmcys.ysm.expression.host.EventArguments.deferredFloats(0, 1, 2)));
                            else {
                                var values = new float[] {1, 2, 3};
                                round.submit((PreparedEffect)() -> allocationObject = values);
                            }
                            round.endTopLevel();
                            round.releaseExpression();
                            com.elfmcys.ysm.expression.host.EffectTransaction.finish(round, (commands, source) -> {
                                if (commands.size() != (deferred ? 0 : 1)
                                    || !deferred && !source.programLocation().equals("payload-effect"))
                                    throw new AssertionError("effect snapshot");
                                if (!commands.isEmpty()) allocationObject = commands.get(0);
                                return () -> {
                                };
                            }, true);
                            round.closeRound();
                            return 1;
                        });
                    }
                    System.out.println("PAYLOAD_ALLOCATION_ORACLE_PASS");
                    java.nio.file.Path report = java.nio.file.Path.of(System.getProperty("ysm.payloadReport"));
                    java.nio.file.Files.createDirectories(report.getParent());
                    java.nio.file.Files.writeString(report, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(allocationRows));
                }
                boolean rejected = false;
                var nonFiniteId = resource.bindProgram(nonFiniteKey, ProgramShape.F32);
                invocation.bindExpression("packaged-nonfinite", nonFiniteId.sourceId());
                try {
                    instance.executeFloat(nonFiniteId, invocation,
                            instance.bindSubject(MolangContext.subjectOrNull(invocation)), invocation);
                } catch (RuntimeException failure) {
                    rejected = true;
                } finally {
                    invocation.releaseExpression();
                }
                if (!rejected || (!Boolean.getBoolean("ysm.payloadAllocation") && independent.PackagedMolangProvider.calls() != 1))
                    throw new AssertionError("non-finite boundary did not preserve execution failure");
            }
        }
    }

    private static final java.util.List<java.util.Map<String, Object>> allocationRows = new java.util.ArrayList<>();
    private static volatile Object allocationObject;
    private static volatile float allocationValue;

    @FunctionalInterface
    private interface AllocationOperation {
        float run() throws Throwable;
    }

    private static void measure(String name, int iterations, AllocationOperation operation) throws Throwable {
        for (int i = 0; i < iterations; i++) allocationValue = operation.run();
        var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long before = threads.getThreadAllocatedBytes(thread);
        for (int i = 0; i < iterations; i++) allocationValue = operation.run();
        long allocated = threads.getThreadAllocatedBytes(thread) - before;
        var row = java.util.Map.<String, Object>of("case", name, "iterations", iterations, "allocatedBytes", allocated, "bytesPerOperation", (double) allocated / iterations);
        allocationRows.add(row);
        System.out.println("PAYLOAD_ALLOCATION " + row);
    }

    private static ProgramNode invokeSum(ProgramNode pair, ProgramNode first, ProgramNode tail) {
        var saved = new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.LOCAL, "pair"),
                ProgramNode.AccessForm.READ, List.of());
        var base = new ProgramNode.Binary(ProgramNode.BinaryOperator.ADD, new ProgramNode.Member(saved, "base"),
                new ProgramNode.Member(saved, "value"));
        var call = new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "weapon:sum"),
                ProgramNode.AccessForm.INVOKE, List.of(base, first, tail));
        return new ProgramNode.Block(List.of(new ProgramNode.Assignment(saved, pair), call));
    }

    private static MolangContext packagedInvocation(CompiledRoamingState roaming,
                                                    net.minecraft.world.entity.Entity subject) {
        return MolangContext.observation(new EntityView() {
            @Override
            public net.minecraft.world.entity.Entity entity() {
                return subject;
            }
        }, new RenderView() {
        }, new EffectValidator() {
            @Override
            public void prevalidateEffect(com.elfmcys.ysm.api.molang.PreparedEffect command, MolangContext<?> scope) {
                throw new AssertionError("unexpected host effect " + command);
            }
        }, roaming, new InvocationProvenance("packaged", "provider", 0, 1, 1), () -> false, () -> true);
    }

    private enum Sha256 implements IdentityHashAlgorithm {
        INSTANCE;

        private static final HashAlgorithmDescriptor DESCRIPTOR =
                new HashAlgorithmDescriptor("ysm:packaged-smoke-sha256", 1);

        @Override
        public HashAlgorithmDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Hash256 hashCanonicalPreimage(ReadOnlyBytes canonicalPreimage) {
            try {
                return Hash256.copyOf(MessageDigest.getInstance("SHA-256").digest(canonicalPreimage.toByteArray()));
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    private static final class Frame implements ExecutionFrame {
        private boolean active;
        private boolean allowAction;

        @Override
        public boolean actionAllowed() {
            if (!active)
                throw new IllegalStateException("frame is not active");
            return allowAction;
        }

        @Override
        public void beginTopLevel(boolean allowAction) {
            if (active) {
                throw new IllegalStateException("frame is already active");
            }
            active = true;
            this.allowAction = allowAction;
        }

        @Override
        public void endTopLevel() {
            if (!active) {
                throw new IllegalStateException("frame is not active");
            }
            active = false;
            allowAction = false;
        }

        @Override
        public void chargeLoopIteration() {
        }

        @Override
        public void chargeProviderCall() {
        }

        @Override
        public void chargeControlTransfer() {
        }

        @Override
        public boolean tryEnterUserFunction(int programId) {
            return true;
        }

        @Override
        public void exitUserFunction(int programId) {
        }

        @Override
        public ArgumentScratch acquireArgumentScratch(int size) {
            throw new AssertionError("smoke program has no arguments");
        }

        @Override
        public void releaseArgumentScratch(ArgumentScratch scratch) {
            throw new AssertionError("smoke program has no arguments");
        }
    }
}
