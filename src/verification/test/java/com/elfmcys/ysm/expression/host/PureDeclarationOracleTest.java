package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import dev.semaweave.*;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.*;
import dev.semaweave.program.*;
import java.util.*;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Stateful probes observe calls only; they do not authorize a production implementation as pure. */
public final class PureDeclarationOracleTest {
    private static int targets, arguments;
    private static final IllegalStateException FAILURE = new IllegalStateException("pure target probe");

    public static float declaredAffine(MolangContext context, float value) { throw new AssertionError("context adapter"); }
    public static float declaredNonfinite(MolangContext context, float value) { throw new AssertionError("context adapter"); }
    public static float declaredExplode(MolangContext context, float value) { throw new AssertionError("context adapter"); }
    public static float affine(float value) { targets++; return 2 * value + 1; }
    public static float nonfinite(float value) { targets++; return Float.NaN; }
    public static float explode(float value) { targets++; throw FAILURE; }
    public static float argument(MolangContext context) { arguments++; return 3; }

    @Test
    void declarationLocalTargetsKeepPhysicalCallsGuardsBudgetsAndFailures() throws Throwable {
        ComponentScopeTest.initializeMinecraftEntityClasses();
        var entity = (Entity)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe()
            .allocateInstance(net.minecraft.world.entity.decoration.ArmorStand.class);
        var other = (Entity)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe()
            .allocateInstance(net.minecraft.world.entity.projectile.Snowball.class);
        var host = host();
        var hash = ExpressionIdentityHash.SHA_256;
        var codec = PortableProgramCodecs.v1();
        var builder = CompilationUnit.builder(host.compilationPolicy(), codec, hash);
        String[] expressions = {"freshpure.affine(freshpure.argument())", "freshpure.nonfinite(3) ?? 9",
            "freshpure.explode(3)"};
        var keys = new ProgramKey[expressions.length];
        for (int i = 0; i < expressions.length; i++) {
            keys[i] = new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {(byte)i}));
            var portable = new MolangFrontend().compile(expressions[i], MolangFrontend.SourceMode.EXPRESSION,
                new MolangFrontend.SourceLocation("pure-oracle/" + i)).program();
            builder.addProgram(new ProgramSource(keys[i], portable,
                new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(), List.of()),
                Optional.empty(), new DiagnosticLocation("pure-oracle/" + i)));
        }
        var unit = builder.build();
        var backend = new JaninoJavaCompilerBackend();
        var plan = host.expressionBinding().bindingPlan();
        var artifact = new DefaultExpressionAotCompiler<MolangContext>(backend).compile(TaskContext.NONE, unit,
            host.hostAbi(), host.registry(), CompilerOptions.DEFAULT, plan, hash);
        var calls = new ArrayList<String>();
        for (var bytes : artifact.classes().values()) {
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(bytes.toByteArray()).accept(node, 0);
            for (var method : node.methods)
                for (var instruction : method.instructions)
                    if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)
                        calls.add(call.owner + "." + call.name + call.desc);
        }
        String owner = PureDeclarationOracleTest.class.getName().replace('.', '/');
        assertTrue(calls.contains(owner + ".affine(F)F"), calls.toString());
        assertFalse(calls.stream().anyMatch(c -> c.startsWith(owner + ".declared")));
        var expected = ExpectedArtifactIdentity.create(unit, host.hostAbi(), host.registry(), CompilerOptions.DEFAULT,
            plan, backend.descriptor(), hash);
        try (var resource = ModuleLoaders.<MolangContext>standard().load(artifact, expected, host.registry(),
            host.registry().visibilityRoot(), hash)) {
            var registry = RegistryRuntimeLinkage.<MolangContext>create(
                ProviderInstanceTable.create(host.registry(), plan, Map.of()), RuntimeSymbolTable.empty());
            try (var instance = resource.newInstance(new RuntimeLinkage<MolangContext>() {
                public ContextType<MolangContext> contextType() { return host.contextType(); }
                public RegistryRuntimeLinkage<MolangContext> registry() { return registry; }
                public BakedConstantTable constants() { return artifact.constants(); }
                public RuntimeDiagnostics diagnostics() { return RuntimeDiagnostics.none(); }
                public ResourceLease<MolangContext> retainResource() { return resource.retain(); }
            })) {
                for (int scenario = 0; scenario < 5; scenario++) {
                    targets = arguments = 0;
                    var subject = scenario == 1 ? other : entity;
                    var roaming = resource.newRoamingState(RoamingRole.PRODUCER);
                    var inputs = new InvocationInputs<Entity>() { public Entity entity() { return subject; } };
                    var context = MolangContext.observation(inputs, inputs, inputs, roaming,
                        new InvocationProvenance("owner", "pure-oracle/" + scenario, scenario, 1, 1),
                        () -> false, () -> true);
                    context.configureLimits(host.executionLimits());
                    if (scenario == 2) {
                        var limits = host.executionLimits();
                        context.configureLimits(new ExecutionLimits(limits.maxLoopIterations(), limits.maxUserFunctionDepth(),
                            1, limits.maxControlTransfers(), limits.maxArgumentScratch()));
                    }
                    int program = scenario < 3 ? 0 : scenario - 2;
                    var id = resource.programs().requireId(keys[program]);
                    context.bindExpression("pure-oracle/" + scenario, id.value());
                    try {
                        var binding = instance.bindSubject(subject);
                        if (scenario == 2 || scenario == 4) {
                            var failure = assertThrows(Throwable.class, () -> instance.executeFloat(
                                instance.bindProgram(id, ProgramShape.F32), context, binding, context));
                            if (scenario == 4) {
                                while (failure != FAILURE && failure.getCause() != null) failure = failure.getCause();
                                assertSame(FAILURE, failure);
                            }
                        } else {
                            float result = instance.executeFloat(instance.bindProgram(id, ProgramShape.F32),
                                context, binding, context);
                            assertEquals(scenario == 0 ? 7f : scenario == 1 ? 0f : 9f, result);
                        }
                        assertEquals(scenario == 1 || scenario == 2 ? 0 : 1, targets);
                        assertEquals(scenario == 0 || scenario == 2 ? 1 : 0, arguments);
                    } finally { context.releaseExpression(); context.closeRound(); }
                }
            }
        }
    }

    private static ExpressionHost host() {
        var scalar = new DeclarationSchema.Value("FLOAT", "float", "", null, List.of());
        var schemas = new ArrayList<DeclarationSchema.Member>();
        var members = new ArrayList<LinkedDeclaration.Member>();
        for (String name : List.of("affine", "nonfinite", "explode", "argument")) {
            boolean argument = name.equals("argument");
            String declared = "declared" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            var schema = new DeclarationSchema.Member("freshpure", name, argument ? "argument" : declared,
                "(Lcom/elfmcys/ysm/api/molang/MolangContextView;" + (argument ? "" : "F") + ")F",
                "m" + schemas.size(), net.minecraft.world.entity.decoration.ArmorStand.class.getName(), true, argument ? "" : name, "CONTEXT",
                argument ? 0 : 1, argument ? 0 : 1, argument ? List.of() : List.of(scalar), List.of(), null, scalar, false);
            schemas.add(schema);
            members.add(new LinkedDeclaration.Member(schema, net.minecraft.world.entity.decoration.ArmorStand.class, argument
                ? LinkedDeclaration.binding(PureDeclarationOracleTest.class, "argument", float.class, MolangContext.class)
                : LinkedDeclaration.binding(PureDeclarationOracleTest.class, declared, float.class, MolangContext.class, float.class),
                argument ? Optional.empty() : Optional.of(LinkedDeclaration.binding(PureDeclarationOracleTest.class,
                    name, float.class, float.class))));
        }
        var schema = new DeclarationSchema(PureDeclarationOracleTest.class.getName(),
            PureDeclarationOracleTest.class.getName() + "MolangBindings", "freshpure", "", true, false, "", List.of(), schemas);
        var declaration = new LinkedDeclaration(schema, "ysm", PureDeclarationOracleTest.class,
            PureDeclarationOracleTest.class, PureDeclarationOracleTest.class.getClassLoader(), members);
        return ExpressionHost.createFrozen(HostAvailability.none(), ExpressionHost.create(HostAvailability.none()).compilationPolicy(),
            List.of(declaration), List.of(declaration));
    }
}
