package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AxisConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollectionNameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollisionShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LimitKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsDefinitionProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeProto;
import com.elfmcys.ysm.testutil.ModelDefinitionFixture;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.util.ProtoUtil;
import org.apache.commons.io.FileUtils;
import us.hebi.quickbuf.ProtoSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import static com.elfmcys.ysm.format.schema.model.PhysicsWireFixture.*;

/** Explicit authoring tool. Verification always reads the checked-in artifacts. */
public final class PhysicsConformanceFixtureGenerator {
    private static final String GEOMETRY = """
        {"minecraft:geometry":[{"description":{"texture_width":16,"texture_height":16},"bones":[
          {"name":"body","pivot":[0,0,0],"cubes":[{"origin":[-2,0,-1],"size":[4,4,2],"uv":[0,0]}]},
          {"name":"hair","parent":"body","pivot":[0,3,0],"cubes":[{"origin":[-1,1,0],"size":[2,2,1],"uv":[0,0]}]},
          {"name":"tip","parent":"hair","pivot":[0,1,0],"cubes":[{"origin":[-1,-1,0],"size":[2,2,1],"uv":[0,0]}]},
          {"name":"child","parent":"hair","pivot":[0,0,0]}
        ]}]}
        """;
    private static final String PHYSICS = """
        {"collections":{"1":"Body and hair"},"nodes":[
          {"id":"body","bone":"body","type":"proxy",
           "shapes":[{"type":"box","center":[0,1,0],"size":[4,4,2]}]}],
         "presets":[{"id":"hair","type":"hair_chain","bones":["hair","tip"],"anchor_bone":"body",
                     "body":{"shapes":[{"type":"capsule","point_a":[0,0,0],"point_b":[0,-2,0],"radius":0.5}]}}]}
        """;
    private static final String BOUNDARIES =
        """
              {"gravity":[10000,-10000,0],"collections":{"1":"First","20":"Last"},"nodes":[
                {"id":"anchor","bone":"body","type":"anchor"},
                {"id":"control","bone":"hair","type":"dynamic","mass":0.0001,"linear_damping":10000,"gravity_scale":-10000,
                 "shapes":[{"type":"sphere","radius":0.001},{"type":"capsule","point_a":[0,0,0],"point_b":[0,0,0],"radius":10000},
                           {"type":"capsule","point_a":[0,-5000,0],"point_b":[0,5000,0],"radius":0.001},
                           {"type":"box","center":[0.00001,-10000,10000],"size":[10000,0.001,1]}]},
                {"id":"tip","bone":"tip","type":"dynamic","mass":1000000,"friction":10000,"bounciness":1,"angular_damping":10000,
                 "shapes":[{"type":"sphere","center":[10000,0,0],"radius":10000}]}],
               "constraints":[
                {"id":"point","type":"point","node_a":"anchor","node_b":"control","frame_a":{"pivot_offset":[-8,0,0]},
                 "frame_b":{},"angular_y":{"limit":{"kind":"locked"}},"angular_z":{"limit":{"kind":"hard","min_deg":179,"max_deg":180}}},
                {"id":"tip","type":"point","node_a":"control","node_b":"tip"},
                {"id":"free","type":"generic","node_a":"anchor","node_b":"control"},
                {"id":"full_turn","type":"generic","node_a":"anchor","node_b":"control",
                 "angular_x":{"limit":{"kind":"hard","min_deg":-180,"max_deg":180}}},
                {"id":"full_turn_soft","type":"generic","node_a":"anchor","node_b":"control",
                 "angular_z":{"limit":{"kind":"soft","min_deg":-180,"max_deg":180},
                              "soft_limit_spring":{"frequency":2.5}}},
                {"id":"zero_soft","type":"generic","node_a":"anchor","node_b":"control",
                 "linear_x":{"limit":{"kind":"soft","min":-10000,"max":10000},
                             "soft_limit_spring":{"mode":"stiffness_damping","stiffness":0,"damping":0}}},
            {"id":"spring_bounds","type":"generic","node_a":"control","node_b":"tip",
                 "linear_x":{"spring":{"target":"rest_pose","frequency":0.001,"damping_ratio":0}},
                 "linear_y":{"spring":{"target":"rest_pose","frequency":10000,"damping_ratio":10000}},
                 "linear_z":{"spring":{"target":"animation","mode":"stiffness_damping","stiffness":1000000000,"damping":1000000000}}}]}
              """;
    private static final CatalogLocation LOCATION =
        new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("physics-conformance"));
    private static final PhysicsConstraintProto CONNECT =
        PhysicsWireFixture.joint("point", "anchor", PhysicsConstraintKind.POINT);
    private static final PhysicsConstraintProto FREE =
        PhysicsWireFixture.joint("bridge", "anchor", PhysicsConstraintKind.GENERIC);
    private static final PhysicsNodeProto TIP = DYNAMIC.withId("tip").withBone("tip");
    private static final PhysicsConstraintProto TIP_LINK = CONNECT.withId("tip").withNodeA("strand").withNodeB("tip");

    private PhysicsConformanceFixtureGenerator() {}

    private record Rejection(String name, ModelDataProto definition, String diagnostic) {
    }

    public static void main(String[] args) throws Exception {
        NativeLibraryExtension.loadNativeLibrary();
        var output = Files.createDirectory(Path.of(args[0]));
        var work = Files.createTempDirectory("ysm-physics-fixtures-");
        try {
            generate(output, work);
        } finally {
            FileUtils.deleteDirectory(work.toFile());
        }
    }

    private static void generate(Path output, Path work) throws Exception {
        var cases = new ArrayList<String>();
        export(work, output, "animation-only", null);
        var portable = export(work, output, "physics-export", PHYSICS);
        export(work, output, "physics-p4-controls", BOUNDARIES);
        cases.add("animation-only.mxc\tM2\tabsent");
        cases.add("physics-export.mxc\tM2\tpresent");
        cases.add("physics-p4-controls.mxc\tM2\tpresent");

        var source = ManagedContainer.openDirect(portable, LOCATION);
        try {
            var admitted = source.modelFile().getPlayer().readDefinition(() -> false, source.chunks());
            var clean = admitted.withPhysics(
                PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC, ANCHOR, PROXY).addConstraints(CONNECT).build());
            var rejections = new ArrayList<Rejection>();
            structure(clean, rejections);
            geometry(clean, rejections);
            bodies(clean, rejections);
            shapes(clean, rejections);
            joints(clean, rejections);
            springs(clean, rejections);
            for (var rejection : rejections) {
                var name = "reject-" + rejection.name + ".mxc";
                ModelDefinitionFixture.rewritePlayerDefinition(source, rejection.definition, output.resolve(name));
                cases.add(name + "\tM2_CONTENT\t" + rejection.diagnostic.stripTrailing());
            }
        } finally {
            source.representation().close();
        }
        Files.writeString(output.resolve("cases.tsv"), "file\tresult\texpectation\n" + String.join("\n", cases) + "\n");
        try (var paths = Files.list(output)) {
            var summaries = new ArrayList<String>();
            for (var path : paths.filter(path -> path.getFileName().toString().endsWith(".mxc")).sorted().toList()) {
                try (var channel = FileChannel.open(path)) {
                    summaries.add(summary(path, AssetContainerReader.read(channel)));
                }
            }
            Files.writeString(output.resolve("containers.tsv"),
                "file\tsha256\tcontainer_version\tpreamble_size\tmodel_id\tchunks(type:encoding:offset:size:decode_size:hash)\n"
                    + String.join("\n", summaries) + "\n");
        }
    }

    private static Path export(Path work, Path output, String name, String physics) throws Exception {
        var raw = Files.createDirectory(work.resolve(name));
        var property = physics == null ? "" : ",\"physics\":\"physics.json\"";
        Files.writeString(raw.resolve("ysm.json"), """
            {"metadata":{"name":"Physics conformance"},
             "files":{"player":{"model":{"main":"main.json","arm":"arm.json"},"texture":["texture.png"]%s}}}
            """.formatted(property));
        Files.writeString(raw.resolve("main.json"), GEOMETRY);
        Files.writeString(raw.resolve("arm.json"), GEOMETRY.replace("\"child\"", "\"arm_only\""));
        var texture = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        var graphics = texture.createGraphics();
        try {
            graphics.setColor(new Color(170, 107, 63));
            graphics.fillRect(0, 0, 16, 16);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(texture, "png", raw.resolve("texture.png").toFile()))
            throw new IOException("No PNG writer for the raw fixture texture");
        if (physics != null)
            Files.writeString(raw.resolve("physics.json"), physics);
        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());
        try (var capture = importer.capture(raw); var preview = TestPreviews.blank()) {
            var compiled = importer.convert(capture, work.resolve(name + "-converted"));
            var source = ManagedContainer.openDirect(compiled.stagedContainer(), LOCATION);
            try {
                return ModelExporter.export(source, preview, output.resolve(name + ".mxc"), "physics conformance");
            } finally {
                source.representation().close();
            }
        }
    }

    static String summary(Path path, AssetContainerView view) throws Exception {
        var hex = HexFormat.of();
        var chunks =
            view.getChunkTable().values().stream().sorted(Comparator.comparingInt(AssetContainerView.ChunkInfo::offset))
                .map(chunk -> String.join(":", chunk.type(), chunk.encoding(), Integer.toString(chunk.offset()),
                    Integer.toString(chunk.size()), Integer.toString(chunk.decodeSize()),
                    chunk.hash() == null ? "-" : hex.formatHex(chunk.hash())))
                .collect(Collectors.joining(","));
        var qualifier = view.getQualifierVer();
        var version = AssetContainerConstant.CURRENT_MAJOR_VER + "." + view.getMinorVer() + "." + view.getPatchVer()
            + (qualifier.isEmpty() ? "" : "-" + qualifier);
        return String.join("\t", path.getFileName().toString(),
            hex.formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))), version,
            Integer.toString(view.getContainerPreambleSize()), view.getSchemaProperty(ModelFileConstant.PROP_MODEL_ID),
            chunks);
    }

    private static void structure(ModelDataProto clean, List<Rejection> cases) throws IOException {
        var physics = clean.physicsUnsafe();
        var nodes = physics.toBuilder();
        for (int i = physics.nodes().size(); i < 129; ++i)
            nodes.addNodes(ANCHOR.withId("anchor-" + i));
        add(cases, clean, "nodes-129", nodes.build(), "at most 128 nodes");
        var constraints = physics.toBuilder();
        for (int i = physics.constraints().size(); i < 257; ++i)
            constraints.addConstraints(CONNECT.withId("point-" + i));
        add(cases, clean, "constraints-257", constraints.build(), "at most 256 constraints");
        var shapes = DYNAMIC.toBuilder().clearShapes();
        for (int i = 0; i < 32; ++i)
            shapes.addShapes(SPHERE);
        var fullNodeShapes = shapes.build().shapes().toArray(CollisionShapeProto[]::new);
        node(cases, clean, "node-shapes-33", shapes.addShapes(BOX).build(), "at most 32 collision shapes");
        var model = PhysicsDefinitionProto.newBuilder().addNodes(ANCHOR);
        for (int i = 0; i < 16; ++i)
            model.addNodes(PROXY.toBuilder().clearShapes().addShapes(fullNodeShapes).setId("proxy-" + i).build());
        model.addNodes(DYNAMIC.toBuilder().clearShapes().addShapes(SPHERE).build()).addConstraints(CONNECT);
        add(cases, clean, "model-shapes-513", model.build(), "at most 512 collision shapes per model");

        add(cases, clean, "duplicate-node", physics.toBuilder().addNodes(ANCHOR).build(),
            "node 'anchor' has a duplicate id");
        add(cases, clean, "duplicate-joint", physics.toBuilder().addConstraints(CONNECT).build(),
            "constraint 'point' has a duplicate id");
        add(cases, clean, "duplicate-dynamic-bone", physics.toBuilder().addNodes(DYNAMIC.withId("other"))
            .addConstraints(CONNECT.withId("other").withNodeB("other")).build(), "duplicates dynamic bone 'hair'");
        node(cases, clean, "missing-binding", DYNAMIC.toBuilder().clearBone().build(),
            "needs an explicit bone binding");
        node(cases, clean, "absent-bone", DYNAMIC.withBone("missing"), "bone named 'missing'");
        node(cases, clean, "arm-only-bone", DYNAMIC.withBone("arm_only"), "bone named 'arm_only'");
        cases.add(new Rejection("missing-main",
            clean.toBuilder().clearGeoModels().putGeoModels("arm", clean.geoModels().get("arm")).build(),
            "Physics requires player/main geometry"));
        node(cases, clean, "dynamic-without-shape", DYNAMIC.toBuilder().clearShapes().build(),
            "at least one collision shape");
        add(cases, clean, "anchor-with-shape", physics.toBuilder().clearNodes()
            .addNodes(DYNAMIC, ANCHOR.toBuilder().addShapes(SPHERE).build(), PROXY).build(), "anchor can only bind");
        add(cases, clean, "proxy-on-dynamic",
            physics.toBuilder().clearNodes().addNodes(DYNAMIC, ANCHOR, PROXY.withBone("hair")).build(),
            "outside the dynamic bone lineages");
        add(cases, clean, "anchor-on-dynamic-descendant",
            physics.toBuilder().clearNodes().addNodes(DYNAMIC, ANCHOR.withBone("child"), PROXY).build(),
            "outside the dynamic bone lineages");
        for (int slot : new int[] {0, 21}) {
            add(cases, clean, "collection-slot-" + slot,
                physics.toBuilder()
                    .addCollections(CollectionNameProto.newBuilder().setSlot(slot).setName("invalid").build()).build(),
                "collection slot must be within 1..20: " + slot);
        }
        node(cases, clean, "collection-mask-high-bit", DYNAMIC.withCollectionMask(1 << 20),
            "collection mask must use only slots 1..20");

        joint(cases, clean, "self-joint", CONNECT.withNodeB("anchor"), "connects node 'anchor' to itself");
        joint(cases, clean, "missing-node", CONNECT.withNodeA("missing"), "references unknown node 'missing'");
        joint(cases, clean, "non-dynamic-joint", CONNECT.withNodeB("proxy"), "needs at least one dynamic endpoint");
        var chain = physics.toBuilder().clearConstraints().addNodes(TIP).addConstraints(TIP_LINK);
        for (var bridge : new PhysicsConstraintProto[] {FREE.withId("free"),
            FREE.withId("full-turn").withAngularX(hard(-(float)Math.PI, (float)Math.PI)),
            FREE.withId("full-turn-soft").withAngularZ(
                SOFT.withSpring(null).withMin(-(float)Math.PI).withMax((float)Math.PI).withSoftLimitSpring(FREQUENCY)),
            FREE.withId("zero-soft").withLinearX(SOFT.withSpring(null)
                .withSoftLimitSpring(STIFFNESS.withStiffnessDamping(STIFFNESS.stiffnessDamping().withDamping(0))))}) {
            add(cases, clean, "unanchored-" + bridge.id(), chain.build().toBuilder().addConstraints(bridge).build(),
                "via effective mechanical constraints");
        }
        add(cases, clean, "unanchored-chain", chain.build(), "via effective mechanical constraints");
    }

    private static void geometry(ModelDataProto clean, List<Rejection> cases) throws IOException {
        var main = GeoModelProto.parseFrom(ProtoSource.newInstance(clean.geoModels().get("main")));
        var body = main.bones().get(0);
        var hair = main.bones().get(1);
        var tip = main.bones().get(2);
        var child = main.bones().get(3);
        var bones = main.toBuilder().clearBones();
        var normal = bones.build();
        geometryCase(cases, clean, "bone-missing-parent", normal, "missing parent", body, hair.withParent("missing"),
            tip, child);
        geometryCase(cases, clean, "bone-cycle", normal, "bone cycle", body.withParent("hair"), hair, tip, child);
        geometryCase(cases, clean, "bone-duplicate", normal, "duplicate bone", body, hair, hair, tip, child);
        geometryCase(cases, clean, "bone-pivot-nan", normal, "pivot must be finite", body,
            hair.toBuilder().clearPivot().addPivot(0, Float.NaN, 0).build(), tip, child);
        geometryCase(cases, clean, "bone-rotation-infinite", normal, "rotate must be finite", body,
            hair.toBuilder().clearRotate().addRotate(0, 0, Float.POSITIVE_INFINITY).build(), tip, child);
        var overflow = new BoneProto[13];
        overflow[0] = body;
        for (int i = 1; i <= 10; ++i) {
            overflow[i] = hair.toBuilder().setName(i == 10 ? "hair" : "overflow-" + i).setParent(overflow[i - 1].name())
                .clearPivot().addPivot(i % 2 == 0 ? Float.MAX_VALUE : -Float.MAX_VALUE, 0, 0).clearRotate()
                .addRotate(0, 0, (float)Math.PI).build();
        }
        overflow[11] = tip;
        overflow[12] = child;
        geometryCase(cases, clean, "bind-pose-overflow", normal, "nonfinite bind pose", overflow);
    }

    private static void bodies(ModelDataProto clean, List<Rejection> cases) {
        node(cases, clean, "mass-low", DYNAMIC.withMass(0.000099f), " mass ");
        node(cases, clean, "mass-high", DYNAMIC.withMass(1_000_001f), " mass ");
        node(cases, clean, "friction-high", DYNAMIC.withFriction(1e30f), " friction ");
        node(cases, clean, "bounciness-high", DYNAMIC.withBounciness(1.01f), " bounciness ");
        node(cases, clean, "linear-damping-high", DYNAMIC.withLinearDamping(10_001f), " linear damping ");
        node(cases, clean, "angular-damping-low", DYNAMIC.withAngularDamping(-1f), " angular damping ");
        node(cases, clean, "gravity-scale-low", DYNAMIC.withGravityScale(-10_001f), " gravity scale ");
        node(cases, clean, "mass-nan", DYNAMIC.withMass(Float.NaN), " mass must be finite");
        add(cases, clean, "gravity-outside", clean.physicsUnsafe().toBuilder().addGravity(0, -10001, 0).build(),
            "Physics gravity");
        add(cases, clean, "gravity-nan", clean.physicsUnsafe().toBuilder().addGravity(0, Float.NaN, 0).build(),
            "Physics gravity");
    }

    private static void shapes(ModelDataProto clean, List<Rejection> cases) {
        shape(cases, clean, "unsupported-shape", CollisionShapeProto.newBuilder().build(), "no supported shape type");
        shape(cases, clean, "sphere-radius-quantized-zero", SPHERE.withSphere(SPHERE.sphere().withRadius(0)),
            " radius ");
        shape(cases, clean, "capsule-radius-low", CAPSULE.withCapsule(CAPSULE.capsule().withRadius(0.0009f)),
            " radius ");
        shape(cases, clean, "sphere-radius-high", SPHERE.withSphere(SPHERE.sphere().withRadius(10001f)), " radius ");
        shape(cases, clean, "box-size-high",
            BOX.withBox(BOX.box().toBuilder().clearSize().addSize(1, 10001, 1).build()), " box dimensions ");
        shape(cases, clean, "sphere-center-outside",
            SPHERE.withSphere(SPHERE.sphere().toBuilder().clearCenter().addCenter(10001, 0, 0).build()), " center ");
        shape(cases, clean, "capsule-endpoint-outside",
            CAPSULE.withCapsule(CAPSULE.capsule().toBuilder().clearPointA().addPointA(-10001, 0, 0).build()),
            " point A ");
        shape(cases, clean, "capsule-span-outside", CAPSULE.withCapsule(CAPSULE.capsule().toBuilder().clearPointA()
            .clearPointB().addPointA(-5000, 0, 0).addPointB(5001, 0, 0).build()), "capsule endpoint distance");
        shape(cases, clean, "shape-vector-infinite",
            BOX.withBox(BOX.box().toBuilder().clearSize().addSize(1, Float.POSITIVE_INFINITY, 1).build()),
            " size must be finite");
        shape(cases, clean, "shape-rotation-nan",
            BOX.withBox(BOX.box().toBuilder().clearRotation().addRotation(0, 0, Float.NaN, 1).build()),
            " rotation must be finite");
    }

    private static void joints(ModelDataProto clean, List<Rejection> cases) {
        joint(cases, clean, "pivot-outside",
            CONNECT.withFrameA(IDENTITY.toBuilder().clearPivotOffset().addPivotOffset(-10001, 0, 0).build()),
            " pivot offset ");
        joint(cases, clean, "basis-zero",
            CONNECT.withFrameA(IDENTITY.toBuilder().clearAxisX().addAxisX(0, 0, 0).build()),
            "orthonormal right-handed basis");
        joint(cases, clean, "basis-collinear",
            CONNECT.withFrameA(IDENTITY.toBuilder().clearAxisY().addAxisY(1, 0, 0).build()),
            "orthonormal right-handed basis");
        joint(cases, clean, "basis-left-handed",
            CONNECT.withFrameA(IDENTITY.toBuilder().clearAxisZ().addAxisZ(0, 0, -1).build()),
            "orthonormal right-handed basis");
        joint(cases, clean, "linear-limit-low", GENERIC.withLinearZ(hard(-10001, 0)), "within [-10000, 10000] pixels");
        joint(cases, clean, "linear-limit-high", GENERIC.withLinearZ(hard(0, 10001)), "within [-10000, 10000] pixels");
        joint(cases, clean, "limit-reversed", HINGE.withAngularZ(hard(0.5f, -0.5f)), "min <= max");
        joint(cases, clean, "hinge-outside", HINGE.withAngularZ(hard(0, 3.15f)),
            "angular Z limit must be within [-pi, pi]");
        joint(cases, clean, "twist-outside", CONNECT.withAngularX(hard(-3.15f, 0)),
            "angular X limit must be within [-pi, pi]");
        joint(cases, clean, "angular-limit-infinite", HINGE.withAngularZ(hard(0, Float.POSITIVE_INFINITY)),
            "finite limit bounds");
        joint(cases, clean, "cone-angle-high", POINT.withCone(POINT.coneUnsafe().withHalfAngle(3.15f)),
            "cone half-angle");
        var locked = AxisConfigProto.newBuilder().setLimit(LimitKind.LOCKED).build();
        var pole = hard((float)Math.PI, (float)Math.PI);
        joint(cases, clean, "swing-pole-locked", CONNECT.withAngularY(locked).withAngularZ(pole),
            "intersect the 179 degree disk");
        joint(cases, clean, "swing-pole-free", CONNECT.withAngularZ(pole), "intersect the 179 degree disk");
        var outside = hard((float)Math.toRadians(135), (float)Math.toRadians(150));
        joint(cases, clean, "swing-outside-disk", CONNECT.withAngularY(outside).withAngularZ(outside),
            "intersect the 179 degree disk");
    }

    private static void springs(ModelDataProto clean, List<Rejection> cases) {
        var free = AxisConfigProto.newBuilder().setLimit(LimitKind.FREE).build();
        var locked = free.withLimit(LimitKind.LOCKED);
        joint(cases, clean, "spring-on-locked", HINGE.withAngularZ(locked.withSpring(REST)),
            "locked axis cannot enable a spring");
        joint(cases, clean, "soft-limit-on-locked", HINGE.withAngularZ(locked.withSoftLimitSpring(FREQUENCY)),
            "require the soft limit kind");
        joint(cases, clean, "soft-limit-on-free", HINGE.withAngularZ(free.withSoftLimitSpring(FREQUENCY)),
            "require the soft limit kind");
        var freq = FREQUENCY.frequencyDamping();
        var coeff = STIFFNESS.stiffnessDamping();
        for (float frequency : new float[] {0, 10001}) {
            var spring = REST.withParameters(FREQUENCY.withFrequencyDamping(freq.withFrequency(frequency)));
            joint(cases, clean, frequency == 0 ? "frequency-quantized-zero" : "frequency-high",
                HINGE.withAngularZ(SOFT.withSpring(spring)), "target spring frequency");
        }
        var ratio = REST.withParameters(FREQUENCY.withFrequencyDamping(freq.withDampingRatio(10001)));
        joint(cases, clean, "damping-ratio-high", HINGE.withAngularZ(SOFT.withSpring(ratio)),
            "target spring damping ratio");
        joint(cases, clean, "stiffness-low",
            HINGE.withAngularZ(SOFT.withSoftLimitSpring(STIFFNESS.withStiffnessDamping(coeff.withStiffness(-1)))),
            "soft limit stiffness");
        joint(cases, clean, "spring-damping-high",
            HINGE.withAngularZ(SOFT.withSoftLimitSpring(STIFFNESS.withStiffnessDamping(coeff.withDamping(1.01e9f)))),
            "soft limit damping");
    }

    private static AxisConfigProto hard(float min, float max) {
        return AxisConfigProto.newBuilder().setLimit(LimitKind.HARD).setMin(min).setMax(max).build();
    }

    private static void shape(List<Rejection> cases, ModelDataProto clean, String name, CollisionShapeProto shape,
        String diagnostic) {
        node(cases, clean, name, DYNAMIC.toBuilder().clearShapes().addShapes(shape).build(), diagnostic);
    }

    private static void node(List<Rejection> cases, ModelDataProto clean, String name, PhysicsNodeProto node,
        String diagnostic) {
        add(cases, clean, name, clean.physicsUnsafe().toBuilder().clearNodes().addNodes(node, ANCHOR, PROXY).build(),
            diagnostic);
    }

    private static void joint(List<Rejection> cases, ModelDataProto clean, String name, PhysicsConstraintProto joint,
        String diagnostic) {
        add(cases, clean, name, clean.physicsUnsafe().toBuilder().clearConstraints().addConstraints(joint).build(),
            diagnostic);
    }

    private static void add(List<Rejection> cases, ModelDataProto clean, String name, PhysicsDefinitionProto physics,
        String diagnostic) {
        cases.add(new Rejection(name, clean.withPhysics(physics), diagnostic));
    }

    private static void geometryCase(List<Rejection> cases, ModelDataProto clean, String name, GeoModelProto main,
        String diagnostic, BoneProto... bones) throws IOException {
        var geometry = main.toBuilder().addBones(bones).build();
        cases.add(new Rejection(name,
            clean.toBuilder().putGeoModels("main", ByteBuffer.wrap(ProtoUtil.serializeToArray(geometry))).build(),
            diagnostic));
    }
}
