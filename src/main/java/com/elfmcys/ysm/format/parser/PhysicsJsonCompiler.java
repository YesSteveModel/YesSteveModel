package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.model.PhysicsBindPose;
import com.elfmcys.ysm.format.schema.model.PhysicsDefinitionValidator;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AxisConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoxShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CapsuleShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollectionNameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollisionShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ConeConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.FrequencyDampingProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
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
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class PhysicsJsonCompiler {
    private static final Map<String, Set<String>> JOINT_FIELDS =
        Map.of("fixed", jointFields(), "point", jointFields("cone", "angular_x", "angular_y", "angular_z"), "hinge",
            jointFields("limit", "spring", "soft_limit_spring"), "generic",
            jointFields("linear_x", "linear_y", "linear_z", "angular_x", "angular_y", "angular_z"));

    private final PhysicsDefinitionProto.Builder definition = PhysicsDefinitionProto.newBuilder();
    private final Map<String, PhysicsNodeProto> nodes = new HashMap<>();
    private final List<JointInput> joints = new ArrayList<>();
    private final List<AnchorInput> anchors = new ArrayList<>();
    private final PhysicsBindPose bindPose;

    private PhysicsJsonCompiler(PhysicsBindPose bindPose) {
        this.bindPose = bindPose;
    }

    static PhysicsDefinitionProto compile(NativeBuffer source, GeoModelProto geometry, String targetId, String context)
        throws IOException {
        try {
            var text = StandardCharsets.UTF_8.newDecoder().decode(source.nio()).toString();
            var json = PhysicsJson.parse(text, context);
            var bindPose = new PhysicsBindPose(geometry, context);
            var result = new PhysicsJsonCompiler(bindPose).compile(json);
            try {
                PhysicsDefinitionValidator.validate(result, bindPose, targetId);
            } catch (AssetLoadException error) {
                throw AssetLoadException.content(context + ": " + error.getMessage(), error);
            }
            return result;
        } catch (CharacterCodingException error) {
            throw AssetLoadException.content(context + " must be UTF-8", error);
        }
    }

    private PhysicsDefinitionProto compile(PhysicsJson root) throws AssetLoadException {
        root.only("gravity", "collections", "nodes", "constraints", "presets");
        if (root.has("gravity")) {
            definition.addAllGravity(root.floatVector("gravity"));
        }
        var collections = root.optionalObject("collections");
        var sorted = new TreeMap<Integer, String>();
        for (var entry : collections.values().entrySet()) {
            var key = entry.getKey();
            int slot;
            try {
                slot = PhysicsJson.slot(Integer.parseInt(key), collections.context());
            } catch (NumberFormatException error) {
                throw collections.error("has invalid collection slot '" + key + "'");
            }
            if (!Integer.toString(slot).equals(key)) {
                throw collections.error("has invalid collection slot '" + key + "'");
            }
            sorted.put(slot, PhysicsJson.string(entry.getValue(), collections.context() + " slot " + slot));
        }
        sorted.forEach((slot, name) -> definition
            .addCollections(CollectionNameProto.newBuilder().setSlot(slot).setName(name).build()));

        for (var node : root.optionalArray("nodes")) {
            addNode(PhysicsJson.object(node, root.context() + " node"), null);
        }
        for (var constraint : root.optionalArray("constraints")) {
            addJoint(PhysicsJson.object(constraint, root.context() + " constraint"), null, null);
        }
        var presets = new PhysicsPresets(this);
        for (var preset : root.optionalArray("presets")) {
            presets.expand(PhysicsJson.object(preset, root.context() + " preset"));
        }
        for (var anchor : anchors) {
            var node = nodes.get(anchor.id());
            if (node == null || node.kind() != PhysicsNodeKind.ANCHOR) {
                throw anchor.preset().error("must reference an existing anchor node '" + anchor.id() + "'");
            }
        }
        for (var joint : joints) {
            definition.addConstraints(constraint(joint));
        }
        return definition.build();
    }

    PhysicsBindPose.Pose pose(String bone, PhysicsJson context) throws AssetLoadException {
        return bindPose.resolve(bone, context.context());
    }

    void addNode(PhysicsJson input, @Nullable String tag) throws AssetLoadException {
        var id = input.string("id");
        var node = input.withContext(input.context() + " '" + id + "'");
        var kind = switch (node.string("type")) {
            case "dynamic" -> PhysicsNodeKind.DYNAMIC;
            case "proxy" -> PhysicsNodeKind.PROXY;
            case "anchor" -> PhysicsNodeKind.ANCHOR;
            default -> throw node.error("has unsupported node type '" + node.string("type") + "'");
        };
        switch (kind) {
            case ANCHOR -> node.only("id", "bone", "type");
            case PROXY -> node.only("id", "bone", "type", "collections", "friction", "bounciness", "shapes");
            case DYNAMIC -> node.only("id", "bone", "type", "collections", "friction", "bounciness", "shapes", "mass",
                "linear_damping", "angular_damping", "gravity_scale", "disable_when_hidden");
            default -> throw new IllegalStateException("Unexpected raw node kind " + kind);
        }
        var bone = node.string("bone");
        var result = PhysicsNodeProto.newBuilder().setId(id).setBone(bone).setKind(kind);
        if (kind != PhysicsNodeKind.ANCHOR) {
            result.setFriction(node.number("friction", 0.5f)).setBounciness(node.number("bounciness", 0));
            if (node.has("collections")) {
                int mask = 0;
                for (var rawSlot : node.array("collections")) {
                    mask |= 1 << (PhysicsJson.slot(rawSlot, node.context() + " collection slot") - 1);
                }
                result.setCollectionMask(mask);
            }
            int index = 0;
            for (var shape : node.optionalArray("shapes")) {
                result.addShapes(shape(PhysicsJson.object(shape, node.context() + " shape[" + index++ + "]")));
            }
        }
        if (kind == PhysicsNodeKind.DYNAMIC) {
            result.setMass(node.number("mass", 1)).setLinearDamping(node.number("linear_damping", 0.04f))
                .setAngularDamping(node.number("angular_damping", 0.1f))
                .setGravityScale(node.number("gravity_scale", 1))
                .setDisableWhenHidden(node.has("disable_when_hidden") && node.bool("disable_when_hidden"));
        }
        if (tag != null) {
            result.setPresetTag(tag);
        }
        var built = result.build();
        nodes.putIfAbsent(id, built);
        definition.addNodes(built);
    }

    private static CollisionShapeProto shape(PhysicsJson shape) throws AssetLoadException {
        var result = CollisionShapeProto.newBuilder();
        var type = shape.string("type");
        switch (type) {
            case "sphere" -> {
                shape.only("type", "center", "radius");
                result.setSphere(SphereShapeProto.newBuilder().addAllCenter(shape.optionalFloatVector("center"))
                    .setRadius(shape.number("radius")).build());
            }
            case "capsule" -> {
                shape.only("type", "point_a", "point_b", "radius");
                result.setCapsule(CapsuleShapeProto.newBuilder().addAllPointA(shape.floatVector("point_a"))
                    .addAllPointB(shape.floatVector("point_b")).setRadius(shape.number("radius")).build());
            }
            case "box" -> {
                shape.only("type", "center", "size", "rotation_deg");
                var rotation = PhysicsFrame.degrees(shape.optionalVector("rotation_deg"));
                result.setBox(BoxShapeProto.newBuilder().addAllCenter(shape.optionalFloatVector("center"))
                    .addAllSize(shape.floatVector("size")).addRotation((float)rotation.x).addRotation((float)rotation.y)
                    .addRotation((float)rotation.z).addRotation((float)rotation.w).build());
            }
            default -> throw shape.error("has unsupported shape type '" + type + "'");
        }
        return result.build();
    }

    void addJoint(PhysicsJson input, @Nullable String tag, @Nullable PhysicsFrame frame) {
        joints.add(new JointInput(input, tag, frame, false));
    }

    void addLateralJoint(PhysicsJson input, String tag) {
        joints.add(new JointInput(input, tag, null, true));
    }

    void requireAnchor(String id, PhysicsJson preset) {
        anchors.add(new AnchorInput(id, preset));
    }

    private static Set<String> jointFields(String... specificFields) {
        var fields = new HashSet<>(
            List.of("id", "node_a", "node_b", "type", "disable_collisions", "frame", "frame_a", "frame_b"));
        fields.addAll(List.of(specificFields));
        return Set.copyOf(fields);
    }

    static Set<String> allowedJointFields(String type, PhysicsJson joint) throws AssetLoadException {
        var fields = JOINT_FIELDS.get(type);
        if (fields == null) {
            throw joint.error("has unsupported constraint type '" + type + "'");
        }
        return fields;
    }

    private PhysicsConstraintProto constraint(JointInput source) throws AssetLoadException {
        var input = source.json();
        var id = input.string("id");
        var joint = input.withContext(input.context() + " '" + id + "'");
        var type = joint.string("type");
        var a = joint.string("node_a");
        var b = joint.string("node_b");
        joint.only(allowedJointFields(type, joint));
        var result = PhysicsConstraintProto.newBuilder().setId(id).setNodeA(a).setNodeB(b);
        switch (type) {
            case "fixed" -> result.setKind(PhysicsConstraintKind.FIXED);
            case "point" -> {
                result.setKind(PhysicsConstraintKind.POINT);
                if (joint.has("cone")) {
                    result.setCone(cone(joint.object("cone")));
                }
                angularAxes(joint, result);
            }
            case "hinge" -> {
                result.setKind(PhysicsConstraintKind.HINGE);
                result.setAngularZ(axis(axisFields(joint), true));
            }
            case "generic" -> {
                result.setKind(PhysicsConstraintKind.GENERIC);
                if (joint.has("linear_x")) {
                    result.setLinearX(axis(joint.object("linear_x"), false));
                }
                if (joint.has("linear_y")) {
                    result.setLinearY(axis(joint.object("linear_y"), false));
                }
                if (joint.has("linear_z")) {
                    result.setLinearZ(axis(joint.object("linear_z"), false));
                }
                angularAxes(joint, result);
            }
            default -> throw new IllegalStateException("Unhandled constraint type " + type);
        }
        if (joint.has("disable_collisions")) {
            result.setDisableCollisions(joint.bool("disable_collisions"));
        }
        if (source.tag() != null) {
            result.setPresetTag(source.tag());
        }
        if (joint.has("frame_a") || joint.has("frame_b")) {
            if (joint.has("frame")) {
                throw joint.error("must use either frame or the frame_a and frame_b pair");
            }
            result.setFrameA(PhysicsFrame.readLocal(joint.object("frame_a")).proto());
            result.setFrameB(PhysicsFrame.readLocal(joint.object("frame_b")).proto());
        } else {
            var nodeA = endpoint(a, joint);
            var nodeB = endpoint(b, joint);
            var poseA = bindPose.resolve(nodeA.boneUnsafe(), joint.context() + " node '" + a + "'");
            var poseB = bindPose.resolve(nodeB.boneUnsafe(), joint.context() + " node '" + b + "'");
            var frame = source.frame();
            if (frame == null) {
                frame = PhysicsFrame.read(joint.optionalObject("frame"));
            }
            var pivot = poseB.position();
            if (source.centerOnShapes() && !joint.has("frame")) {
                pivot = shapeCenter(nodeA, poseA).add(shapeCenter(nodeB, poseB)).mul(0.5);
            }
            var shared = frame.at(pivot);
            result.setFrameA(shared.localize(poseA));
            result.setFrameB(shared.localize(poseB));
        }
        return result.build();
    }

    private PhysicsNodeProto endpoint(String id, PhysicsJson joint) throws AssetLoadException {
        var node = nodes.get(id);
        if (node == null) {
            throw joint.error("references unknown node '" + id + "'");
        }
        return node;
    }

    private static Vector3d shapeCenter(PhysicsNodeProto node, PhysicsBindPose.Pose pose) {
        var center = new Vector3d();
        for (var shape : node.shapes()) {
            switch (shape.shapeCase()) {
                case SPHERE -> center.add(PhysicsFrame.vector(shape.sphere().center()));
                case CAPSULE -> center.add(PhysicsFrame.vector(shape.capsule().pointA())
                    .add(PhysicsFrame.vector(shape.capsule().pointB())).mul(0.5));
                case BOX -> center.add(PhysicsFrame.vector(shape.box().center()));
                case NONE -> throw new IllegalStateException("Unknown compiled shape");
            }
        }
        if (!node.shapes().isEmpty()) {
            center.div(node.shapes().size());
        }
        return pose.rotation().transform(center).add(pose.position());
    }

    private static void angularAxes(PhysicsJson joint, PhysicsConstraintProto.Builder result)
        throws AssetLoadException {
        if (joint.has("angular_x")) {
            result.setAngularX(axis(joint.object("angular_x"), true));
        }
        if (joint.has("angular_y")) {
            result.setAngularY(axis(joint.object("angular_y"), true));
        }
        if (joint.has("angular_z")) {
            result.setAngularZ(axis(joint.object("angular_z"), true));
        }
    }

    private static PhysicsJson axisFields(PhysicsJson joint) {
        var object = new JsonObject();
        for (var name : List.of("limit", "spring", "soft_limit_spring")) {
            if (joint.has(name)) {
                object.add(name, joint.values().get(name));
            }
        }
        return new PhysicsJson(object, joint.context());
    }

    private static AxisConfigProto axis(PhysicsJson axis, boolean angular) throws AssetLoadException {
        axis.only("limit", "spring", "soft_limit_spring");
        var result = AxisConfigProto.newBuilder();
        if (axis.has("limit")) {
            var min = angular ? "min_deg" : "min";
            var max = angular ? "max_deg" : "max";
            var limit = axis.object("limit").only("kind", min, max);
            result.setLimit(limit(limit, "kind"));
            if (limit.has(min)) {
                result.setMin(bound(limit, min, angular));
            }
            if (limit.has(max)) {
                result.setMax(bound(limit, max, angular));
            }
        }
        if (axis.has("spring")) {
            result.setSpring(spring(axis.object("spring")));
        }
        if (axis.has("soft_limit_spring")) {
            result.setSoftLimitSpring(parameters(axis.object("soft_limit_spring")));
        }
        return result.build();
    }

    private static ConeConfigProto cone(PhysicsJson cone) throws AssetLoadException {
        cone.only("half_angle_deg", "limit_kind", "soft_limit_spring");
        var result = ConeConfigProto.newBuilder().setLimit(limit(cone, "limit_kind"))
            .setHalfAngle((float)Math.toRadians(cone.decimal("half_angle_deg")));
        if (cone.has("soft_limit_spring")) {
            result.setSoftLimitSpring(parameters(cone.object("soft_limit_spring")));
        }
        return result.build();
    }

    private static float bound(PhysicsJson limit, String field, boolean angular) throws AssetLoadException {
        return angular ? (float)Math.toRadians(limit.decimal(field)) : limit.number(field);
    }

    private static LimitKind limit(PhysicsJson limit, String field) throws AssetLoadException {
        var kind = limit.string(field);
        return switch (kind) {
            case "free" -> LimitKind.FREE;
            case "locked" -> LimitKind.LOCKED;
            case "hard" -> LimitKind.HARD;
            case "soft" -> LimitKind.SOFT;
            default -> throw limit.error("has unsupported limit kind '" + kind + "'");
        };
    }

    static SpringConfigProto spring(PhysicsJson spring) throws AssetLoadException {
        var key = spring.string("target", "off");
        var target = switch (key) {
            case "off" -> SpringTarget.OFF;
            case "rest_pose" -> SpringTarget.REST_POSE;
            case "animation" -> SpringTarget.ANIMATION;
            default -> throw spring.error("has unsupported spring target '" + key + "'");
        };
        var result = SpringConfigProto.newBuilder().setTarget(target);
        if (target == SpringTarget.OFF) {
            spring.only("target");
        } else {
            result.setParameters(parameters(spring, true));
        }
        return result.build();
    }

    private static SpringParametersProto parameters(PhysicsJson spring) throws AssetLoadException {
        return parameters(spring, false);
    }

    private static SpringParametersProto parameters(PhysicsJson spring, boolean hasTarget) throws AssetLoadException {
        var mode = spring.string("mode", "frequency_damping");
        var result = SpringParametersProto.newBuilder();
        switch (mode) {
            case "frequency_damping" -> {
                if (hasTarget) {
                    spring.only("target", "mode", "frequency", "damping_ratio");
                } else {
                    spring.only("mode", "frequency", "damping_ratio");
                }
                result.setFrequencyDamping(FrequencyDampingProto.newBuilder().setFrequency(spring.number("frequency"))
                    .setDampingRatio(spring.number("damping_ratio", 0.5f)).build());
            }
            case "stiffness_damping" -> {
                if (hasTarget) {
                    spring.only("target", "mode", "stiffness", "damping");
                } else {
                    spring.only("mode", "stiffness", "damping");
                }
                result.setStiffnessDamping(StiffnessDampingProto.newBuilder()
                    .setStiffness(spring.number("stiffness", 10)).setDamping(spring.number("damping", 0.5f)).build());
            }
            default -> throw spring.error("has unsupported spring mode '" + mode + "'");
        }
        return result.build();
    }

    private record JointInput(PhysicsJson json, @Nullable String tag, @Nullable PhysicsFrame frame,
        boolean centerOnShapes) {
    }

    private record AnchorInput(String id, PhysicsJson preset) {
    }
}
