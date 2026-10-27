package com.elfmcys.ysm.expression.frontend;

import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.ProgramNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MolangFrontendTest {
    private static final MolangFrontend.SourceLocation LOCATION = new MolangFrontend.SourceLocation("test/expression");

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
    }

    @Test
    void enforcesSourceTokenNodeAndDepthBounds() {
        assertKind(MolangFrontendException.Kind.SOURCE_LIMIT, new MolangFrontend(PortableProgramCodecs.v1(),
            new MolangFrontend.Limits(2, 100, 100, 10, 10, 10, 10, 1000)), "q.x");
        assertKind(MolangFrontendException.Kind.TOKEN_LIMIT, new MolangFrontend(PortableProgramCodecs.v1(),
            new MolangFrontend.Limits(100, 2, 100, 10, 10, 10, 10, 1000)), "1+2");
        assertKind(MolangFrontendException.Kind.SOURCE_LIMIT, new MolangFrontend(PortableProgramCodecs.v1(),
            new MolangFrontend.Limits(100, 100, 2, 10, 10, 10, 10, 1000)), "1+2");
        assertKind(MolangFrontendException.Kind.SOURCE_LIMIT, new MolangFrontend(PortableProgramCodecs.v1(),
            new MolangFrontend.Limits(100, 100, 100, 2, 10, 10, 10, 1000)), "((1))");
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
