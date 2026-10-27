package com.elfmcys.ysm.model.catalog.builtin;

import com.elfmcys.ysm.model.catalog.content.DefaultAnimationKey;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.EventKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStatsProto;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinModelIndexTest {
    @Test
    void writesCanonicalSortedIndexAndReadsItBack() throws Exception {
        var index =
            BuiltinModelIndex.of(Map.of(new ModelPath("misc/example"), hash(2), new ModelPath("default"), hash(1)));
        var output = new StringWriter();

        index.write(output);

        var json = output.toString();
        assertTrue(json.indexOf("default") < json.indexOf("misc/example"));
        assertEquals(List.of(new ModelPath("default"), new ModelPath("misc/example")), BuiltinModelIndex
            .read(new StringReader(json)).entries().stream().map(BuiltinModelIndex.Entry::path).toList());
    }

    @Test
    void rejectsUnknownFieldsUnsupportedVersionsAndNonCanonicalValues() {
        assertInvalid("""
            {"formatVersion":1,"models":[{"path":"default","modelHash":"%s","extra":1}]}
            """.formatted(hash(1)));
        assertInvalid("""
            {"formatVersion":2,"models":[{"path":"default","modelHash":"%s"}]}
            """.formatted(hash(1)));
        assertInvalid("""
            {"formatVersion":1,"models":[{"path":"/default","modelHash":"%s"}]}
            """.formatted(hash(1)));
        assertInvalid("""
            {"formatVersion":1,"models":[{"path":"default","modelHash":"%s"}]}
            """.formatted(hash(0xab).toString().toUpperCase()));
    }

    @Test
    void rejectsUnsortedDuplicateHashMissingDefaultAndCoverageMismatch() throws Exception {
        assertInvalid("""
            {"formatVersion":1,"models":[
              {"path":"misc/example","modelHash":"%s"},
              {"path":"default","modelHash":"%s"}
            ]}
            """.formatted(hash(2), hash(1)));
        assertInvalid("""
            {"formatVersion":1,"models":[
              {"path":"default","modelHash":"%s"},
              {"path":"misc/example","modelHash":"%s"}
            ]}
            """.formatted(hash(1), hash(1)));
        assertInvalid("""
            {"formatVersion":1,"models":[{"path":"misc/example","modelHash":"%s"}]}
            """.formatted(hash(2)));

        var index = BuiltinModelIndex.of(Map.of(new ModelPath("default"), hash(1)));
        assertThrows(IOException.class, () -> index.validateCoverage(List.of(new ModelPath("different"))));
    }

    @Test
    void mergesKnownAnimationHistoryAndRejectsUnknownNames() throws Exception {
        var key = new DefaultAnimationKey("player/main", "idle");
        var current = hash(3);
        var historical = hash(4);
        var index = BuiltinModelIndex.of(Map.of(new ModelPath("default"), hash(1)), Map.of(key, current),
            Map.of(key, Set.of(historical)));

        assertTrue(index.accepts(key, current));
        assertTrue(index.accepts(key, historical));
        assertThrows(IOException.class, () -> BuiltinModelIndex.of(Map.of(new ModelPath("default"), hash(1)),
            Map.of(key, current), Map.of(new DefaultAnimationKey("player/main", "missing"), Set.of(historical))));
    }

    @Test
    void immutableContractFiltersAcceptedDefaultsAndValidatesCurrentPayloads() throws Exception {
        var target = RenderTargetProto.newBuilder().setTargetId("player").setKind(RenderTargetKind.PLAYER).setBlobId(0)
            .setSettings(ModelSettingsProto.newBuilder().setHeightScale(0).setWidthScale(0).setRenderLayersFirst(false)
                .setForceCulling(false).setGuiNoLighting(false).setMergeMultilineExpr(false).build())
            .setStats(ModelStatsProto.newBuilder().setBones(0).setCubes(0).setFaces(0).build()).build();
        var idle = AnimationProto.newBuilder().setName("idle").setLength(1).setLoop(LoopType.PLAY_ONCE).build();
        var custom = AnimationProto.newBuilder().setName("custom").setLength(2).setLoop(LoopType.PLAY_ONCE).build();
        var key = new DefaultAnimationKey("player/main", "idle");
        var index = BuiltinModelIndex.of(Map.of(new ModelPath("default"), hash(1)),
            Map.of(key, BuiltinModelMaterializer.payloadHash(idle)), Map.of());

        var filtered = index.apply(target.kind(), target.match(), "main",
            AnimationFileProto.newBuilder().addAnimations(idle).addAnimations(custom).build());

        assertEquals(1, filtered.animations().size());
        assertEquals("custom", filtered.animations().get(0).name());
        assertDoesNotThrow(() -> index.requireCurrent(target, "main", idle));
        assertThrows(IOException.class, () -> index.requireCurrent(target, "main",
            AnimationProto.newBuilder().setName("idle").setLength(3).setLoop(LoopType.PLAY_ONCE).build()));
    }

    @Test
    void soundKeyframesParticipateInBuiltinPayloadIdentity() throws Exception {
        var withoutSound = AnimationProto.newBuilder().setName("idle").setLength(0).setLoop(LoopType.PLAY_ONCE).build();
        var withSound = AnimationProto.newBuilder().setName("idle").setLength(0).setLoop(LoopType.PLAY_ONCE)
            .addSoundKeyframes(EventKeyFrameProto.newBuilder().setData("minecraft:bell").setStartTick(1.25f).build())
            .build();

        assertNotEquals(BuiltinModelMaterializer.payloadHash(withoutSound),
            BuiltinModelMaterializer.payloadHash(withSound));
    }

    private static void assertInvalid(String json) {
        assertThrows(IOException.class, () -> BuiltinModelIndex.read(new StringReader(json)));
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[bytes.length - 1] = (byte)marker;
        return new Hash256(bytes);
    }
}
