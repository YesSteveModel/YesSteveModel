package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.frontend.MolangFrontendException;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;

import java.util.List;

final class FrontendPrograms {
    private FrontendPrograms() {}

    static ProgramProto compile(MolangFrontend frontend, String source, MolangFrontend.SourceMode mode,
        String location) {
        try {
            return EncodedProgramMapper
                .toProto(frontend.compile(source, mode, new MolangFrontend.SourceLocation(location)).program());
        } catch (MolangFrontendException failure) {
            try {
                return EncodedProgramMapper
                    .toProto(frontend.compile("0", mode, new MolangFrontend.SourceLocation(location)).program());
            } catch (MolangFrontendException impossible) {
                throw new IllegalStateException("constant Molang fallback did not compile", impossible);
            }
        }
    }

    static ProgramProto compileStatements(MolangFrontend frontend, List<String> sources, String location) {
        var combined = new StringBuilder();
        for (var source : sources) {
            var statement = source == null ? "" : source.stripTrailing();
            combined.append(statement);
            if (!statement.endsWith(";")) {
                combined.append(';');
            }
            combined.append('\n');
        }
        return compile(frontend, combined.toString(), MolangFrontend.SourceMode.INSTRUCTION, location);
    }
}
