package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.InlineChunkReader;
import com.elfmcys.ysm.format.schema.file.FileChunkDataSource;
import com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.natives.physics.NativePhysicsAsset;
import com.elfmcys.ysm.natives.physics.NativePhysicsWorld;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LimitKind;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.testutil.ProtobufJavaOracle;
import com.elfmcys.ysm.util.ProtoUtil;
import com.google.protobuf.ByteString;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(NativeLibraryExtension.class)
class PhysicsConformanceTest {
    private static final String ROOT = "/physics-fixtures/";
    private static final CatalogLocation LOCATION =
        new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("physics-conformance"));

    private record Fixture(String file, String result, String expectation, String summary) {
        @Override
        public String toString() {
            return file;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void acceptsC3AndM1BeforeCheckingThePlayerDefinition(Fixture fixture, @TempDir Path directory) throws Exception {
        var path = directory.resolve(fixture.file);
        try (var input = resource(fixture.file)) {
            Files.copy(input, path);
        }
        try (var input = FileChannel.open(path)) {
            var container = AssetContainerReader.read(input);
            assertEquals(fixture.summary, PhysicsConformanceFixtureGenerator.summary(path, container));
            var preamble = Arrays.copyOf(Files.readAllBytes(path), container.getContainerPreambleSize());
            assertTrue(container.sameContents(AssetContainerReader.readPreamble(preamble)));
            long end = container.getContainerPreambleSize();
            for (var chunk : container.getChunkTable().values()) {
                // The reader already verified the preamble using this chunk's special hash.
                if (AssetContainerConstant.VERIFICATION_CHUNK_TYPE.equals(chunk.type()))
                    continue;
                InlineChunkReader.readStoredVerifiedBytes(input, chunk);
                end = Math.max(end, (long)chunk.offset() + chunk.size());
            }
            assertEquals(end, input.size());

            var metadata = new ModelFileView(input);
            var player = metadata.getPlayer();
            assertEquals("player", player.id());
            var source = new FileChunkDataSource(path);
            if ("M2_CONTENT".equals(fixture.result)) {
                var rejection =
                    assertThrows(AssetLoadException.class, () -> player.readDefinition(() -> false, source));
                assertEquals(AssetLoadException.Reason.CONTENT, rejection.reason());
                assertTrue(rejection.getMessage().contains(fixture.expectation), rejection::getMessage);
                return;
            }
            assertEquals("M2", fixture.result);
            var definition = player.readDefinition(() -> false, source);
            assertTrue("present".equals(fixture.expectation) || "absent".equals(fixture.expectation));
            boolean hasPhysics = "present".equals(fixture.expectation);
            assertEquals(hasPhysics, definition.hasPhysics());
            assertEquals(2, definition.geoModels().size());
            var definitionChunk = metadata.getFileView().blobChunkInfo(player.descriptor().blobId());
            assertNotNull(definitionChunk);
            var definitionBytes = InlineChunkReader.readPayloadBytes(input, definitionChunk);
            var reference = ProtobufJavaOracle.parse("mixel.asset.model.ModelData", definitionBytes);
            assertEquals(hasPhysics,
                reference.hasField(ProtobufJavaOracle.field(reference.getDescriptorForType(), "physics")));
            var geometries = ProtobufJavaOracle.field(reference.getDescriptorForType(), "geo_models");
            assertEquals(2, reference.getRepeatedFieldCount(geometries));
            for (int index = 0; index < reference.getRepeatedFieldCount(geometries); ++index) {
                var entry = (DynamicMessage)reference.getRepeatedField(geometries, index);
                var bytes = (ByteString)entry.getField(ProtobufJavaOracle.field(entry.getDescriptorForType(), "value"));
                var decoded = ProtobufJavaOracle.parse("mixel.asset.model.data.GeoModel", bytes.toByteArray());
                var cubes =
                    (ByteString)decoded.getField(ProtobufJavaOracle.field(decoded.getDescriptorForType(), "cubes"));
                var cubeData = ProtobufJavaOracle.parse("mixel.asset.model.data.Cubes", cubes.toByteArray());
                assertEquals(3, cubeData
                    .getRepeatedFieldCount(ProtobufJavaOracle.field(cubeData.getDescriptorForType(), "cubes_legacy")));
            }
            metadata.getCommon().readStringData(() -> false, source);
            metadata.getCommon().validateSoundContent(() -> false, source);
            try (var thumbnail = metadata.readThumbnail(() -> false, source)) {
                assertNotNull(thumbnail);
                assertEquals(1, thumbnail.width());
                assertEquals(1, thumbnail.height());
                try (var pixels = thumbnail.decodeToBuffer()) {
                    assertEquals(4, pixels.size());
                }
            }
            for (var texture : player.getTextureNames()) {
                try (var uv = player.textureSources(source, texture).uv().open(); var pixels = uv.decodeToBuffer()) {
                    assertEquals(16, uv.width());
                    assertEquals(16, uv.height());
                    assertEquals(16 * 16 * 4, pixels.size());
                }
            }
            if ("physics-export.mxc".equals(fixture.file)) {
                var physics = definition.physicsUnsafe();
                assertEquals(4, physics.nodes().size());
                assertEquals("hair_chain:hair", physics.constraints().get(0).presetTagUnsafe());
                assertEquals(1.5f, physics.nodes().get(2).linearDampingUnsafe());
                assertTrue(physics.nodes().get(2).disableWhenHiddenUnsafe());
                try (var geometry = NativeBuffer.copyOf(definition.geoModels().get("main"));
                    var payload = ProtoUtil.serializeToBuffer(physics);
                    var asset = NativePhysicsAsset.compile(geometry, payload);
                    var world = NativePhysicsWorld.create(asset)) {
                    assertNotEquals(0, world.get());
                }
            }
            if ("physics-p4-controls.mxc".equals(fixture.file)) {
                var joints = definition.physicsUnsafe().constraints();
                var springs =
                    joints.stream().filter(joint -> "spring_bounds".equals(joint.id())).findFirst().orElseThrow();
                assertEquals("control", springs.nodeA());
                assertEquals("tip", springs.nodeB());
                var fullTurn = joints.stream().filter(joint -> "full_turn_soft".equals(joint.id())).findFirst()
                    .orElseThrow().angularZUnsafe();
                assertEquals(LimitKind.SOFT, fullTurn.limitUnsafe());
                assertEquals(-(float)Math.PI, fullTurn.minUnsafe());
                assertEquals((float)Math.PI, fullTurn.maxUnsafe());
                assertEquals(2.5F, fullTurn.softLimitSpringUnsafe().frequencyDamping().frequency());
            }
        }
        var direct = ManagedContainer.openDirect(path, LOCATION);
        try {
            DirectContainerAdmission.requireEmbeddedPreview(direct);
        } finally {
            direct.representation().close();
        }
    }

    private static Stream<Fixture> fixtures() throws IOException {
        var summaries =
            lines("containers.tsv").collect(Collectors.toMap(line -> line.split("\t", 2)[0], Function.identity()));
        var cases = lines("cases.tsv").map(line -> line.split("\t", -1)).toList();
        assertEquals(summaries.keySet(), cases.stream().map(fields -> fields[0]).collect(Collectors.toSet()));
        assertEquals(summaries.size(), cases.size());
        return cases.stream().map(fields -> {
            assertEquals(3, fields.length);
            return new Fixture(fields[0], fields[1], fields[2], summaries.get(fields[0]));
        });
    }

    private static Stream<String> lines(String name) throws IOException {
        try (var input = new BufferedReader(new InputStreamReader(resource(name), StandardCharsets.UTF_8))) {
            return input.lines().skip(1).toList().stream();
        }
    }

    private static InputStream resource(String name) {
        return Objects.requireNonNull(PhysicsConformanceTest.class.getResourceAsStream(ROOT + name), name);
    }
}
