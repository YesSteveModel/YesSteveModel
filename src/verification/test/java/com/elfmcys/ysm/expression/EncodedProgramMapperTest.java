package com.elfmcys.ysm.expression;

import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.util.ProtoUtil;
import dev.semaweave.program.EncodedProgram;
import dev.semaweave.program.PortableProgram;
import dev.semaweave.program.PortableProgramCodec;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.PortablePrograms;
import dev.semaweave.program.ProgramNode;
import dev.semaweave.program.ProgramStructuralException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class EncodedProgramMapperTest {
    @Test
    void decodedMappingParsesOnceAndPreservesTheValidatedWireIdentity() throws Exception {
        var roots = List.<ProgramNode>of(new ProgramNode.F32(Float.floatToRawIntBits(41F)));
        var encoded = PortableProgramCodecs.v1().encode(PortablePrograms.ofRoots(roots));
        var source = EncodedProgramMapper.toProto(encoded);
        var codec = new CountingCodec();
        var decoded = EncodedProgramMapper.decode(source, codec);

        assertEquals(1, codec.decodes);
        assertEquals(0, codec.validations);
        assertEquals(roots, decoded.program().roots());
        assertEquals(encoded.formatId(), decoded.encoded().formatId());
        assertArrayEquals(encoded.payload().toByteArray(), decoded.encoded().payload().toByteArray());
        assertArrayEquals(ProtoUtil.serializeToArray(source),
            ProtoUtil.serializeToArray(EncodedProgramMapper.toProto(decoded.encoded())));

        var envelopeCodec = new CountingCodec();
        var mapped = EncodedProgramMapper.fromProto(source, envelopeCodec);
        assertEquals(1, envelopeCodec.validations);
        assertEquals(0, envelopeCodec.decodes);
        assertArrayEquals(mapped.payload().toByteArray(), decoded.encoded().payload().toByteArray());
    }

    @Test
    void decodedMappingRejectsTheSameMalformedInputsAsTheValidatedEnvelope() {
        var valid = PortableProgramCodecs.v1().canonicalZero().payload().toByteArray();
        for (var source : List.of(ProgramProto.newBuilder().setFormat(1).build(),
            ProgramProto.newBuilder().setFormat(1).setBytecode(ByteBuffer.allocate(0)).build(),
            ProgramProto.newBuilder().setFormat(999).setBytecode(ByteBuffer.wrap(valid)).build(),
            ProgramProto.newBuilder().setFormat(1).setBytecode(ByteBuffer.wrap(new byte[] {0})).build())) {
            var prior = assertThrows(ProgramStructuralException.class, () -> EncodedProgramMapper.fromProto(source));
            var current = assertThrows(ProgramStructuralException.class,
                () -> EncodedProgramMapper.decode(source, PortableProgramCodecs.v1()));
            assertEquals(prior.category(), current.category());
        }
    }

    private static final class CountingCodec implements PortableProgramCodec {
        private final PortableProgramCodec delegate = PortableProgramCodecs.v1();
        private int decodes;
        private int validations;

        @Override
        public PortableProgram decode(int formatId, byte[] payload) throws ProgramStructuralException {
            decodes++;
            return delegate.decode(formatId, payload);
        }

        @Override
        public PortableProgram decode(EncodedProgram input) throws ProgramStructuralException {
            decodes++;
            return delegate.decode(input);
        }

        @Override
        public EncodedProgram encode(PortableProgram program) throws ProgramStructuralException {
            return delegate.encode(program);
        }

        @Override
        public void validate(int formatId, byte[] payload) throws ProgramStructuralException {
            validations++;
            delegate.validate(formatId, payload);
        }

        @Override
        public void validate(EncodedProgram input) throws ProgramStructuralException {
            validations++;
            delegate.validate(input);
        }

        @Override
        public EncodedProgram canonicalZero() {
            return delegate.canonicalZero();
        }
    }
}
