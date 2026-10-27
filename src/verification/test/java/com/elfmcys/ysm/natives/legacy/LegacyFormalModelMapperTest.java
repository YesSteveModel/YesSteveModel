package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.StateProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.util.ProtoUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyFormalModelMapperTest {
    @Test
    void mapsEveryLegacyOriginToAReleaseWithoutChangingExportProvenance() {
        var export = com.elfmcys.ysm.proto.mixel.manifest.info.ExportInfoProto.newBuilder().setTimestamp(123)
            .setExtra("original export").build();
        for (int originVer = 0; originVer <= 32; originVer++) {
            var source = com.elfmcys.ysm.proto.mixel.manifest.ManifestProto.newBuilder()
                .setCommonAssets(com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto.newBuilder().build())
                .setInfo(com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto.newBuilder()
                    .setProperties(com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto.newBuilder()
                        .setOriginVer(Integer.toString(originVer)).build())
                    .setSettings(com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto.newBuilder().build())
                    .setExport(export).build())
                .build();
            var result = new LegacyFormalModelMapper().manifest(source);
            assertEquals(com.elfmcys.ysm.format.schema.model.ModelOriginVersion.toModVersion(originVer),
                result.info().properties().originVer());
            assertEquals(export, result.info().exportUnsafe());
        }
    }

    @Test
    void lowersHistoricSourcesThroughTheFormalFrontend() throws Exception {
        var legacy = StringDataProto.newBuilder()
            .addUserFunctions(
                UserFunctionProto.newBuilder().setName("tick").setBody(sourceProgram("return q.life;")).build())
            .build();

        var result = new LegacyFormalModelMapper().strings(legacy);

        assertEquals("tick", result.userFunctions().get(0).name());
        assertTrue(result.userFunctions().get(0).body().hasBytecode());
        assertFalse(result.userFunctions().get(0).body().hasSource());
        assertTrue(result.userFunctions().get(0).body().bytecode().hasRemaining());
        assertTrue(result.userFunctions().get(0).body().source().isEmpty());
        var mapped = EncodedProgramMapper.fromProto(result.userFunctions().get(0).body());
        var direct = new MolangFrontend().compile("return q.life;", MolangFrontend.SourceMode.USER_FUNCTION,
            new MolangFrontend.SourceLocation("legacy/function/tick")).program();
        assertEquals(direct.formatId(), mapped.formatId());
        assertArrayEquals(direct.payload().toByteArray(), mapped.payload().toByteArray());
    }

    @Test
    void currentSchemaCarriesHistoricSourceWithoutASecondMessageFamily() throws Exception {
        var source = StringDataProto.newBuilder()
            .addUserFunctions(UserFunctionProto.newBuilder().setName("tick").setBody(sourceProgram("q.life")).build())
            .build();

        var parsed = StringDataProto.parseFrom(ProtoUtil.serializeToArray(source));

        assertEquals("q.life", parsed.userFunctions().get(0).body().source());
        assertTrue(parsed.userFunctions().get(0).body().hasSource());
        assertFalse(parsed.userFunctions().get(0).body().hasBytecode());
        assertFalse(parsed.userFunctions().get(0).body().bytecode().hasRemaining());
    }

    @Test
    void lowersInvalidHistoricSourceToTheLegacyZeroFallback() throws Exception {
        var legacy = StringDataProto.newBuilder()
            .addUserFunctions(UserFunctionProto.newBuilder().setName("bad").setBody(sourceProgram("q.life+")).build())
            .build();

        var result = new LegacyFormalModelMapper().strings(legacy);
        var mapped = EncodedProgramMapper.fromProto(result.userFunctions().get(0).body());
        var fallback = new MolangFrontend().compile("0", MolangFrontend.SourceMode.USER_FUNCTION,
            new MolangFrontend.SourceLocation("legacy/function/bad")).program();

        assertEquals(fallback.formatId(), mapped.formatId());
        assertArrayEquals(fallback.payload().toByteArray(), mapped.payload().toByteArray());
    }

    @Test
    void preservesBlankHistoricProgramsAsFrontendNoOps() {
        var legacy = StringDataProto.newBuilder()
            .addUserFunctions(UserFunctionProto.newBuilder().setName("noop").setBody(sourceProgram("  \n")).build())
            .build();

        var result = new LegacyFormalModelMapper().strings(legacy);

        assertTrue(result.userFunctions().get(0).body().bytecode().hasRemaining());
        assertTrue(result.userFunctions().get(0).body().source().isEmpty());
    }

    @Test
    void rejectsLegacyProgramsWithoutAnActiveSourcePayload() {
        var mapper = new LegacyFormalModelMapper();

        assertThrows(IllegalArgumentException.class,
            () -> mapper.strings(stringsWithBody(ProgramProto.newBuilder().build())));
        assertThrows(IllegalArgumentException.class, () -> mapper
            .strings(stringsWithBody(ProgramProto.newBuilder().setBytecode(java.nio.ByteBuffer.allocate(0)).build())));
    }

    @Test
    void preservesMissingBlendTransition() {
        var state = StateProto.newBuilder().setName("default").build();
        var controller = AnimationControllerProto.newBuilder().setName("controller").setDefaultState("default")
            .addStates(state).build();
        var file = AnimationControllerFileProto.newBuilder().addControllers(controller).build();
        var source = ModelDataProto.newBuilder().putAnimationControllers("main", file).build();

        var result = new LegacyFormalModelMapper().model(source);

        assertFalse(
            result.animationControllers().get("main").controllers().get(0).states().get(0).hasBlendTransition());
    }

    private static ProgramProto sourceProgram(String source) {
        return ProgramProto.newBuilder().setSource(source).build();
    }

    private static StringDataProto stringsWithBody(ProgramProto body) {
        return StringDataProto.newBuilder()
            .addUserFunctions(UserFunctionProto.newBuilder().setName("test").setBody(body).build()).build();
    }
}
