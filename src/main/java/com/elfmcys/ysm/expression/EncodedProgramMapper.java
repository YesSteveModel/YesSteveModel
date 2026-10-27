package com.elfmcys.ysm.expression;

import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.util.ProtoBytes;
import dev.semaweave.program.EncodedProgram;
import dev.semaweave.program.PortableProgram;
import dev.semaweave.program.PortableProgramCodec;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.ProgramStructuralException;
import dev.semaweave.program.ReadOnlyBytes;

import java.nio.ByteBuffer;
import java.util.Objects;

/** The only formal-schema mapping seam for engine-owned EncodedProgram values. */
public final class EncodedProgramMapper {
    private EncodedProgramMapper() {}

    public static ProgramProto toProto(EncodedProgram source) {
        Objects.requireNonNull(source, "source");
        return ProgramProto.newBuilder().setFormat(source.formatId())
            .setBytecode(ByteBuffer.wrap(source.payload().toByteArray())).build();
    }

    public static EncodedProgram fromProto(ProgramProto source) throws ProgramStructuralException {
        return fromProto(source, PortableProgramCodecs.v1());
    }

    public static EncodedProgram fromProto(ProgramProto source, PortableProgramCodec codec)
        throws ProgramStructuralException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(codec, "codec");
        var value = envelope(source);
        codec.validate(value);
        return value;
    }

    /** Returns validated bytes and syntax together so callers do not parse twice. */
    public static DecodedProgram decode(ProgramProto source, PortableProgramCodec codec)
        throws ProgramStructuralException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(codec, "codec");
        var value = envelope(source);
        return new DecodedProgram(value, codec.decode(value));
    }

    public record DecodedProgram(EncodedProgram encoded, PortableProgram program) {
        public DecodedProgram {
            Objects.requireNonNull(encoded, "encoded");
            Objects.requireNonNull(program, "program");
        }
    }

    private static EncodedProgram envelope(ProgramProto source) throws ProgramStructuralException {
        if (!source.hasBytecode() || !source.bytecode().hasRemaining()) {
            throw new ProgramStructuralException(ProgramStructuralException.Category.INVALID_ENVELOPE, -1);
        }
        return new EncodedProgram(source.format(), ReadOnlyBytes.copyOf(ProtoBytes.copy(source.bytecode())));
    }
}
