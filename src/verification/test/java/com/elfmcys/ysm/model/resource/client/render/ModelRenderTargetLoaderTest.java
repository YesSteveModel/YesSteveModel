package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoPropertiesProto;
import com.elfmcys.ysm.util.ProtoUtil;
import com.elfmcys.ysm.util.ResourceTransaction;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRenderTargetLoaderTest {
    @Test
    void unpublishedResourcesCloseInReverseOrder() {
        var order = new ArrayList<Integer>();
        try (var resources = new ResourceTransaction()) {
            resources.own((AutoCloseable)() -> order.add(1));
            resources.own((AutoCloseable)() -> order.add(2));
        }
        assertEquals(List.of(2, 1), order);
    }

    @Test
    void successfulPublicationTransfersOwnershipExactlyOnce() throws Exception {
        var closes = new AtomicInteger();
        var candidate = (AutoCloseable)closes::incrementAndGet;
        try (var resources = new ResourceTransaction()) {
            resources.own(candidate);
            assertSame(candidate, ModelRenderTargetLoader.publishCandidate(() -> false, resources, candidate));
        }
        assertEquals(0, closes.get());

        candidate.close();
        assertEquals(1, closes.get());
    }

    @Test
    void cancellationAfterTransferClosesCandidateExactlyOnce() {
        var closes = new AtomicInteger();
        var candidate = (AutoCloseable)closes::incrementAndGet;
        try (var resources = new ResourceTransaction()) {
            resources.own(candidate);
            assertThrows(CancellationException.class,
                () -> ModelRenderTargetLoader.publishCandidate(() -> true, resources, candidate));
        }
        assertEquals(1, closes.get());
    }

    @Test
    void resolvesMainAndArmGeometryFromOneModelData() throws Exception {
        var main = GeoModelProto.newBuilder()
            .setProperties(GeoPropertiesProto.newBuilder().setTextureHeight(0).setTextureWidth(0).build())
            .setCubes(ByteBuffer.allocate(0)).build();
        var arm = GeoModelProto.newBuilder()
            .setProperties(GeoPropertiesProto.newBuilder().setTextureHeight(0).setTextureWidth(0).build())
            .setCubes(ByteBuffer.allocate(0)).build();
        var definition = ModelDataProto.newBuilder();
        var mainEntry = geometry("main", main);
        var armEntry = geometry("arm", arm);
        definition.putGeoModels(mainEntry.getKey(), mainEntry.getValue());
        definition.putGeoModels(armEntry.getKey(), armEntry.getValue());
        var modelData = definition.build();

        assertArrayEquals(ProtoUtil.serializeToArray(main),
            ProtoUtil.serializeToArray(ModelRenderTargetLoader.geoModel(modelData, "main")));
        assertArrayEquals(ProtoUtil.serializeToArray(arm),
            ProtoUtil.serializeToArray(ModelRenderTargetLoader.geoModel(modelData, "arm")));
        var missing =
            assertThrows(AssetLoadException.class, () -> ModelRenderTargetLoader.geoModel(modelData, "missing"));
        assertEquals(AssetLoadException.Reason.CONTENT, missing.reason());
    }

    private static Map.Entry<String, ByteBuffer> geometry(String name, GeoModelProto model) throws Exception {
        return Map.entry(name, ByteBuffer.wrap(ProtoUtil.serializeToArray(model)));
    }

    @Test
    void mapsAssetProvenanceAtTheLoaderBoundary() {
        assertEquals(ResourceFailure.Kind.TRANSIENT,
            ModelRenderTargetLoader.failure(AssetLoadException.access("offline")).failure().kind());
        assertEquals(ResourceFailure.Kind.DETERMINISTIC,
            ModelRenderTargetLoader.failure(AssetLoadException.content("invalid")).failure().kind());
        assertEquals(ResourceFailure.Kind.TRANSIENT,
            ModelRenderTargetLoader.failure(new CancellationException("cancelled")).failure().kind());
    }
}
