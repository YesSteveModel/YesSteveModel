package com.elfmcys.ysm.expression.frontend;

import dev.semaweave.*;
import dev.semaweave.compilation.*;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.*;
import dev.semaweave.registry.*;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MolangFrontendTest {
    private static final MolangFrontend.SourceLocation LOCATION = new MolangFrontend.SourceLocation("test/expression");

    @Test
    void groupsAllExistingOperatorsWithIndependentAstOracles() throws Exception {
        var a = query("a");
        var b = query("b");
        var c = query("c");
        var d = query("d");
        var e = query("e");
        var ref = query("ref");
        var x = query("x");
        var y = query("y");
        var cases =
            new Object[][] {{"q.ref->q.x+q.y", binary(ProgramNode.BinaryOperator.ADD, arrow(ref, x), y)},
                {"q.ref->q.x??7", new ProgramNode.Coalesce(arrow(ref, x), number(7))},
                {"-q.ref->q.x", unary(ProgramNode.UnaryOperator.NEGATE, arrow(ref, x))},
                {"!q.ref->q.x", unary(ProgramNode.UnaryOperator.LOGICAL_NOT, arrow(ref, x))},
                {"q.ref->-q.x", arrow(ref, unary(ProgramNode.UnaryOperator.NEGATE, x))},
                {"q.a->q.b->q.x", arrow(arrow(a, b), x)},
                {"q.a->{q.b->q.x;}", arrow(a, new ProgramNode.Block(List.of(arrow(b, x))))},
                {"q.ref->(q.x+q.y)", arrow(ref, binary(ProgramNode.BinaryOperator.ADD, x, y))},
                {"q.ref->q.f().x[1]",
                    arrow(ref,
                        new ProgramNode.Index(new ProgramNode.Member(new ProgramNode.Symbol(
                            new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "query:f"),
                            ProgramNode.AccessForm.INVOKE, List.of()), "x"), number(1)))},
                {"-q.a*2",
                    binary(ProgramNode.BinaryOperator.MUL, unary(ProgramNode.UnaryOperator.NEGATE, a), number(2))},
                {"1+2*3",
                    binary(ProgramNode.BinaryOperator.ADD, number(1),
                        binary(ProgramNode.BinaryOperator.MUL, number(2), number(3)))},
                {"1<2+3",
                    binary(ProgramNode.BinaryOperator.LT, number(1),
                        binary(ProgramNode.BinaryOperator.ADD, number(2), number(3)))},
                {"1==2<3",
                    binary(ProgramNode.BinaryOperator.EQ, number(1),
                        binary(ProgramNode.BinaryOperator.LT, number(2), number(3)))},
                {"1&&2==3",
                    binary(ProgramNode.BinaryOperator.AND, number(1),
                        binary(ProgramNode.BinaryOperator.EQ, number(2), number(3)))},
                {"1||2&&3",
                    binary(ProgramNode.BinaryOperator.OR, number(1),
                        binary(ProgramNode.BinaryOperator.AND, number(2), number(3)))},
                {"1||0?2:3",
                    conditional(binary(ProgramNode.BinaryOperator.OR, number(1), number(0)), number(2), number(3))},
                {"0??1?2:3", new ProgramNode.Coalesce(number(0), conditional(number(1), number(2), number(3)))}, {
                    "1?null:2??7",
                    new ProgramNode.Coalesce(conditional(number(1), new ProgramNode.Unset(), number(2)), number(7))},
                {"q.a??q.b??q.c", new ProgramNode.Coalesce(new ProgramNode.Coalesce(a, b), c)},
                {"v.a=1??2", new ProgramNode.Assignment(variable("a"), new ProgramNode.Coalesce(number(1), number(2)))},
                {"v.a=(v.b=3)",
                    new ProgramNode.Assignment(variable("a"), new ProgramNode.Assignment(variable("b"), number(3)))},
                {"1?(v.x=2):3",
                    conditional(number(1), new ProgramNode.Assignment(variable("x"), number(2)), number(3))},
                {"1?{v.x=2;null??4;}:3",
                    conditional(number(1),
                        new ProgramNode.Block(List.of(new ProgramNode.Assignment(variable("x"), number(2)),
                            new ProgramNode.Coalesce(new ProgramNode.Unset(), number(4)))),
                        number(3))},
                {"q.a?q.b?q.c:q.d:q.e", conditional(a, conditional(b, c, d), e)},
                {"q.a?q.b:q.c?q.d:q.e", conditional(a, b, conditional(c, d, e))},
                {"q.a?q.b?q.c", conditional(a, conditional(b, c, new ProgramNode.Unset()), new ProgramNode.Unset())},
                {"return v.x=1??2", new ProgramNode.Control(ProgramNode.ControlOperator.RETURN,
                    Optional.of(
                        new ProgramNode.Assignment(variable("x"), new ProgramNode.Coalesce(number(1), number(2)))))},
                {"10-3-2",
                    binary(ProgramNode.BinaryOperator.SUB,
                        binary(ProgramNode.BinaryOperator.SUB, number(10), number(3)), number(2))},
                {"12/3*2",
                    binary(ProgramNode.BinaryOperator.MUL,
                        binary(ProgramNode.BinaryOperator.DIV, number(12), number(3)), number(2))},
                {"1<2<=3>4>=5",
                    binary(ProgramNode.BinaryOperator.GTE,
                        binary(ProgramNode.BinaryOperator.GT,
                            binary(ProgramNode.BinaryOperator.LTE,
                                binary(ProgramNode.BinaryOperator.LT, number(1), number(2)), number(3)),
                            number(4)),
                        number(5))},
                {"1==2!=3",
                    binary(ProgramNode.BinaryOperator.NEQ, binary(ProgramNode.BinaryOperator.EQ, number(1), number(2)),
                        number(3))},
                {"1&&2&&3",
                    binary(ProgramNode.BinaryOperator.AND, binary(ProgramNode.BinaryOperator.AND, number(1), number(2)),
                        number(3))},
                {"1||2||3",
                    binary(ProgramNode.BinaryOperator.OR, binary(ProgramNode.BinaryOperator.OR, number(1), number(2)),
                        number(3))},
                {"!-+q.a", unary(ProgramNode.UnaryOperator.LOGICAL_NOT,
                    unary(ProgramNode.UnaryOperator.NEGATE, unary(ProgramNode.UnaryOperator.POSITIVE, a)))}};
        for (var example : cases) {
            assertEquals(example[1], root(new MolangFrontend(), (String)example[0]), (String)example[0]);
        }
        for (var source : List.of("v.a=v.b=3", "1?v.x=2:3", "0?2:v.x=3", "q.a->q.b=3", "2(3)")) {
            assertKind(MolangFrontendException.Kind.SYNTAX, new MolangFrontend(), source);
        }
    }

    private static ProgramNode number(float value) {
        return ProgramNode.F32.of(value);
    }

    private static ProgramNode query(String name) {
        return symbol(ProgramNode.SymbolDomain.EXTERNAL, "query:" + name);
    }

    private static ProgramNode variable(String name) {
        return symbol(ProgramNode.SymbolDomain.INSTANCE, name);
    }

    private static ProgramNode symbol(ProgramNode.SymbolDomain domain, String name) {
        return new ProgramNode.Symbol(new ProgramNode.SymbolRef(domain, name), ProgramNode.AccessForm.READ, List.of());
    }

    private static ProgramNode arrow(ProgramNode target, ProgramNode body) {
        return new ProgramNode.WithContext(target, body);
    }

    private static ProgramNode unary(ProgramNode.UnaryOperator op, ProgramNode value) {
        return new ProgramNode.Unary(op, value);
    }

    private static ProgramNode binary(ProgramNode.BinaryOperator op, ProgramNode a, ProgramNode b) {
        return new ProgramNode.Binary(op, a, b);
    }

    private static ProgramNode conditional(ProgramNode a, ProgramNode b, ProgramNode c) {
        return new ProgramNode.Conditional(a, b, c);
    }

    public static final class Trace {
        final List<Float> reads = new ArrayList<>();
        final List<Float> writes = new ArrayList<>();
        int calls;

        public static Float next(Trace trace) {
            return trace.reads.get(trace.calls++);
        }

        public static float mark(Trace trace, float value) {
            trace.writes.add(value);
            return value;
        }
    }

    @Test
    void groupingPreservesValuesWritesAndProviderShortCircuiting() throws Throwable {
        var hash = ExpressionIdentityHash.SHA_256;
        var rb = ExpressionRegistry.builder(ContextTypes.create("test:grouping", 1, Trace.class, hash), hash);
        var effect = EffectSet.of(new EffectId("test:trace"));
        rb.registerFunction(new FunctionDescriptor<>(new SymbolKey("test:next"), FunctionCallShape.FIXED, 0, 0,
            List.of(), List.of(), Optional.empty(), BuiltinType.F32, new JavaMemberBinding(Trace.class, "next",
                MethodType.methodType(Float.class, Trace.class), InvocationKind.STATIC, Optional.empty()),
            effect, "test", 1));
        rb.registerFunction(new FunctionDescriptor<>(
            new SymbolKey("test:mark"), FunctionCallShape.FIXED, 1, 1, List.of(BuiltinType.F32), List.of(),
            Optional.empty(), BuiltinType.F32, new JavaMemberBinding(Trace.class, "mark",
                MethodType.methodType(float.class, Trace.class, float.class), InvocationKind.STATIC, Optional.empty()),
            effect, "test", 1));
        var registry = rb.freeze();
        String[] sources = {"0??1?2:3", "1?null:2??7", "0?null:2??7", "1?(t.x=2):3;return t.x;",
            "t.a=test.mark((t.b=test.mark(3))+1);test.mark(t.b);return test.mark(t.a);", "1?{t.x=2;null??4;}:3",
            "1?0?2:3:4", "0?2:1?3:4", "0?1?2", "return t.x=1??2", "test.next()??test.next()??test.next()",
            "test.next()??test.next()??test.next()", "test.next()??test.next()??test.next()", "0&&test.next()||1",
            "1||test.next()&&test.next()", "1?test.mark(2):test.mark(3)", "0?test.mark(2):test.mark(3)", "10-3-2",
            "12/3*2", "!-+1"};
        float[] values = {0, 7, 2, 2, 4, 4, 3, 3, 0, 1, 0, 4, 9, 1, 1, 2, 3, 5, 8, 0};
        int[] calls = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 0, 0, 0, 0, 0, 0, 0};
        var base = CompilationPolicy.DEFAULT;
        var policy = new CompilationPolicy(base.missingSymbols(), base.semanticFailure(), base.arityMismatch(),
            base.numericPolicy(), base.executionLimits(), ObjectLanguageRules.YSM_STATIC_OBJECTS);
        var builder = CompilationUnit.builder(policy, PortableProgramCodecs.v1(), hash);
        for (int i = 0; i < sources.length; i++) {
            var program =
                new MolangFrontend().compile(sources[i], MolangFrontend.SourceMode.EXPRESSION, LOCATION).program();
            builder.addProgram(new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {(byte)i})), program,
                new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(),
                    List.of(new LocalName("a"), new LocalName("b"), new LocalName("x"))),
                Optional.empty(), new DiagnosticLocation("grouping/" + i)));
        }
        var host = new HostAbi("test:grouping", "1", Fingerprint.compute(hash, ReadOnlyBytes.copyOf(new byte[] {1})));
        var unit = builder.build();
        var backend = new JaninoJavaCompilerBackend();
        var artifact = new DefaultExpressionAotCompiler<Trace>(backend).compile(TaskContext.NONE, unit, host, registry,
            CompilerOptions.DEFAULT, hash);
        var expected =
            ExpectedArtifactIdentity.create(unit, host, registry, CompilerOptions.DEFAULT, backend.descriptor(), hash);
        try (
            var resource =
                ModuleLoaders.<Trace>standard().load(artifact, expected, registry, registry.visibilityRoot(), hash);
            var instance = resource.newInstance(new RuntimeLinkage<Trace>() {
                public RegistryRuntimeLinkage<Trace> registry() {
                    return RegistryRuntimeLinkage.create(ProviderInstanceTable.create(registry, Map.of()),
                        RuntimeSymbolTable.empty());
                }

                public RuntimeDiagnostics diagnostics() {
                    return RuntimeDiagnostics.none();
                }
            })) {
            for (int i = 0; i < sources.length; i++) {
                var trace = new Trace();
                trace.reads.addAll(i == 10 ? Arrays.asList(0f, 4f, 9f)
                    : i == 11 ? Arrays.asList(null, 4f, 9f) : Arrays.asList(null, null, 9f));
                assertEquals(values[i], instance.executeFloat(instance.bindProgram(new ProgramId(i), ProgramShape.F32),
                    trace, instance.bindSubject(null), new GroupingFrame()), sources[i]);
                assertEquals(calls[i], trace.calls, sources[i]);
                assertEquals(
                    i == 4 ? List.of(3f, 4f, 3f, 4f) : i == 15 ? List.of(2f) : i == 16 ? List.of(3f) : List.of(),
                    trace.writes, sources[i]);
            }
        }
    }

    private static final class GroupingFrame implements ExecutionFrame {
        public boolean actionAllowed() {
            return true;
        }

        public void beginTopLevel(boolean allowAction) {}

        public void endTopLevel() {}

        public void enterUserFunction() {}

        public void exitUserFunction() {}
    }

    @Test
    void lowersCoreConstructsDirectlyToPortableProgram() throws Exception {
        var frontend = new MolangFrontend();
        var result =
            frontend.compile(
                "t.x=math.clamp(q.life,0,1);" + "for_each(t.item,v.items,{v.total=v.total+t.item;});"
                    + "loop(2,{break;});" + "return (v.enabled??false)?fn.tick(args[0]);",
                MolangFrontend.SourceMode.USER_FUNCTION, LOCATION);
        var decoded = PortableProgramCodecs.v1().decode(result.program());

        assertEquals(4, decoded.roots().size());
        assertInstanceOf(ProgramNode.Assignment.class, decoded.roots().get(0));
        assertInstanceOf(ProgramNode.ForEach.class, decoded.roots().get(1));
        assertInstanceOf(ProgramNode.Loop.class, decoded.roots().get(2));
        assertInstanceOf(ProgramNode.Control.class, decoded.roots().get(3));
        assertTrue(result.referencedSymbols().stream()
            .anyMatch(value -> value.domain() == ProgramNode.SymbolDomain.USER_FUNCTION
                && value.accessForm() == ProgramNode.AccessForm.INVOKE));
        assertTrue(result.statistics().nodes() > 10);
    }

    @Test
    void nullIsAnUnsetLiteralWithoutHostLookup() throws Exception {
        var result = new MolangFrontend().compile("null", MolangFrontend.SourceMode.EXPRESSION, LOCATION);
        assertInstanceOf(ProgramNode.Unset.class, PortableProgramCodecs.v1().decode(result.program()).roots().get(0));
        assertTrue(result.referencedSymbols().isEmpty());
    }

    @Test
    void oneArmConditionalHasExplicitUnsetElseBranch() throws Exception {
        var result = new MolangFrontend().compile("q.ready?1", MolangFrontend.SourceMode.EXPRESSION, LOCATION);
        var root = assertInstanceOf(ProgramNode.Conditional.class,
            PortableProgramCodecs.v1().decode(result.program()).roots().get(0));

        assertInstanceOf(ProgramNode.Unset.class, root.ifFalse());
    }

    @Test
    void preservesLiteralNodesAcrossParenthesesButNotUnaryOrBinaryOperators() throws Exception {
        var frontend = new MolangFrontend();

        assertInstanceOf(ProgramNode.F32.class, root(frontend, "(0)"));
        assertInstanceOf(ProgramNode.Bool.class, root(frontend, "(false)"));
        assertInstanceOf(ProgramNode.Unary.class, root(frontend, "+0"));
        assertInstanceOf(ProgramNode.Unary.class, root(frontend, "-0"));
        assertInstanceOf(ProgramNode.Unary.class, root(frontend, "!true"));
        assertInstanceOf(ProgramNode.Binary.class, root(frontend, "1-1"));
    }

    @Test
    void outputAndReferenceOrderAreDeterministic() throws Exception {
        var frontend = new MolangFrontend();
        var first = frontend.compile("q.z+v.a+q.a", MolangFrontend.SourceMode.EXPRESSION, LOCATION);
        var second = frontend.compile("q.z+v.a+q.a", MolangFrontend.SourceMode.EXPRESSION, LOCATION);

        assertArrayEquals(first.program().payload().toByteArray(), second.program().payload().toByteArray());
        assertEquals(first.referencedSymbols(), second.referencedSymbols());
        assertEquals("INSTANCE:a:READ,EXTERNAL:query:a:READ,EXTERNAL:query:z:READ",
            String.join(",", first.referencedSymbols().stream()
                .map(value -> value.domain() + ":" + value.name() + ':' + value.accessForm()).toList()));
    }

    @Test
    void canonicalizesAllRoamingSpellingsWithoutChangingOrdinaryVariables() throws Exception {
        var frontend = new MolangFrontend();
        var canonical = frontend.compile("v.roaming.x=v.roaming.x+1", MolangFrontend.SourceMode.EXPRESSION, LOCATION);
        for (var source : new String[] {"variable.roaming.x=variable.roaming.x+1", "roaming.x=roaming.x+1",
            "r.x=r.x+1"}) {
            var result = frontend.compile(source, MolangFrontend.SourceMode.EXPRESSION, LOCATION);
            assertArrayEquals(canonical.program().payload().toByteArray(), result.program().payload().toByteArray());
            assertEquals(canonical.referencedSymbols(), result.referencedSymbols());
        }

        var ordinary = frontend.compile("v.x", MolangFrontend.SourceMode.EXPRESSION, LOCATION);
        assertEquals(ProgramNode.SymbolDomain.INSTANCE, assertInstanceOf(ProgramNode.Symbol.class,
            PortableProgramCodecs.v1().decode(ordinary.program()).roots().get(0)).ref().domain());
    }

    @Test
    void preservesThirdPartyNamespacesAndLowersClosedMathSet() throws Exception {
        var frontend = new MolangFrontend();
        var external = frontend.compile("moda.f()+modb.f()", MolangFrontend.SourceMode.EXPRESSION, LOCATION);
        assertTrue(external.referencedSymbols().stream().anyMatch(value -> value.name().equals("moda:f")));
        assertTrue(external.referencedSymbols().stream().anyMatch(value -> value.name().equals("modb:f")));

        var names = new String[] {"abs", "clamp", "cos", "exp", "floor", "lerp", "max", "min_angle", "min", "mod",
            "pow", "round", "sin", "sqrt"};
        var arities = new int[] {1, 3, 1, 1, 1, 3, 2, 1, 2, 2, 2, 1, 1, 1};
        for (var index = 0; index < names.length; index++) {
            var arguments = String.join(",", java.util.Collections.nCopies(arities[index], "1"));
            var result = frontend.compile("math." + names[index] + '(' + arguments + ')',
                MolangFrontend.SourceMode.EXPRESSION, LOCATION);
            var call = assertInstanceOf(ProgramNode.Symbol.class,
                PortableProgramCodecs.v1().decode(result.program()).roots().get(0));
            assertEquals("math:" + names[index], call.ref().name());
            assertEquals(1, result.referencedSymbols().size());
        }

        var pi = frontend.compile("math.pi", MolangFrontend.SourceMode.EXPRESSION, LOCATION);
        var literal =
            assertInstanceOf(ProgramNode.F32.class, PortableProgramCodecs.v1().decode(pi.program()).roots().get(0));
        assertEquals(Float.floatToRawIntBits((float)Math.PI), literal.rawBits());
        assertArrayEquals(new byte[] {1, 1, (byte)0xdb, 0x0f, 0x49, 0x40}, pi.program().payload().toByteArray());
        assertTrue(pi.referencedSymbols().isEmpty());

        var e = frontend.compile("math.e", MolangFrontend.SourceMode.EXPRESSION, LOCATION);
        var eLiteral =
            assertInstanceOf(ProgramNode.F32.class, PortableProgramCodecs.v1().decode(e.program()).roots().get(0));
        assertEquals(Float.floatToRawIntBits((float)Math.E), eLiteral.rawBits());
        assertTrue(e.referencedSymbols().isEmpty());
    }

    @Test
    void acceptsLargeAndDeepInputsBeyondTheFormerAdmissionThresholds() throws Exception {
        var frontend = new MolangFrontend();
        var deep = frontend.compile("(".repeat(2_048) + "7" + ")".repeat(2_048), MolangFrontend.SourceMode.EXPRESSION,
            LOCATION);
        assertEquals(2_049, deep.statistics().maximumDepth());
        var longLiteral =
            frontend.compile("'" + "a".repeat(1_048_577) + "'", MolangFrontend.SourceMode.EXPRESSION, LOCATION);
        assertEquals(1_048_579, longLiteral.statistics().sourceBytes());
        var roots = frontend.compile("1;".repeat(65_537), MolangFrontend.SourceMode.EXPRESSION, LOCATION);
        assertEquals(65_537, PortableProgramCodecs.v1().decode(roots.program()).roots().size());
        var name = "a".repeat(1_025);
        assertEquals(name,
            ((ProgramNode.Symbol)PortableProgramCodecs.v1()
                .decode(frontend.compile("t." + name, MolangFrontend.SourceMode.EXPRESSION, LOCATION).program()).roots()
                .get(0)).ref().name());
        assertKind(MolangFrontendException.Kind.SYNTAX, frontend, "math.sin(1,)");
    }

    @Test
    void rejectsImplicitApplicationAndMalformedSyntax() {
        assertKind(MolangFrontendException.Kind.SYNTAX, new MolangFrontend(), "2q.x");
        assertKind(MolangFrontendException.Kind.SYNTAX, new MolangFrontend(), "q.x+");
    }

    @Test
    void lowersCorpusSectionMarkersToExplicitNoOp() throws Exception {
        for (var marker : new String[] {"[;", "];", "['section';"}) {
            var program =
                new MolangFrontend().compile(marker, MolangFrontend.SourceMode.EXPRESSION, LOCATION).program();
            assertInstanceOf(ProgramNode.Unset.class, PortableProgramCodecs.v1().decode(program).roots().get(0));
        }
        assertKind(MolangFrontendException.Kind.SYNTAX, new MolangFrontend(), "[");
        assertKind(MolangFrontendException.Kind.SYNTAX, new MolangFrontend(), "]");
        assertKind(MolangFrontendException.Kind.SYNTAX, new MolangFrontend(), "['section'");
    }

    @Test
    void onlyInstructionModeAcceptsHistoricCommentStatements() throws Exception {
        var source = "'note: ysm.bone_rot('LeftM1').x';";
        var result = new MolangFrontend().compile(source, MolangFrontend.SourceMode.INSTRUCTION, LOCATION);
        assertInstanceOf(ProgramNode.Unset.class, PortableProgramCodecs.v1().decode(result.program()).roots().get(0));
        assertThrows(MolangFrontendException.class,
            () -> new MolangFrontend().compile(source, MolangFrontend.SourceMode.EXPRESSION, LOCATION));
    }

    private static void assertKind(MolangFrontendException.Kind kind, MolangFrontend frontend, String source) {
        var failure = assertThrows(MolangFrontendException.class,
            () -> frontend.compile(source, MolangFrontend.SourceMode.EXPRESSION, LOCATION));
        assertEquals(kind, failure.kind());
    }

    private static ProgramNode root(MolangFrontend frontend, String source) throws Exception {
        return PortableProgramCodecs.v1()
            .decode(frontend.compile(source, MolangFrontend.SourceMode.EXPRESSION, LOCATION).program()).roots().get(0);
    }
}
