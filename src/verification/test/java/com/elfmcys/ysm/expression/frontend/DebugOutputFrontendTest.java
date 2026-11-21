package com.elfmcys.ysm.expression.frontend;

import dev.semaweave.program.ProgramNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DebugOutputFrontendTest {
    @Test
    void mixedArgumentsAreGuardedAndAppendedInSourceOrder() throws Exception {
        var parser = new MolangSourceParser("q.debug_output('value=', 2, true)",
            new MolangFrontend.SourceLocation("test"), dev.semaweave.compilation.TaskContext.NONE);
        var result = parser.parse(MolangFrontend.SourceMode.EXPRESSION);
        var conditional = assertInstanceOf(ProgramNode.Conditional.class, result.roots().get(0));
        var condition = assertInstanceOf(ProgramNode.Symbol.class, conditional.condition());
        assertEquals("query:debug_output_enabled", condition.ref().name());
        assertInstanceOf(ProgramNode.Unset.class, conditional.ifFalse());
        var block = assertInstanceOf(ProgramNode.Block.class, conditional.ifTrue());
        assertEquals(5, block.children().size());
        assertEquals("query:debug_output_begin", ((ProgramNode.Symbol)block.children().get(0)).ref().name());
        assertInstanceOf(ProgramNode.StringLiteral.class,
            ((ProgramNode.Symbol)block.children().get(1)).arguments().get(0));
        assertInstanceOf(ProgramNode.F32.class, ((ProgramNode.Symbol)block.children().get(2)).arguments().get(0));
        assertInstanceOf(ProgramNode.Bool.class, ((ProgramNode.Symbol)block.children().get(3)).arguments().get(0));
        assertEquals("query:debug_output_end", ((ProgramNode.Symbol)block.children().get(4)).ref().name());
    }
}
