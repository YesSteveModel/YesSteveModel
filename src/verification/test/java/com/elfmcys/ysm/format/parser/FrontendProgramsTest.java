package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FrontendProgramsTest {
    @Test
    void lowersInvalidPersistentSourceToZero() throws Exception {
        var frontend = new MolangFrontend();
        var actual = EncodedProgramMapper.fromProto(FrontendPrograms.compile(frontend, "q.value+",
            MolangFrontend.SourceMode.EXPRESSION, "test/invalid-persistent"));
        var expected = frontend.compile("0", MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("test/invalid-persistent")).program();

        assertEquals(expected.formatId(), actual.formatId());
        assertArrayEquals(expected.payload().toByteArray(), actual.payload().toByteArray());
    }
}
