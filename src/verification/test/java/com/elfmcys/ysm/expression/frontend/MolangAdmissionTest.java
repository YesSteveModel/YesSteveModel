package com.elfmcys.ysm.expression.frontend;

import dev.semaweave.*;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.*;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.*;
import dev.semaweave.registry.ExpressionRegistry;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MolangAdmissionTest {
    @Test
    void sourceLoopsRoundAndCapIndependentlyAcrossPersistentTransientAndWarm() throws Throwable {
        var hash = ExpressionIdentityHash.SHA_256;
        var registry =
            ExpressionRegistry.builder(ContextTypes.create("test:loops", 1, Object.class, hash), hash).freeze();
        var host = new HostAbi("test:loops", "1", Fingerprint.compute(hash, ReadOnlyBytes.copyOf(new byte[] {2})));
        String[] counts = {"-1", "0", "0.49", "0.5", "1.49", "1.5", "1024", "1025"};
        float[] iterations = {0, 0, 0, 1, 1, 2, 1024, 1024};
        var sources = new java.util.ArrayList<String>();
        var oracles = new java.util.ArrayList<Float>();
        for (int index = 0; index < counts.length; index++) {
            sources.add("t.sum=0;loop(" + counts[index] + ",{t.sum=t.sum+1;});return t.sum+100;");
            oracles.add(iterations[index] + 100);
        }
        sources.add("t.sum=0;" + "loop(1024,{t.sum=t.sum+1;});".repeat(11) + "return t.sum;");
        oracles.add(11264f);
        sources.add("t.sum=0;loop(1024,{loop(1024,{t.sum=t.sum+1;});});return t.sum;");
        oracles.add(1048576f);
        sources.add("t.sum=0;t.count=1.5;loop(t.count,{t.sum=t.sum+1;t.count=0;});return t.sum;");
        oracles.add(2f);
        var programs = new java.util.ArrayList<EncodedProgram>();
        var builder = CompilationUnit.builder(CompilationPolicy.DEFAULT, PortableProgramCodecs.v1(), hash);
        for (int index = 0; index < sources.size(); index++) {
            var lowered = new MolangFrontend().compile(sources.get(index), MolangFrontend.SourceMode.EXPRESSION,
                new MolangFrontend.SourceLocation("loops/" + index));
            programs.add(lowered.program());
            builder.addProgram(
                new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {(byte)index})), lowered.program(),
                    new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(),
                        List.of(new LocalName("sum"), new LocalName("count"))),
                    Optional.empty(), new DiagnosticLocation("loops/" + index)));
        }
        var unit = builder.build();
        var backend = new JaninoJavaCompilerBackend();
        var artifact = new DefaultExpressionAotCompiler<Object>(backend).compile(TaskContext.NONE, unit, host, registry,
            CompilerOptions.DEFAULT, hash);
        var expected =
            ExpectedArtifactIdentity.create(unit, host, registry, CompilerOptions.DEFAULT, backend.descriptor(), hash);
        var encoded = ArtifactCodecs.v1().encode(artifact, hash);
        for (var candidate : List.of(artifact, ArtifactCodecs.v1().decodeAndVerify(encoded, expected, hash))) {
            try (var resource =
                ModuleLoaders.<Object>standard().load(candidate, expected, registry, registry.visibilityRoot(), hash);
                var instance = resource.newInstance(new RuntimeLinkage<Object>() {
                    public RegistryRuntimeLinkage<Object> registry() {
                        return RegistryRuntimeLinkage.create(ProviderInstanceTable.create(registry, Map.of()),
                            RuntimeSymbolTable.empty());
                    }

                    public RuntimeDiagnostics diagnostics() {
                        return RuntimeDiagnostics.none();
                    }
                })) {
                var none = TransientDynamicAccess.none(hash);
                var layout = resource.createTransientLayout(new TransientAccessRequest(
                    new java.util.TreeSet<LocalName>(java.util.Comparator.comparing(LocalName::name)),
                    new java.util.TreeSet<LocalName>(java.util.Comparator.comparing(LocalName::name)),
                    new java.util.TreeSet<ProgramKey>(), none.descriptor(), CompilationPolicy.DEFAULT));
                for (int index = 0; index < sources.size(); index++) {
                    assertEquals(oracles.get(index),
                        instance.executeFloat(instance.bindProgram(new ProgramId(index), ProgramShape.F32),
                            new Object(), instance.bindSubject(null), new Frame()));
                    try (
                        var lease = TransientCompilers.<Object>standard(backend).compile(TaskContext.NONE,
                            programs.get(index), ProgramShape.F32, layout, TransientPurpose.ONE_SHOT, hash);
                        var access = instance.openTransientAccess(layout, none)) {
                        assertEquals(oracles.get(index),
                            lease.program().executeFloat(access, new Object(), access.bindSubject(null), new Frame()));
                    }
                }
            }
        }
    }

    @Test
    void formerSourceAndNameBoundsReachColdCompilationArtifactRestoreAndEvaluation() throws Throwable {
        var hash = ExpressionIdentityHash.SHA_256;
        var registry =
            ExpressionRegistry.builder(ContextTypes.create("test:admission", 1, Object.class, hash), hash).freeze();
        var host = new HostAbi("test:admission", "1", Fingerprint.compute(hash, ReadOnlyBytes.copyOf(new byte[] {1})));
        var name = "a".repeat(1_025);
        var sources = List.of(" ".repeat(1_048_577) + "7", "t." + name + "=7;return t." + name + ";",
            "+".repeat(257) + "7", "fn.tick();return 7;");
        var base = CompilationPolicy.DEFAULT;
        var policy = new CompilationPolicy(base.missingSymbols(), base.semanticFailure(),
            ArityMismatchPolicy.UNSET_CALL, base.numericPolicy(), base.executionLimits(), base.objectLanguageRules());
        for (var source : sources) {
            var location = new MolangFrontend.SourceLocation("location/" + "a".repeat(1_025));
            var lowered = new MolangFrontend().compile(source, MolangFrontend.SourceMode.EXPRESSION, location);
            var unit = CompilationUnit.builder(policy, PortableProgramCodecs.v1(), hash)
                .addProgram(new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[1_025])), lowered.program(),
                    new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(),
                        List.of(new LocalName(name))),
                    Optional.empty(), new DiagnosticLocation(location.value())))
                .addProgram(new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {1})),
                    PortableProgramCodecs.v1().canonicalZero(),
                    new UserFunctionEntrySignature(new SymbolKey(MolangNames.userFunctionSymbol("tick")),
                        new EntryResult.Value(BuiltinType.F32),
                        List.of(new RequiredParameter(new LocalName("x"), BuiltinType.F32),
                            new VariadicParameter(new LocalName("tail"), BuiltinType.F32)),
                        List.of()),
                    Optional.empty(), new DiagnosticLocation("tick")))
                .build();
            var backend = new JaninoJavaCompilerBackend();
            var artifact = new DefaultExpressionAotCompiler<Object>(backend).compile(TaskContext.NONE, unit, host,
                registry, CompilerOptions.DEFAULT, hash);
            var expected = ExpectedArtifactIdentity.create(unit, host, registry, CompilerOptions.DEFAULT,
                backend.descriptor(), hash);
            var bytes = ArtifactCodecs.v1().encode(artifact, hash);
            var restored = ArtifactCodecs.v1().decodeAndVerify(bytes, expected, hash);
            if (source.startsWith("fn.tick"))
                assertEquals(Integer.MAX_VALUE, restored.report().localRecoveries().get(0).maximumArguments());
            try (var resource =
                ModuleLoaders.<Object>standard().load(restored, expected, registry, registry.visibilityRoot(), hash);
                var instance = resource.newInstance(new RuntimeLinkage<Object>() {
                    public RegistryRuntimeLinkage<Object> registry() {
                        return RegistryRuntimeLinkage.create(ProviderInstanceTable.create(registry, Map.of()),
                            RuntimeSymbolTable.empty());
                    }

                    public RuntimeDiagnostics diagnostics() {
                        return RuntimeDiagnostics.none();
                    }
                })) {
                var program = instance.bindProgram(new ProgramId(0), ProgramShape.F32);
                assertEquals(7f, instance.executeFloat(program, new Object(), instance.bindSubject(null), new Frame()));
            }
        }
    }

    @Test
    void cancellationDuringLongParsingNeverPublishesAFrontendResult() {
        var checks = new AtomicInteger();
        var publications = new AtomicInteger();
        var frontend = new MolangFrontend((source, mode, location, result) -> publications.incrementAndGet());
        assertThrows(CancellationException.class,
            () -> frontend.compile("(".repeat(2_048) + "7" + ")".repeat(2_048), MolangFrontend.SourceMode.EXPRESSION,
                new MolangFrontend.SourceLocation("cancel"), () -> checks.incrementAndGet() == 100));
        assertEquals(100, checks.get());
        assertEquals(0, publications.get());
    }

    private static final class Frame implements ExecutionFrame {
        public boolean actionAllowed() {
            return true;
        }

        public void beginTopLevel(boolean allowAction) {}

        public void endTopLevel() {}

        public void enterUserFunction() {}

        public void exitUserFunction() {}

    }
}
