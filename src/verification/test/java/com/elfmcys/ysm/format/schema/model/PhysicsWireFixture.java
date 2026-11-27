package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AxisConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoxShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CapsuleShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollectionNameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollisionShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ConeConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ConstraintFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.FrequencyDampingProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoPropertiesProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LimitKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsDefinitionProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SphereShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringParametersProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringTarget;
import com.elfmcys.ysm.proto.mixel.asset.model.data.StiffnessDampingProto;
import com.elfmcys.ysm.util.ProtoUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

final class PhysicsWireFixture {
    static final ConstraintFrameProto IDENTITY = ConstraintFrameProto.newBuilder().addPivotOffset(0, 0, 0)
        .addAxisX(1, 0, 0).addAxisY(0, 1, 0).addAxisZ(0, 0, 1).build();
    static final ConstraintFrameProto ROTATED = ConstraintFrameProto.newBuilder().addPivotOffset(-2, 8, 0.5F)
        .addAxisX(0, 1, 0).addAxisY(-1, 0, 0).addAxisZ(0, 0, 1).build();

    static final CollisionShapeProto SPHERE = CollisionShapeProto.newBuilder()
        .setSphere(SphereShapeProto.newBuilder().addCenter(1, -2, 3).setRadius(2.5F).build()).build();
    static final CollisionShapeProto CAPSULE = CollisionShapeProto.newBuilder()
        .setCapsule(CapsuleShapeProto.newBuilder().addPointA(-1, 2, 3).addPointB(4, 5, 6).setRadius(0.75F).build())
        .build();
    static final CollisionShapeProto BOX = CollisionShapeProto.newBuilder()
        .setBox(BoxShapeProto.newBuilder().addCenter(0, -1, 2).addSize(2, 4, 6).addRotation(0, 0.6F, 0, 0.8F).build())
        .build();

    static final SpringParametersProto FREQUENCY = SpringParametersProto.newBuilder()
        .setFrequencyDamping(FrequencyDampingProto.newBuilder().setFrequency(2.5F).setDampingRatio(0).build()).build();
    static final SpringParametersProto STIFFNESS = SpringParametersProto.newBuilder()
        .setStiffnessDamping(StiffnessDampingProto.newBuilder().setStiffness(0).setDamping(8).build()).build();
    static final SpringConfigProto REST =
        SpringConfigProto.newBuilder().setTarget(SpringTarget.REST_POSE).setParameters(FREQUENCY).build();
    static final SpringConfigProto ANIMATION =
        SpringConfigProto.newBuilder().setTarget(SpringTarget.ANIMATION).setParameters(STIFFNESS).build();
    static final AxisConfigProto SOFT = AxisConfigProto.newBuilder().setLimit(LimitKind.SOFT).setMin(0).setMax(1.25F)
        .setSpring(REST).setSoftLimitSpring(STIFFNESS).build();

    static final PhysicsNodeProto DYNAMIC = PhysicsNodeProto.newBuilder().setId("strand").setBone("hair")
        .setKind(PhysicsNodeKind.DYNAMIC).addShapes(SPHERE, CAPSULE, BOX).setCollectionMask(0).setFriction(0)
        .setBounciness(0.25F).setMass(2.5F).setLinearDamping(0.125F).setAngularDamping(0.625F).setGravityScale(-0.75F)
        .setDisableWhenHidden(true).setPresetTag("hair_chain:strand").build();
    static final PhysicsNodeProto ANCHOR =
        PhysicsNodeProto.newBuilder().setId("anchor").setBone("body").setKind(PhysicsNodeKind.ANCHOR).build();
    static final PhysicsNodeProto PROXY = PhysicsNodeProto.newBuilder().setId("proxy").setBone("body")
        .setKind(PhysicsNodeKind.PROXY).addShapes(BOX).setCollectionMask(1).build();
    static final PhysicsNodeProto DEFAULT_PROXY = PhysicsNodeProto.newBuilder().setId("default_proxy").setBone("body")
        .setKind(PhysicsNodeKind.PROXY).addShapes(SPHERE).build();

    static final PhysicsConstraintProto GENERIC =
        joint("lat", "anchor", PhysicsConstraintKind.GENERIC).withDisableCollisions(false).withLinearX(SOFT)
            .withLinearY(AxisConfigProto.newBuilder().setLimit(LimitKind.LOCKED).build())
            .withLinearZ(AxisConfigProto.newBuilder().setLimit(LimitKind.HARD).setMin(-4).setMax(2).build())
            .withAngularX(AxisConfigProto
                .newBuilder().setLimit(LimitKind.HARD).setMin(-(float)Math.PI).setMax((float)Math.PI).build())
            .withAngularY(AxisConfigProto.newBuilder().setSpring(ANIMATION).build())
            .withAngularZ(AxisConfigProto.newBuilder().setLimit(LimitKind.FREE)
                .setSpring(SpringConfigProto.newBuilder().setTarget(SpringTarget.OFF).build()).build())
            .withPresetTag("lateral_link");
    static final PhysicsConstraintProto POINT =
        joint("point", "proxy", PhysicsConstraintKind.POINT).withCone(ConeConfigProto.newBuilder()
            .setLimit(LimitKind.SOFT).setHalfAngle(0.75F).setSoftLimitSpring(FREQUENCY).build());
    static final PhysicsConstraintProto HINGE =
        joint("hinge", "anchor", PhysicsConstraintKind.HINGE).withDisableCollisions(true)
            .withAngularZ(AxisConfigProto.newBuilder().setLimit(LimitKind.HARD).setMin(0.25F).setMax(0.5F).build());
    static final PhysicsConstraintProto FIXED = joint("fixed", "anchor", PhysicsConstraintKind.FIXED);

    private PhysicsWireFixture() {}

    static PhysicsConstraintProto joint(String id, String end, PhysicsConstraintKind kind) {
        return PhysicsConstraintProto.newBuilder().setId(id).setNodeA(end).setNodeB("strand").setKind(kind)
            .setFrameA(IDENTITY).setFrameB(ROTATED).build();
    }

    static GeoModelProto geometry(String... names) {
        var geo = GeoModelProto.newBuilder()
            .setProperties(GeoPropertiesProto.newBuilder().setTextureHeight(64).setTextureWidth(32).build())
            .setCubes(ByteBuffer.allocate(0));
        for (var name : names) {
            geo.addBones(BoneProto.newBuilder().setName(name).addPivot(1, 2, 3).addRotate(0, 0, 0).build());
        }
        return geo.build();
    }

    static PhysicsDefinitionProto definition() {
        return PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC, ANCHOR, PROXY, DEFAULT_PROXY)
            .addConstraints(GENERIC, POINT, HINGE, FIXED)
            .addCollections(CollectionNameProto.newBuilder().setSlot(20).setName("hair").build())
            .addGravity(0.5F, -9.25F, -0.75F).build();
    }

    static ModelDataProto modelData(PhysicsDefinitionProto definition) throws IOException {
        return ModelDataProto.newBuilder()
            .putGeoModels("main", ByteBuffer.wrap(ProtoUtil.serializeToArray(geometry("body", "hair"))))
            .setPhysics(definition).build();
    }

    public static void main(String[] args) throws IOException {
        var directory = Files.createDirectories(Path.of(args[0]));
        var model = modelData(definition());
        Files.write(directory.resolve("physics-model.pb"), ProtoUtil.serializeToArray(model));
        Files.write(directory.resolve("physics-model-absent.pb"), ProtoUtil.serializeToArray(model.withPhysics(null)));
    }
}
