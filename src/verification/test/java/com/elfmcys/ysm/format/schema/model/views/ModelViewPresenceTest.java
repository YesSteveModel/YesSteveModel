package com.elfmcys.ysm.format.schema.model.views;

import com.elfmcys.ysm.format.schema.file.AssetFileView;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.common.StringPairProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.AuthorProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigFormsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationButtonProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationClassifyProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.LanguageFileProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.LicenseProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.MetadataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStatsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelViewPresenceTest {
    @Test
    void acceptsAbsentDisplayMetadataAndAuthorAvatar() {
        var info = infoBuilder().clearMetadata().build();
        var author = AuthorProto.newBuilder().build();

        var view = view(info, player());

        assertFalse(view.hasMetadata());
        assertNull(view.getMetadata());
        assertFalse(author.hasAvatar());
        assertTrue(author.avatar().isEmpty());
    }

    @Test
    void treatsEmptyInfoCollectionsAsEmptyWithoutMutatingMessage() {
        var info = emptyInfo();

        var view = view(info, player());

        assertEquals("fallback", view.translateOr("missing", "en_us", "fallback"));
        assertEquals(0, info.languageFiles().size());
        assertEquals(0, info.metadataUnsafe().authors().size());
    }

    @Test
    void treatsEmptyRenderTargetCollectionsWithoutMutatingMessage() {
        var descriptor = player();

        var view = new RenderTargetView(null, descriptor);

        assertEquals("player", view.id());
        assertEquals(com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PLAYER, view.kind());
        assertEquals(0, view.matches().size());
        assertEquals(0, view.getTextureNames().size());
        assertEquals(0, descriptor.match().size());
        assertEquals(0, descriptor.textures().size());
    }

    @Test
    void metadataIndexesReferenceThePublishedProto() {
        var animation = pair("wave", "Wave");
        var button = ExtraAnimationButtonProto.newBuilder().setId("settings").setName("Settings").setSound("").build();
        var classification =
            ExtraAnimationClassifyProto.newBuilder().setId("social").addExtraAnimation(animation).build();
        var settings = SettingsProto.newBuilder().setDefaultTexture("default").setPreviewAnimation("idle")
            .setDisablePreviewRotation(false).addExtraAnimation(animation).addExtraAnimationButtons(button)
            .addExtraAnimationClassify(classification).build();
        var language =
            LanguageFileProto.newBuilder().setLocale("en_us").putEntries("metadata.name", "Published name").build();
        var info = infoBuilder().setSettings(settings).addLanguageFiles(language).build();
        var playerSettings = playerSettings();
        var playerStats = ModelStatsProto.newBuilder().setBones(1).setCubes(2).setFaces(3).build();
        var player = RenderTargetProto.newBuilder().setTargetId("player")
            .setKind(com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PLAYER).setBlobId(1)
            .setSettings(playerSettings).setStats(playerStats).build();

        var view = view(info, player);

        assertSame(settings, view.getSettings());
        assertSame(playerSettings, view.getPlayerSettings());
        assertSame(playerStats, view.getPlayerStats());
        assertSame(animation, view.getExtraAnimations().get(0));
        assertSame(button, view.getExtraAnimationButtons().get("settings"));
        assertSame(classification, view.getExtraAnimationClassifications().get("social"));
        assertEquals("Published name", view.translateOr("metadata.name", "en_us", "fallback"));
        assertThrows(UnsupportedOperationException.class, () -> view.getExtraAnimations().add(pair("other", "Other")));
        assertThrows(UnsupportedOperationException.class, () -> view.getExtraAnimationButtons().put("other", button));
    }

    @Test
    void rejectsUnknownRouletteFormAtTheMetadataInterface() {
        var form = ConfigFormsProto.newBuilder().setType("unknown").setTitle("").setDescription("")
            .setReadProgram(
                ProgramProto.newBuilder().setFormat(1).setBytecode(java.nio.ByteBuffer.wrap(new byte[] {1})).build())
            .setWriteProgram(
                ProgramProto.newBuilder().setFormat(1).setBytecode(java.nio.ByteBuffer.wrap(new byte[] {1})).build())
            .setStep(0).setMin(0).setMax(0).build();
        var button = ExtraAnimationButtonProto.newBuilder().setId("settings").setName("Settings").setSound("")
            .addConfigForms(form).build();
        var info = infoBuilder().setSettings(SettingsProto.newBuilder().setDefaultTexture("default")
            .setPreviewAnimation("idle").setDisablePreviewRotation(false).addExtraAnimationButtons(button).build())
            .build();

        assertThrows(IllegalArgumentException.class, () -> view(info, player()));
    }

    private static ModelInfoView view(InfoProto info, RenderTargetProto player) {
        return new ModelInfoView(info, player, new AssetFileView(null));
    }

    private static StringPairProto pair(String key, String value) {
        return StringPairProto.newBuilder().setKey(key).setValue(value).build();
    }

    private static InfoProto emptyInfo() {
        return infoBuilder().build();
    }

    private static InfoProto.Builder infoBuilder() {
        return InfoProto.newBuilder()
            .setSettings(SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("")
                .setDisablePreviewRotation(false).build())
            .setMetadata(MetadataProto.newBuilder().setName("").setTips("")
                .setLicense(LicenseProto.newBuilder().setType("").setDesc("").build()).build())
            .setProperties(PropertiesProto.newBuilder()
                .setModelId(java.nio.ByteBuffer.wrap(new byte[com.elfmcys.ysm.model.domain.Hash256.SIZE]))
                .setFree(false).setOriginVer("").build());
    }

    private static RenderTargetProto player() {
        return RenderTargetProto.newBuilder().setTargetId("player")
            .setKind(com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PLAYER).setBlobId(1)
            .setSettings(playerSettings())
            .setStats(ModelStatsProto.newBuilder().setBones(0).setCubes(0).setFaces(0).build()).build();
    }

    private static ModelSettingsProto playerSettings() {
        return ModelSettingsProto.newBuilder().setHeightScale(1.1f).setWidthScale(0.9f).setRenderLayersFirst(true)
            .setForceCulling(false).setGuiNoLighting(false).setMergeMultilineExpr(false).build();
    }
}
