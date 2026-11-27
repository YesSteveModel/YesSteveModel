package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity;
import com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKey;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKind;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.catalog.source.SourceObservation;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ConvertedObjectStore;
import com.elfmcys.ysm.model.storage.ConvertedScope;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndexStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ConstraintFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LimitKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsDefinitionProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringTarget;
import com.elfmcys.ysm.testutil.ModelDefinitionFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RawPhysicsModelTest {
    private static final String GEOMETRY = """
        {"minecraft:geometry":[{"description":{"texture_width":16,"texture_height":16},"bones":[
          {"name":"body","pivot":[0,0,0],"rotation":[0,0,90]},
          {"name":"hair0","parent":"body","pivot":[-2,0,0],"rotation":[0,-90,0]},
          {"name":"hair1","parent":"hair0","pivot":[-4,0,0]},
          {"name":"hair2","parent":"hair1","pivot":[-6,0,0]},
          {"name":"coincident","parent":"body","pivot":[-2,0,0]},
          {"name":"skirt0","parent":"body","pivot":[1,-3,0]},
          {"name":"skirt1","parent":"body","pivot":[-1,-3,0]}
        ]}]}
        """;
    private static final String SPHERE = """
        {"type":"sphere","center":[0,-1,0],"radius":0.75}
        """;
    private static final String BODY = """
        {"mass":0.2,"shapes":[%s]}
        """.formatted(SPHERE);
    private static final String PRESETS = """
        {"presets":[
          {"id":"side","type":"lateral_link","node_a":"skirt/body/0","node_b":"skirt/body/1"},
          {"id":"braid","type":"hair_chain","bones":["hair0","hair1","hair2"],"anchor_bone":"body",
           "body":%1$s,
           "root_spring":{"target":"rest_pose","frequency":5,"damping_ratio":0.6},
           "tip_spring":{"target":"rest_pose","frequency":1,"damping_ratio":0.2},
           "overrides":{
              "hair1":{"body":{"collections":[],"linear_damping":0.3,"disable_when_hidden":false},
                       "joint":{"angular_y":{"spring":{"target":"off"}}}},
              "hair2":{"joint":{"type":"generic",
                                  "frame_a":{"pivot_offset":[1,0,0],"axis_x":[1,0,0],"axis_y":[0,1,0]},
                                  "frame_b":{},
                                  "angular_y":{"limit":{"kind":"hard","min_deg":-20,"max_deg":40}}}}
            }},
          {"id":"skirt","type":"skirt_panel","bones":["skirt0","skirt1"],"anchor":"braid/anchor",
           "body":%1$s,
           "joint":{"limit":{"kind":"hard","min_deg":-15,"max_deg":25},
                    "spring":{"target":"animation","mode":"stiffness_damping","stiffness":7,"damping":2}},
           "overrides":{"skirt1":{"joint":{"type":"generic",
                                          "angular_z":{"spring":{"target":"animation","frequency":2,
                                                                 "damping_ratio":0.8}}}}}}
        ]}
        """.formatted(BODY);

    @TempDir
    Path temporary;
    private final RawModelImporter importer = new RawModelImporter(DefaultAnimationFilter.keepAll());

    @Test
    void rawDeclarationControlsDiscoveryAndSourceHash() throws Exception {
        var raw = createRaw("raw", "");
        var physics = raw.resolve("physics.json");
        var original = hash(raw);
        Files.writeString(physics, "{broken");
        assertEquals(original, hash(raw));
        assertFalse(read(convert(raw, "undeclared")).hasPhysics());
        Files.writeString(raw.resolve("ysm.json"), manifest(",\"physics\":null"));
        var withoutPhysics = hash(raw);
        Files.writeString(physics, PRESETS);
        assertEquals(withoutPhysics, hash(raw));
        assertFalse(read(convert(raw, "null-path")).hasPhysics());

        Files.writeString(raw.resolve("ysm.json"), manifest(",\"physics\":\"physics.json\""));
        var first = hash(raw);
        Files.writeString(physics, PRESETS.replace("\"frequency\":5", "\"frequency\":4"));
        assertNotEquals(first, hash(raw));
        try (var captured = importer.capture(raw)) {
            var revised = captured.modelId();
            Files.writeString(physics, "invalid after capture");
            var compiled = importer.convert(captured, temporary.resolve("frozen"));
            assertEquals(revised, compiled.modelHash());
            assertEquals(4, read(compiled.stagedContainer()).physicsUnsafe().constraints().get(1).angularYUnsafe()
                .springUnsafe().parametersUnsafe().frequencyDamping().frequency());
        }

        Files.writeString(raw.resolve("ysm.json"), manifest(",\"physics\":\"\""));
        assertThrows(UncheckedIOException.class, () -> importer.capture(raw));
        Files.writeString(raw.resolve("ysm.json"), manifest(",\"physics\":\"missing.json\""));
        assertThrows(UncheckedIOException.class, () -> importer.capture(raw));
        Files.writeString(raw.resolve("ysm.json"), manifest(",\"physics\":42"));
        assertThrows(com.google.gson.JsonParseException.class, () -> importer.capture(raw));
    }

    @Test
    void sharedFrameReconstructsOneBindPoseThroughRotatedParents() throws Exception {
        var raw = createRaw("raw", ",\"physics\":\"physics.json\"");
        Files.writeString(raw.resolve("physics.json"), """
            {"collections":{"20":"Body","2":"Hair"},"gravity":[0,0,0],"nodes":[
               {"type":"proxy","id":"body","bone":"body","shapes":[
                 {"type":"capsule","point_a":[0,0,0],"point_b":[0,2,0],"radius":1},
                 {"type":"box","center":[0,0,0],"size":[2,3,4],"rotation_deg":[0,0,90]}]},
               {"type":"dynamic","id":"hair","bone":"hair0","shapes":[%s],"mass":0.2,"collections":[20,2]}],
             "constraints":[
               {"type":"fixed","id":"shared","node_a":"body","node_b":"hair",
                "frame":{"pivot_offset":[1,0,3],"rotation_deg":[0,0,90]}},
               {"type":"hinge","id":"explicit","node_a":"body","node_b":"hair",
                "frame_a":{"axis_x":[0,1,0],"axis_y":[0,0,1]},"frame_b":{"pivot_offset":[1,2,3]},
                "limit":{"kind":"soft","min_deg":-90,"max_deg":45},
                "spring":{"target":"animation","frequency":2,"damping_ratio":1},
                "soft_limit_spring":{"mode":"stiffness_damping","stiffness":3,"damping":0.2}}]}
            """.formatted(SPHERE));

        var physics = read(convert(raw, "converted")).physicsUnsafe();
        assertEquals(2, physics.collections().get(0).slot());
        assertEquals(20, physics.collections().get(1).slot());
        assertEquals(List.of(0f, 0f, 0f), physics.gravity());
        var body = physics.nodes().get(0);
        assertFalse(body.hasCollectionMask());
        assertEquals(0.5f, body.frictionUnsafe());
        assertEquals(List.of(0f, 2f, 0f), body.shapes().get(0).capsule().pointB());
        var box = body.shapes().get(1).box();
        assertEquals(List.of(2f, 3f, 4f), box.size_());
        assertEquals(Math.sqrt(0.5), box.rotation().getFloat(2), 1e-6);
        assertEquals(Math.sqrt(0.5), box.rotation().getFloat(3), 1e-6);
        assertEquals((1 << 19) | (1 << 1), physics.nodes().get(1).collectionMaskUnsafe());

        var shared = physics.constraints().get(0);
        // Invert Rz(90) on A and Rz(90) * Ry(90) on B using the hand-derived local frames.
        frame(shared.frameA(), new float[] {2, -1, 3}, new float[] {1, 0, 0}, new float[] {0, 1, 0},
            new float[] {0, 0, 1});
        frame(shared.frameB(), new float[] {-3, -1, 0}, new float[] {0, 0, 1}, new float[] {0, 1, 0},
            new float[] {-1, 0, 0});
        var explicit = physics.constraints().get(1);
        assertEquals(List.of(1f, 0f, 0f), explicit.frameA().axisZ());
        assertEquals(List.of(1f, 2f, 3f), explicit.frameB().pivotOffset());
        var hinge = explicit.angularZUnsafe();
        assertEquals(-Math.PI / 2, hinge.minUnsafe(), 1e-6);
        assertEquals(Math.PI / 4, hinge.maxUnsafe(), 1e-6);
        assertEquals(SpringTarget.ANIMATION, hinge.springUnsafe().target());
        assertEquals(3, hinge.softLimitSpringUnsafe().stiffnessDamping().stiffnessUnsafe());
    }

    @Test
    void presetExpansionSurvivesArchiveCaptureAndPortableExport() throws Exception {
        var raw = createRaw("raw", ",\"physics\":\"physics.json\"");
        Files.writeString(raw.resolve("physics.json"), PRESETS);
        var path = convert(raw, "directory");
        var fromDirectory = read(path);
        var archive = zip(raw);
        var fromArchivePath = convert(archive, "archive");
        assertEquals(fromDirectory, read(fromArchivePath));

        var output = temporary.resolve("portable.mxc");
        export(path, output);
        Files.move(raw, temporary.resolve("relocated"));
        Files.delete(path);
        Files.delete(archive);
        Files.delete(fromArchivePath);
        var exported = open(output);
        try {
            DirectContainerAdmission.requireEmbeddedPreview(exported);
            var definition = exported.modelFile().getPlayer().readDefinition(() -> false, exported.chunks());
            assertEquals(fromDirectory, definition);
            assertEquals(read(output), definition);
            assertFalse(new String(Files.readAllBytes(output), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("physics.json"));

            var physics = definition.physicsUnsafe();
            var hair0 = physics.nodes().get(1);
            var hair1 = physics.nodes().get(2);
            assertEquals("braid/body/0", hair0.id());
            assertEquals("hair_chain:braid", hair0.presetTagUnsafe());
            assertEquals(1.5f, hair0.linearDampingUnsafe());
            assertTrue(hair0.disableWhenHiddenUnsafe());
            assertEquals(0, hair1.collectionMaskUnsafe());
            assertEquals(0.3f, hair1.linearDampingUnsafe());
            assertFalse(hair1.disableWhenHiddenUnsafe());
            assertTrue(physics.nodes().get(4).disableWhenHiddenUnsafe());

            var lateral = physics.constraints().get(0);
            assertEquals("lateral_link:side", lateral.presetTagUnsafe());
            assertFalse(lateral.disableCollisionsUnsafe());
            assertEquals(LimitKind.SOFT, lateral.linearXUnsafe().limitUnsafe());
            assertEquals(SpringTarget.REST_POSE, lateral.linearXUnsafe().springUnsafe().target());
            assertTrue(lateral.linearXUnsafe().hasSoftLimitSpring());
            assertVector(lateral.frameA().pivotOffset(), new float[] {1, -1, 0});
            assertVector(lateral.frameB().pivotOffset(), new float[] {-1, -1, 0});
            var root = physics.constraints().get(1);
            var middle = physics.constraints().get(2);
            var tip = physics.constraints().get(3);
            assertEquals("hair_chain:braid", root.presetTagUnsafe());
            assertEquals(Math.toRadians(35), root.coneUnsafe().halfAngleUnsafe(), 1e-6);
            assertEquals(5, root.angularYUnsafe().springUnsafe().parametersUnsafe().frequencyDamping().frequency());
            assertEquals(SpringTarget.OFF, middle.angularYUnsafe().springUnsafe().target());
            assertEquals(root.coneUnsafe(), middle.coneUnsafe());
            assertEquals(3, middle.angularZUnsafe().springUnsafe().parametersUnsafe().frequencyDamping().frequency());
            assertEquals(0.4f,
                middle.angularZUnsafe().springUnsafe().parametersUnsafe().frequencyDamping().dampingRatioUnsafe(),
                1e-6);
            assertEquals(1, tip.angularYUnsafe().springUnsafe().parametersUnsafe().frequencyDamping().frequency());
            assertEquals(PhysicsConstraintKind.GENERIC, tip.kind());
            assertFalse(tip.hasCone());
            assertEquals(LimitKind.HARD, tip.angularYUnsafe().limitUnsafe());
            assertEquals(List.of(1f, 0f, 0f), tip.frameA().pivotOffset());
            assertVector(root.frameB().axisX(), new float[] {1, 0, 0});
            var skirt = physics.constraints().get(4);
            assertEquals("skirt_panel:skirt", skirt.presetTagUnsafe());
            assertEquals(PhysicsConstraintKind.HINGE, skirt.kind());
            assertEquals(SpringTarget.ANIMATION, skirt.angularZUnsafe().springUnsafe().target());
            assertEquals(PhysicsConstraintKind.GENERIC, physics.constraints().get(5).kind());
        } finally {
            exported.representation().close();
        }
    }

    @Test
    void failedRawReloadAndFailedExportPreserveExistingArtifact() throws Exception {
        var raw = createRaw("raw", ",\"physics\":\"physics.json\"");
        Files.writeString(raw.resolve("physics.json"), PRESETS);
        var valid = convert(raw, "valid");
        var output = temporary.resolve("portable.mxc");
        var corrupt = temporary.resolve("invalid-definition.mxc");
        var original = open(valid);
        try (var preview = TestPreviews.blank()) {
            try {
                ModelExporter.export(original, preview, output, null);
                var model = read(valid);
                var physics = model.physicsUnsafe();
                var invalidBody = physics.nodes().get(1).withMass(1e-5f);
                var invalidPhysics = physics.toBuilder().clearNodes();
                for (var body : physics.nodes()) {
                    invalidPhysics.addNodes(body.id().equals(invalidBody.id()) ? invalidBody : body);
                }
                var bad = model.withPhysics(invalidPhysics.build());
                ModelDefinitionFixture.rewritePlayerDefinition(original, bad, corrupt);
            } finally {
                original.representation().close();
            }
            var bytes = Files.readAllBytes(output);
            Files.writeString(raw.resolve("physics.json"), "{malformed");
            var failure = assertThrows(UncheckedIOException.class, () -> convert(raw, "staging"));
            assertTrue(failure.getCause().getMessage().contains("invalid JSON"));
            try (var staged = Files.list(temporary.resolve("staging"))) {
                assertEquals(0, staged.count());
            }
            Files.writeString(raw.resolve("physics.json"), PRESETS.replace("\"mass\":0.2", "\"mass\":0.00001"));
            var invalidRaw = assertThrows(UncheckedIOException.class, () -> convert(raw, "staging"));
            assertTrue(invalidRaw.getCause().getMessage().contains("mass must be finite and within"),
                invalidRaw::getMessage);
            var content = open(corrupt);
            try {
                var error = assertThrows(AssetLoadException.class,
                    () -> ModelExporter.export(content, preview, output, null, replacement -> fail("committed")));
                assertTrue(error.getMessage().contains("braid/body/0"));
                assertTrue(error.getMessage().contains("mass must be finite and within"));
            } finally {
                content.representation().close();
            }
            assertArrayEquals(bytes, Files.readAllBytes(output));
            try (var files = Files.list(temporary)) {
                assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith("portable.mxc.tmp-")));
            }
        }
        var neighbor = createRaw("neighbor", "");
        assertFalse(read(convert(neighbor, "neighbor-converted")).hasPhysics());
    }

    @Test
    void catalogReloadRecompilesNamedModelAndIsolatesInvalidPhysics() throws Exception {
        var raw = createRaw("raw", ",\"physics\":\"physics.json\"");
        Files.writeString(raw.resolve("ysm.json"), manifest(",\"physics\":\"physics.json\"").replaceFirst("\\{",
            "{\"owner\":\"author\",\"name\":\"named-physics\","));
        var physics = raw.resolve("physics.json");
        Files.writeString(physics, PRESETS);
        var neighbor = createRaw("neighbor", "");

        try (var cache = new AtomicSharedCache(temporary.resolve("cache"))) {
            var resolver = new ModelSourceResolver(importer,
                new ConvertedSourceIndexStore(ConvertedScope.of(temporary, "test-schema"), cache),
                new ConvertedObjectStore(cache));
            var first = resolver.resolveMaterialized(observe(raw));
            try (var initial = first.resolvedContent()) {
                Files.writeString(physics, PRESETS.replace("\"frequency\":5", "\"frequency\":4"));
                var reload = resolver.resolveMaterialized(observe(raw), initial.content());
                try (var updated = reload.resolvedContent()) {
                    assertEquals(first.entry().modelId(), reload.entry().modelId());
                    assertNotEquals(first.entry().identity(), reload.entry().identity());
                    var target = updated.content().modelFile().getPlayer();
                    var model = target.readDefinition(() -> false, updated.content().chunks());
                    assertEquals(4, model.physicsUnsafe().constraints().get(1).angularYUnsafe().springUnsafe()
                        .parametersUnsafe().frequencyDamping().frequency());
                }
                Files.writeString(physics, "{malformed");
                var failed = assertThrows(UncheckedIOException.class, () -> resolver.resolveMaterialized(observe(raw)));
                assertTrue(failed.getCause().getMessage().contains("invalid JSON"));
                try (var unaffected = resolver.resolveMaterialized(observe(neighbor)).resolvedContent()) {
                    var model = unaffected.content().modelFile().getPlayer().readDefinition(() -> false,
                        unaffected.content().chunks());
                    assertFalse(model.hasPhysics());
                }
            }
        }
    }

    private SourceObservation observe(Path raw) throws Exception {
        var key = new ModelSourceKey(new CatalogRootIdentity(CatalogRootKind.MODELS, temporary),
            new ModelPath(raw.getFileName().toString()), ModelSourceKind.CURRENT_RAW_DIRECTORY);
        return ModelSourceDiscovery.observe(key, raw);
    }

    @ParameterizedTest
    @CsvSource({"mesh, unsupported shape type 'mesh'", "convex_hull, unsupported shape type 'convex_hull'",
        "missing-bone, exactly one player/main", "duplicate-target, Duplicate field",
        "multiple-turns, limit must be within", "bad-frame, orthonormal right-handed basis",
        "coincident, coincident bind pivots", "single, single segment"})
    void rejectedInputIdentifiesThePathAndCause(String variant, String message) throws Exception {
        var raw = createRaw("raw", ",\"physics\":\"physics.json\"");
        var preset = """
            {"presets":[{"id":"hair","type":"hair_chain","bones":[%s],"anchor_bone":"body","body":%s}]}
            """;
        var joint = """
            {"nodes":[{"id":"a","type":"anchor","bone":"body"},
                      {"id":"d","type":"dynamic","bone":"%s","mass":1,"shapes":[%s]}],
             "constraints":[{"id":"j","type":"hinge","node_a":"a","node_b":"d"%s}]}
            """;
        var json = switch (variant) {
            case "mesh", "convex_hull" -> joint.formatted("hair0", SPHERE.replace("sphere", variant), "");
            case "missing-bone" -> joint.formatted("armOnly", SPHERE, "");
            case "duplicate-target" ->
                joint.formatted("hair0", SPHERE, ",\"spring\":{\"target\":\"rest_pose\",\"target\":\"animation\"}");
            case "multiple-turns" ->
                joint.formatted("hair0", SPHERE, ",\"limit\":{\"kind\":\"hard\",\"min_deg\":0,\"max_deg\":360}");
            case "bad-frame" -> joint.formatted("hair0", SPHERE, ",\"frame\":{\"axis_x\":[1,0,0],\"axis_y\":[1,1,0]}");
            case "coincident" -> preset.formatted("\"hair0\",\"coincident\"", BODY);
            case "single" -> preset.formatted("\"hair0\"", BODY);
            default -> throw new IllegalArgumentException(variant);
        };
        Files.writeString(raw.resolve("physics.json"), json);
        var error = assertThrows(UncheckedIOException.class, () -> convert(raw, "converted")).getCause();
        assertTrue(error.getMessage().contains("physics.json"), error::getMessage);
        assertTrue(error.getMessage().contains(message), error::getMessage);
    }

    @Test
    void singleHairSegmentUsesExplicitAxisAndRootSpring() throws Exception {
        var raw = createRaw("raw", ",\"physics\":\"physics.json\"");
        Files.writeString(raw.resolve("physics.json"), """
            {"presets":[{"type":"hair_chain","id":"one","bones":["hair0"],"axis":[0,1,0],"anchor_bone":"body",
             "body":%s,
             "root_spring":{"target":"rest_pose","mode":"stiffness_damping","stiffness":6,"damping":1},
             "tip_spring":{"target":"rest_pose","mode":"stiffness_damping","stiffness":2,"damping":3}}]}
            """.formatted(BODY));
        var joint = read(convert(raw, "converted")).physicsUnsafe().constraints().get(0);
        assertVector(joint.frameA().axisX(), new float[] {1, 0, 0});
        assertEquals(6, joint.angularYUnsafe().springUnsafe().parametersUnsafe().stiffnessDamping().stiffnessUnsafe());
        assertEquals(1, joint.angularYUnsafe().springUnsafe().parametersUnsafe().stiffnessDamping().dampingUnsafe());
    }

    @Test
    void rawDefaultsUseBlenderValuesAndNormalizeAuthoredAxes() throws Exception {
        var physics = compile("""
            {"nodes":[{"id":"a","type":"anchor","bone":"body"},
                      {"id":"d","type":"dynamic","bone":"hair0","shapes":[
                         {"type":"sphere","radius":0.001},{"type":"box","size":[1,2,3]}]}],
             "constraints":[{"id":"pair","type":"point","node_a":"a","node_b":"d",
                            "frame_a":{"axis_x":[1,1,0],"axis_y":[-1,1,0]},"frame_b":{},
                            "angular_x":{"spring":{"target":"rest_pose","mode":"stiffness_damping"}},
                            "angular_y":{"spring":{"target":"animation","frequency":2}},
                            "angular_z":{"limit":{"kind":"hard","min_deg":179,"max_deg":180}}}]}
            """, "defaults");
        var node = physics.nodes().get(1);
        assertEquals(1, node.massUnsafe());
        assertEquals(0.04f, node.linearDampingUnsafe());
        assertEquals(0.1f, node.angularDampingUnsafe());
        assertEquals(List.of(0f, 0f, 0f), node.shapes().get(0).sphere().center());
        assertEquals(List.of(0f, 0f, 0f), node.shapes().get(1).box().center());
        assertEquals(List.of(0f, 0f, 0f, 1f), node.shapes().get(1).box().rotation());
        var joint = physics.constraints().get(0);
        float diagonal = (float)Math.sqrt(0.5);
        frame(joint.frameA(), new float[3], new float[] {diagonal, diagonal, 0}, new float[] {-diagonal, diagonal, 0},
            new float[] {0, 0, 1});
        var spring = joint.angularXUnsafe().springUnsafe().parametersUnsafe().stiffnessDamping();
        assertEquals(10, spring.stiffnessUnsafe());
        assertEquals(0.5f, spring.dampingUnsafe());
        assertEquals(0.5f,
            joint.angularYUnsafe().springUnsafe().parametersUnsafe().frequencyDamping().dampingRatioUnsafe());
        assertEquals((float)Math.toRadians(179), joint.angularZUnsafe().minUnsafe());
    }

    @Test
    void emptyParentDoesNotAttachToTheUnnamedBone() throws Exception {
        var raw = createRaw("raw", ",\"physics\":\"physics.json\"");
        Files.writeString(raw.resolve("main.json"), GEOMETRY.replace("{\"name\":\"body\",",
            "{\"name\":\"\",\"pivot\":[3,4,5],\"rotation\":[0,90,90]}," + "{\"name\":\"body\",\"parent\":\"\","));
        Files.writeString(raw.resolve("physics.json"), """
            {"nodes":[{"id":"a","type":"anchor","bone":"body"},
                      {"id":"d","type":"dynamic","bone":"hair0","shapes":[%s]}],
             "constraints":[{"id":"pair","type":"fixed","node_a":"a","node_b":"d"}]}
            """.formatted(SPHERE));
        var compiled = read(convert(raw, "compiled")).physicsUnsafe();
        frame(compiled.constraints().get(0).frameA(), new float[] {2, 0, 0}, new float[] {0, -1, 0},
            new float[] {1, 0, 0}, new float[] {0, 0, 1});
    }

    @Test
    void defaultsLateralPivotToShapeCentersWithAnchorFallbackAndRespectsExplicitFrame() throws Exception {
        var physics = compile("""
            {"nodes":[{"id":"a","type":"anchor","bone":"body"},
                      {"id":"d","type":"dynamic","bone":"skirt0","shapes":[
                         {"type":"box","center":[0,-4,0],"size":[1,2,3]},
                         {"type":"capsule","point_a":[0,0,0],"point_b":[0,-4,0],"radius":1}]}],
             "presets":[{"id":"default","type":"lateral_link","node_a":"a","node_b":"d"},
                        {"id":"explicit","type":"lateral_link","node_a":"a","node_b":"d","joint":{"frame":{}}}]}
            """, "lateral");
        var implicit = physics.constraints().get(0);
        assertVector(implicit.frameA().pivotOffset(), new float[] {-0.5f, -3, 0});
        assertVector(implicit.frameB().pivotOffset(), new float[] {0.5f, 0, 0});
        var explicit = physics.constraints().get(1);
        assertVector(explicit.frameA().pivotOffset(), new float[] {-1, -3, 0});
        assertVector(explicit.frameB().pivotOffset(), new float[3]);
    }

    @Test
    void overridesSwingLimitsAndIndependentTargetSpringsFieldByField() throws Exception {
        var implicit = compile(hair("", "{\"angular_y\":{\"spring\":{\"target\":\"off\"}}}"), "implicit");
        var explicit = compile(hair(",\"joint\":{\"cone\":{\"limit_kind\":\"hard\",\"half_angle_deg\":35}}",
            "{\"angular_y\":{\"spring\":{\"target\":\"off\"}}}"), "explicit");
        assertEquals(implicit, explicit);
        assertTrue(implicit.constraints().get(0).hasCone());

        var free = compile(hair("", "{\"angular_y\":{\"limit\":{\"kind\":\"free\"}}}"), "free").constraints().get(0);
        assertFalse(free.hasCone());
        assertEquals(LimitKind.FREE, free.angularYUnsafe().limitUnsafe());
        assertEquals(SpringTarget.REST_POSE, free.angularYUnsafe().springUnsafe().target());
        var sharedFree =
            compile(hair(",\"joint\":{\"angular_y\":{\"limit\":{\"kind\":\"free\"}}}", "{}"), "shared-free")
                .constraints().get(0);
        assertTrue(sharedFree.hasCone());

        var soft = """
            ,"joint":{"angular_y":{"limit":{"kind":"soft","min_deg":-15,"max_deg":15},
                                   "soft_limit_spring":{"frequency":3}}}
            """;
        var hard = compile(hair(soft, "{\"angular_y\":{\"limit\":{\"kind\":\"hard\"}}}"), "hard").constraints().get(0);
        assertEquals(LimitKind.HARD, hard.angularYUnsafe().limitUnsafe());
        assertEquals((float)Math.toRadians(-15), hard.angularYUnsafe().minUnsafe());
        assertFalse(hard.angularYUnsafe().hasSoftLimitSpring());
        assertEquals(SpringTarget.REST_POSE, hard.angularYUnsafe().springUnsafe().target());

        var cleared =
            compile(hair(soft, "{\"angular_y\":{\"limit\":{\"kind\":\"free\"}}}"), "cleared").constraints().get(0);
        assertFalse(cleared.angularYUnsafe().hasSoftLimitSpring());
        assertTrue(cleared.angularYUnsafe().hasSpring());

        var coneSource = "{\"cone\":{\"limit_kind\":\"hard\",\"half_angle_deg\":40}}";
        var cone = compile(hair(soft, coneSource), "cone").constraints().get(0);
        assertTrue(cone.hasCone());
        assertFalse(cone.angularYUnsafe().hasLimit());
        assertFalse(cone.angularYUnsafe().hasSoftLimitSpring());
        assertTrue(cone.angularYUnsafe().hasSpring());
        var failed = assertThrows(UncheckedIOException.class,
            () -> compile(
                hair(soft, coneSource.replace("40}}", "40},\"angular_y\":{\"limit\":{\"kind\":\"locked\"}}}")),
                "conflict"));
        assertTrue(failed.getCause().getMessage().contains("same preset layer"), failed::getMessage);
    }

    @Test
    void lateralAndSkirtOverlaysDropTheSoftLimitSpringWhenTheLimitKindChanges() throws Exception {
        var physics = compile("""
            {"presets":[
              {"type":"skirt_panel","id":"skirt","bones":["skirt0","skirt1"],"anchor_bone":"body","body":%s,
               "joint":{"limit":{"kind":"soft","min_deg":-30,"max_deg":30},
                        "soft_limit_spring":{"frequency":3},"spring":{"target":"animation","frequency":2}},
               "overrides":{"skirt1":{"joint":{"limit":{"kind":"hard"}}}}},
              {"type":"lateral_link","id":"link","node_a":"skirt/body/0","node_b":"skirt/body/1",
               "joint":{"linear_x":{"limit":{"kind":"free"}},"linear_y":{"limit":{"kind":"hard"}}}}]}
            """.formatted(BODY), "overlay");
        var hinge = physics.constraints().get(1).angularZUnsafe();
        assertEquals(LimitKind.HARD, hinge.limitUnsafe());
        assertFalse(hinge.hasSoftLimitSpring());
        assertEquals(SpringTarget.ANIMATION, hinge.springUnsafe().target());
        var lateral = physics.constraints().get(2);
        for (var axis : List.of(lateral.linearXUnsafe(), lateral.linearYUnsafe())) {
            assertFalse(axis.hasSoftLimitSpring());
            assertEquals(SpringTarget.REST_POSE, axis.springUnsafe().target());
        }
    }

    @ParameterizedTest
    @CsvSource({"skirt_panel, hinge, generic", "skirt_panel, generic, hinge", "hair_chain, point, generic",
        "hair_chain, generic, point"})
    void switchesPresetJointTypesAndExportsTheMergedFieldSet(String preset, String sharedType, String overrideType)
        throws Exception {
        var bones = preset.equals("skirt_panel") ? List.of("skirt0", "skirt1") : List.of("hair0", "hair1");
        var shared = switch (sharedType) {
            case "hinge" -> """
                "limit":{"kind":"soft","min_deg":-15,"max_deg":25},
                "soft_limit_spring":{"frequency":3},"spring":{"target":"animation","frequency":2}
                """;
            case "point" -> """
                "cone":{"limit_kind":"hard","half_angle_deg":25},
                "angular_x":{"limit":{"kind":"hard","min_deg":-30,"max_deg":30}}
                """;
            case "generic" -> """
                "linear_x":{"limit":{"kind":"locked"}},
                "angular_x":{"limit":{"kind":"hard","min_deg":-30,"max_deg":30}}
                """;
            default -> throw new IllegalArgumentException(sharedType);
        };
        var override = switch (overrideType) {
            case "hinge" -> """
                ,"limit":{"kind":"hard","min_deg":-10,"max_deg":40},
                "spring":{"target":"rest_pose","frequency":4}
                """;
            case "generic" -> """
                ,"linear_x":{"limit":{"kind":"locked"}}
                """;
            case "point" -> "";
            default -> throw new IllegalArgumentException(overrideType);
        };
        var raw = createRaw("switch", ",\"physics\":\"physics.json\"");
        Files.writeString(raw.resolve("physics.json"), """
            {"presets":[{"id":"parts","type":"%s","bones":["%s","%s"],"anchor_bone":"body","body":%s,
             "joint":{"type":"%s","disable_collisions":false,"frame_a":{"pivot_offset":[2,0,0]},
                      "frame_b":{"pivot_offset":[0,3,0]},%s},
             "overrides":{"%s":{"joint":{"type":"%s"%s}}}}]}
            """.formatted(preset, bones.get(0), bones.get(1), BODY, sharedType, shared, bones.get(1), overrideType,
            override));
        var staged = convert(raw, "converted");
        var definition = read(staged);
        var joints = definition.physicsUnsafe().constraints();
        var unchanged = joints.get(0);
        var switched = joints.get(1);
        assertEquals(sharedType.toUpperCase(Locale.ROOT), unchanged.kind().name());
        assertEquals(overrideType.toUpperCase(Locale.ROOT), switched.kind().name());
        for (var joint : joints) {
            assertFalse(joint.disableCollisionsUnsafe());
            assertVector(joint.frameA().pivotOffset(), new float[] {2, 0, 0});
            assertVector(joint.frameB().pivotOffset(), new float[] {0, 3, 0});
        }
        assertEquals(sharedType.equals("generic"), unchanged.hasLinearX());
        assertEquals(sharedType.equals("point"), unchanged.hasCone());
        assertEquals(overrideType.equals("generic"), switched.hasLinearX());
        assertEquals(overrideType.equals("point"), switched.hasCone());
        if (switched.kind() == PhysicsConstraintKind.HINGE) {
            assertFalse(switched.hasAngularX());
            assertEquals(LimitKind.HARD, switched.angularZUnsafe().limitUnsafe());
            assertEquals((float)Math.toRadians(40), switched.angularZUnsafe().maxUnsafe());
            assertEquals(SpringTarget.REST_POSE, switched.angularZUnsafe().springUnsafe().target());
        } else if (unchanged.kind() == PhysicsConstraintKind.HINGE) {
            assertTrue(unchanged.angularZUnsafe().hasSoftLimitSpring());
            assertFalse(switched.hasAngularZ());
        } else {
            assertEquals(unchanged.angularXUnsafe(), switched.angularXUnsafe());
        }

        var output = temporary.resolve("switched.mxc");
        export(staged, output);
        Files.move(raw, temporary.resolve("relocated"));
        Files.delete(staged);
        assertEquals(definition, read(output));
    }

    @Test
    void validatesEachPresetLayerBeforeStrippingInheritedFields() {
        var generic = """
            {"type":"generic","linear_x":{"limit":{"kind":"locked"}}}
            """;
        var hingeWithGenericField = generic.replace("generic", "hinge");
        var template = """
            {"presets":[{"id":"skirt","type":"skirt_panel","bones":["skirt0","skirt1"],"anchor_bone":"body","body":%s,
             "joint":%s,"overrides":{"skirt0":{"joint":%s},"skirt1":{"joint":%s}}}]}
            """;
        var badShared = template.formatted(BODY, hingeWithGenericField, generic, generic);
        var shared = assertThrows(UncheckedIOException.class, () -> compile(badShared, "bad-shared"));
        assertTrue(shared.getCause().getMessage().contains("field 'joint' has unknown field 'linear_x'"),
            shared::getMessage);
        var badOverride = template.formatted(BODY, generic, hingeWithGenericField, generic);
        var override = assertThrows(UncheckedIOException.class, () -> compile(badOverride, "bad-override"));
        assertTrue(
            override.getCause().getMessage().contains("field 'skirt0' field 'joint' has unknown field 'linear_x'"),
            override::getMessage);
    }

    @Test
    void rawFullTurnSoftLimitCannotBeTheOnlyAnchorPath() throws Exception {
        var full = """
            {"nodes":[{"id":"anchor","type":"anchor","bone":"body"},
                      {"id":"strand","type":"dynamic","bone":"hair0","shapes":[%s]}],
             "constraints":[{"id":"joint","type":"generic","node_a":"anchor","node_b":"strand",
               "angular_z":{"limit":{"kind":"soft","min_deg":-180,"max_deg":180},
                            "soft_limit_spring":{"frequency":2}}}]}
            """.formatted(SPHERE);
        var isolated = assertThrows(UncheckedIOException.class, () -> compile(full, "full-turn"));
        assertTrue(isolated.getCause().getMessage().contains("via effective mechanical constraints"),
            isolated::getMessage);
        var bounded = compile(full.replace("180", "90"), "bounded").constraints().get(0).angularZUnsafe();
        assertEquals((float)(Math.PI / 2), bounded.maxUnsafe());
    }

    @Test
    void hairAxesFollowLocalBindRollWithParallelFallback() throws Exception {
        var source = """
            {"presets":[{"type":"hair_chain","id":"one","bones":["hair0"],"axis":%s,"anchor_bone":"body","body":%s}]}
            """;
        var vertical = compile(source.formatted("[0,0,-1]", BODY), "vertical").constraints().get(0).frameB();
        frame(vertical, new float[3], new float[] {1, 0, 0}, new float[] {0, 1, 0}, new float[] {0, 0, 1});
        float diagonal = (float)Math.sqrt(0.5);
        var oblique = compile(source.formatted("[1,1,0]", BODY), "oblique").constraints().get(0).frameB();
        frame(oblique, new float[3], new float[] {0, -diagonal, diagonal}, new float[] {1, 0, 0},
            new float[] {0, diagonal, diagonal});
        var parallel = compile(source.formatted("[0,-1,0]", BODY), "parallel").constraints().get(0).frameB();
        frame(parallel, new float[3], new float[] {0, 0, -1}, new float[] {0, 1, 0}, new float[] {1, 0, 0});
    }

    @Test
    void rawNumbersAreAcceptedOrRejectedAfterFloat32Encoding() throws Exception {
        var source = """
            {"nodes":[{"id":"a","type":"anchor","bone":"body"},
                      {"id":"d","type":"dynamic","bone":"hair0","shapes":[%s],
                       "linear_damping":-1e-100,"mass":%s}],
             "constraints":[{"id":"pair","type":"fixed","node_a":"a","node_b":"d"}]}
            """;
        var rounded = compile(source.formatted(SPHERE, "0.0000999999999"), "boundary").nodes().get(1);
        assertEquals(1e-4f, rounded.massUnsafe());
        assertTrue(rounded.linearDampingUnsafe() == 0);
        var failure =
            assertThrows(UncheckedIOException.class, () -> compile(source.formatted(SPHERE, "1e-100"), "underflow"));
        assertTrue(failure.getCause().getMessage().contains("mass must be finite and within"), failure::getMessage);
        var upper = SPHERE.replace("0,-1,0", "0,10000.00048828125000001,0");
        var roundedOutside =
            assertThrows(UncheckedIOException.class, () -> compile(source.formatted(upper, "1"), "outside"));
        assertTrue(roundedOutside.getCause().getMessage().contains("center must be finite and within"),
            roundedOutside::getMessage);
    }

    private static String hair(String sharedJoint, String override) {
        return """
            {"presets":[{"type":"hair_chain","id":"one","bones":["hair0"],"axis":[0,1,0],"anchor_bone":"body",
                         "body":%s,"root_spring":{"target":"rest_pose","frequency":2}%s,
                         "overrides":{"hair0":{"joint":%s}}}]}
            """.formatted(BODY, sharedJoint, override);
    }

    private PhysicsDefinitionProto compile(String json, String name) throws Exception {
        var raw = createRaw(name, ",\"physics\":\"physics.json\"");
        Files.writeString(raw.resolve("physics.json"), json);
        return read(convert(raw, name + "-converted")).physicsUnsafe();
    }

    private static void frame(ConstraintFrameProto frame, float[] pivot, float[] x, float[] y, float[] z) {
        assertVector(frame.pivotOffset(), pivot);
        assertVector(frame.axisX(), x);
        assertVector(frame.axisY(), y);
        assertVector(frame.axisZ(), z);
    }

    private static void assertVector(List<Float> actual, float[] expected) {
        for (var i = 0; i < 3; i++) {
            assertEquals(expected[i], actual.get(i), 1e-6);
        }
    }

    private Path createRaw(String name, String declaration) throws Exception {
        var root = Files.createDirectory(temporary.resolve(name));
        Files.writeString(root.resolve("ysm.json"), manifest(declaration));
        Files.writeString(root.resolve("main.json"), GEOMETRY);
        Files.writeString(root.resolve("arm.json"), GEOMETRY.replace("skirt1", "armOnly"));
        Files.write(root.resolve("texture.png"), Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
        return root;
    }

    private static String manifest(String physics) {
        return """
            {"metadata":{"name":"Physics"},
             "files":{"player":{"model":{"main":"main.json","arm":"arm.json"},"texture":["texture.png"]%s}}}
            """.formatted(physics);
    }

    private static ModelId hash(Path source) {
        try (var directory = new Directory(source)) {
            return ModelParser.scanModelHash(directory);
        }
    }

    private Path convert(Path raw, String output) {
        try (var captured = importer.capture(raw)) {
            return importer.convert(captured, temporary.resolve(output)).stagedContainer();
        }
    }

    private static ModelDataProto read(Path path) throws Exception {
        var model = open(path);
        try {
            return model.modelFile().getPlayer().readDefinition(() -> false, model.chunks());
        } finally {
            model.representation().close();
        }
    }

    private static void export(Path source, Path output) throws Exception {
        try (var preview = TestPreviews.blank()) {
            var compiled = open(source);
            try {
                ModelExporter.export(compiled, preview, output, null);
            } finally {
                compiled.representation().close();
            }
        }
    }

    private static ManagedContainer open(Path file) throws Exception {
        return ManagedContainer.openDirect(file, new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("physics")));
    }

    private Path zip(Path raw) throws Exception {
        var dest = temporary.resolve("raw.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(dest)); var files = Files.list(raw)) {
            for (var file : files.sorted().toList()) {
                zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        return dest;
    }

}
