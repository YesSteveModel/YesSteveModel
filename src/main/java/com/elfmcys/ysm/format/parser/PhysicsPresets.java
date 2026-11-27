package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringTarget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class PhysicsPresets {
    private static final List<String> SWING_AXES = List.of("angular_y", "angular_z");
    private static final Set<String> AXES =
        Set.of("linear_x", "linear_y", "linear_z", "angular_x", "angular_y", "angular_z");
    private static final List<String> FRAME_FIELDS = List.of("frame", "frame_a", "frame_b");
    private static final Set<String> AXIS_FIELDS = Set.of("limit", "spring", "soft_limit_spring");
    private final PhysicsJsonCompiler compiler;
    private final Set<String> ids = new HashSet<>();

    PhysicsPresets(PhysicsJsonCompiler compiler) {
        this.compiler = compiler;
    }

    void expand(PhysicsJson input) throws AssetLoadException {
        var id = input.string("id");
        var preset = input.withContext(input.context() + " '" + id + "'");
        if (id.isBlank() || !ids.add(id)) {
            throw preset.error("needs a nonblank unique preset id");
        }
        var type = preset.string("type");
        var tag = type + ":" + id;
        switch (type) {
            case "hair_chain" -> {
                preset.only("id", "type", "bones", "anchor_bone", "anchor", "body", "joint", "overrides", "axis",
                    "root_spring", "tip_spring");
                expandBones(preset, id, tag, true);
            }
            case "skirt_panel" -> {
                preset.only("id", "type", "bones", "anchor_bone", "anchor", "body", "joint", "overrides");
                expandBones(preset, id, tag, false);
            }
            case "lateral_link" -> expandLateralLink(preset, id, tag);
            default -> throw preset.error("has unsupported preset type '" + type + "'");
        }
    }

    private void expandBones(PhysicsJson preset, String id, String tag, boolean hair) throws AssetLoadException {
        var bones = new ArrayList<String>();
        for (var value : preset.array("bones")) {
            bones.add(PhysicsJson.string(value, preset.context() + " bone"));
        }
        if (bones.isEmpty() || new HashSet<>(bones).size() != bones.size()) {
            throw preset.error("needs a nonempty list of distinct bones");
        }
        var overrides = preset.optionalObject("overrides");
        for (var bone : overrides.values().keySet()) {
            if (!bones.contains(bone)) {
                throw overrides.error("names unknown preset bone '" + bone + "'");
            }
        }
        var body = bodyFields(preset.object("body"));
        var defaultType = hair ? "point" : "hinge";
        var joint = preset.optionalObject("joint");
        var sharedType = joint.string("type", defaultType);
        validateBoneJoint(joint, sharedType, defaultType);
        joint.values().addProperty("type", sharedType);
        var springs = HairSprings.read(preset);
        var anchor = anchor(preset, id, tag);
        var axis = preset.has("axis") ? PhysicsFrame.normalize(preset.vector("axis"), preset) : null;
        Vector3dc previous = null;
        for (int i = 0; i < bones.size(); i++) {
            var bone = bones.get(i);
            var part = overrides.optionalObject(bone);
            if (hair) {
                part.only("body", "joint", "axis");
            } else {
                part.only("body", "joint");
            }
            var nodeId = id + "/body/" + i;
            var node = body.values().deepCopy();
            copyFields(node, bodyFields(part.optionalObject("body")).values());
            node.addProperty("id", nodeId);
            node.addProperty("type", "dynamic");
            node.addProperty("bone", bone);
            defaultValue(node, "disable_when_hidden", true);
            if (hair && !node.has("linear_damping")) {
                node.addProperty("linear_damping", 1.5f);
            }
            compiler.addNode(new PhysicsJson(node, preset.context() + " node"), tag);

            var override = part.optionalObject("joint");
            var jointType = override.string("type", sharedType);
            validateBoneJoint(override, jointType, defaultType);
            var inherited = joint;
            if (springs != null) {
                inherited = new PhysicsJson(joint.values().deepCopy(), joint.context());
                var spring = springs.at(i, bones.size());
                for (var angular : SWING_AXES) {
                    installSpring(inherited, angular, spring);
                }
            }
            var connection = mergeJoint(inherited, override, jointType, hair);
            var jointJson = new PhysicsJson(connection, part.context() + " joint");
            PhysicsFrame frame = null;
            if (hair) {
                var pose = compiler.pose(bone, part);
                if (part.has("axis")) {
                    previous = PhysicsFrame.normalize(part.vector("axis"), part);
                } else if (axis != null) {
                    previous = axis;
                } else if (i + 1 < bones.size()) {
                    var to = compiler.pose(bones.get(i + 1), part).position();
                    var direction = new Vector3d(to).sub(pose.position());
                    previous = direction.lengthSquared() > 0 ? direction.normalize() : null;
                }
                var shared = jointJson.optionalObject("frame");
                if (!shared.has("rotation_deg") && !shared.has("axis_x") && !shared.has("axis_y")
                    && !jointJson.has("frame_a") && !jointJson.has("frame_b")) {
                    shared.only("pivot_offset");
                    if (previous == null) {
                        throw part.error("requires an explicit axis for a single segment or coincident bind pivots");
                    }
                    var zRef = pose.rotation().transform(new Vector3d(0, 0, 1));
                    Vector3d y;
                    Vector3d z;
                    if (Math.abs(previous.dot(zRef)) < 0.999) {
                        y = zRef.cross(previous).normalize();
                        z = previous.cross(y, new Vector3d());
                    } else {
                        var yRef = pose.rotation().transform(new Vector3d(0, 1, 0));
                        z = previous.cross(yRef, new Vector3d()).normalize();
                        y = z.cross(previous, new Vector3d());
                    }
                    frame = new PhysicsFrame(shared.optionalVector("pivot_offset"), previous, y, z);
                }
            }
            connection.addProperty("id", id + "/joint/" + i);
            connection.addProperty("node_a", hair && i > 0 ? id + "/body/" + (i - 1) : anchor);
            connection.addProperty("node_b", nodeId);
            compiler.addJoint(new PhysicsJson(connection, preset.context() + " constraint"), tag, frame);
        }
    }

    private String anchor(PhysicsJson preset, String id, String tag) throws AssetLoadException {
        if (preset.has("anchor") == preset.has("anchor_bone")) {
            throw preset.error("requires exactly one of anchor or anchor_bone");
        }
        if (preset.has("anchor")) {
            var anchor = preset.string("anchor");
            compiler.requireAnchor(anchor, preset);
            return anchor;
        }
        var node = new JsonObject();
        node.addProperty("id", id + "/anchor");
        node.addProperty("type", "anchor");
        node.addProperty("bone", preset.string("anchor_bone"));
        compiler.addNode(new PhysicsJson(node, preset.context() + " node"), tag);
        return id + "/anchor";
    }

    private void expandLateralLink(PhysicsJson preset, String id, String tag) throws AssetLoadException {
        preset.only("id", "type", "node_a", "node_b", "joint");
        var authored = preset.optionalObject("joint");
        if (!authored.string("type", "generic").equals("generic")) {
            throw authored.error("allows only a generic joint");
        }
        validateJointFields(authored, "generic");
        var defaults = new JsonObject();
        defaults.addProperty("type", "generic");
        defaults.addProperty("disable_collisions", false);
        for (var axis : List.of("linear_x", "linear_y", "linear_z")) {
            defaults.add(axis, lateralAxis());
        }
        var joint = mergeJoint(new PhysicsJson(defaults, preset.context()), authored, "generic", false);
        joint.addProperty("id", id + "/joint");
        joint.addProperty("node_a", preset.string("node_a"));
        joint.addProperty("node_b", preset.string("node_b"));
        compiler.addLateralJoint(new PhysicsJson(joint, preset.context() + " constraint"), tag);
    }

    private static JsonObject hardCone() {
        var cone = new JsonObject();
        cone.addProperty("limit_kind", "hard");
        cone.addProperty("half_angle_deg", 35);
        return cone;
    }

    private static JsonObject lateralAxis() {
        var limit = new JsonObject();
        limit.addProperty("kind", "soft");
        limit.addProperty("min", -1);
        limit.addProperty("max", 1);
        var parameters = new JsonObject();
        parameters.addProperty("frequency", 3);
        parameters.addProperty("damping_ratio", 0.8);
        var spring = parameters.deepCopy();
        spring.addProperty("target", "rest_pose");

        var axis = new JsonObject();
        axis.add("limit", limit);
        axis.add("spring", spring);
        axis.add("soft_limit_spring", parameters);
        return axis;
    }

    private static PhysicsJson bodyFields(PhysicsJson body) throws AssetLoadException {
        return body.only("collections", "friction", "bounciness", "shapes", "mass", "linear_damping", "angular_damping",
            "gravity_scale", "disable_when_hidden");
    }

    private static void validateBoneJoint(PhysicsJson joint, String type, String defaultType)
        throws AssetLoadException {
        if (!type.equals(defaultType) && !type.equals("generic")) {
            throw joint.error("allows joint type '" + defaultType + "' or 'generic'");
        }
        validateJointFields(joint, type);
    }

    private static void validateJointFields(PhysicsJson joint, String type) throws AssetLoadException {
        if (joint.has("id") || joint.has("node_a") || joint.has("node_b")) {
            throw joint.error("cannot set generated id or endpoints");
        }
        joint.only(PhysicsJsonCompiler.allowedJointFields(type, joint));
        checkAxisFields(joint);
        for (var name : AXES) {
            if (joint.has(name)) {
                checkAxisFields(joint.object(name));
            }
        }
        if (joint.has("cone")) {
            checkAxisFields(joint.object("cone"));
        }
        if (joint.has("cone") && hasSwingLimit(joint, false)) {
            throw joint.error("cannot combine a cone with per-axis swing limits in the same preset layer");
        }
    }

    private static void checkAxisFields(PhysicsJson axis) throws AssetLoadException {
        for (var name : AXIS_FIELDS) {
            if (axis.has(name)) {
                axis.object(name);
            }
        }
    }

    private static boolean hasSwingLimit(PhysicsJson joint, boolean includeFree) throws AssetLoadException {
        for (var name : SWING_AXES) {
            var axis = joint.optionalObject(name);
            if (axis.has("limit")) {
                var limit = axis.object("limit");
                if (includeFree || !limit.string("kind", "").equals("free")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonObject mergeJoint(PhysicsJson base, PhysicsJson override, String type, boolean hair)
        throws AssetLoadException {
        var result = base.values().deepCopy();
        var oldType = base.string("type");
        if (!type.equals(oldType)) {
            var allowed = PhysicsJsonCompiler.allowedJointFields(type, override);
            result.keySet().retainAll(allowed);
        }
        if (hair && type.equals("point") && !result.has("cone")
            && !hasSwingLimit(new PhysicsJson(result, base.context()), false)) {
            result.add("cone", hardCone());
        }
        if (override.has("cone")) {
            for (var name : SWING_AXES) {
                if (result.has(name)) {
                    var axis = result.getAsJsonObject(name);
                    axis.remove("limit");
                    axis.remove("soft_limit_spring");
                }
            }
        } else if (hasSwingLimit(override, true)) {
            result.remove("cone");
        }
        if (FRAME_FIELDS.stream().anyMatch(override::has)) {
            FRAME_FIELDS.forEach(result::remove);
        }
        if (type.equals("hinge") && oldType.equals("hinge")) {
            removeInactiveLimitFields(result, override);
        }
        for (var entry : override.values().entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (AXES.contains(key) && value.isJsonObject()) {
                var oldAxis = result.getAsJsonObject(key);
                result.add(key, mergeAxis(oldAxis, override.object(key)));
            } else if (key.equals("cone") && value.isJsonObject()) {
                var oldCone = result.getAsJsonObject(key);
                result.add(key, mergeCone(oldCone, override.object(key)));
            } else if (type.equals("hinge") && AXIS_FIELDS.contains(key)) {
                mergeAxisField(result, key, value);
            } else {
                result.add(key, value.deepCopy());
            }
        }
        result.addProperty("type", type);
        return result;
    }

    private static JsonObject mergeAxis(@Nullable JsonObject base, PhysicsJson override) throws AssetLoadException {
        var result = base == null ? new JsonObject() : base.deepCopy();
        removeInactiveLimitFields(result, override);
        for (var entry : override.values().entrySet()) {
            mergeAxisField(result, entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static JsonObject mergeCone(@Nullable JsonObject base, PhysicsJson override) throws AssetLoadException {
        var result = base == null ? new JsonObject() : base.deepCopy();
        if (override.has("limit_kind") && !override.string("limit_kind").equals("soft")) {
            result.remove("soft_limit_spring");
        }
        for (var entry : override.values().entrySet()) {
            mergeAxisField(result, entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static void removeInactiveLimitFields(JsonObject result, PhysicsJson override) throws AssetLoadException {
        if (!override.has("limit")) {
            return;
        }
        var limit = override.object("limit");
        if (!limit.has("kind")) {
            return;
        }
        var kind = limit.string("kind");
        if (!kind.equals("soft")) {
            result.remove("soft_limit_spring");
        }
        if (kind.equals("free") || kind.equals("locked")) {
            result.remove("limit");
        }
    }

    private static void mergeAxisField(JsonObject result, String key, JsonElement value) {
        if (AXIS_FIELDS.contains(key) && value.isJsonObject() && result.has(key)) {
            var authored = value.getAsJsonObject();
            var inherited = result.getAsJsonObject(key);
            if (key.equals("spring") || key.equals("soft_limit_spring")) {
                var mode = authored.get("mode");
                if (key.equals("spring") && isString(authored.get("target"), "off")) {
                    inherited = new JsonObject();
                } else if (mode != null) {
                    var inheritedMode = inherited.get("mode");
                    if (!mode.equals(inheritedMode)
                        && !(inheritedMode == null && isString(mode, "frequency_damping"))) {
                        List.of("frequency", "damping_ratio", "stiffness", "damping").forEach(inherited::remove);
                    }
                }
            }
            copyFields(inherited, authored);
            result.add(key, inherited);
        } else {
            result.add(key, value.deepCopy());
        }
    }

    private static boolean isString(@Nullable JsonElement element, String value) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
            && element.getAsString().equals(value);
    }

    private static void copyFields(JsonObject target, JsonObject source) {
        source.entrySet().forEach(entry -> target.add(entry.getKey(), entry.getValue().deepCopy()));
    }

    private static void defaultValue(JsonObject object, String key, boolean value) {
        if (!object.has(key)) {
            object.addProperty(key, value);
        }
    }

    private static void installSpring(PhysicsJson joint, String name, JsonObject spring) throws AssetLoadException {
        var axis = joint.optionalObject(name);
        var authored = axis.values().get("spring");
        axis.values().add("spring", spring.deepCopy());
        if (authored != null) {
            mergeAxisField(axis.values(), "spring", authored);
        }
        joint.values().add(name, axis.values());
    }

    private record HairSprings(String target, String mode, double rootA, double rootB, double tipA, double tipB) {
        static @Nullable HairSprings read(PhysicsJson preset) throws AssetLoadException {
            if (!preset.has("root_spring") && !preset.has("tip_spring")) {
                return null;
            }
            var root = preset.object("root_spring");
            var tip = preset.has("tip_spring") ? preset.object("tip_spring") : root;
            var rootSpring = PhysicsJsonCompiler.spring(root);
            var tipSpring = PhysicsJsonCompiler.spring(tip);
            if (rootSpring.target() != tipSpring.target()) {
                throw preset.error("requires matching root and tip spring targets");
            }
            if (rootSpring.target() == SpringTarget.OFF) {
                return new HairSprings("off", "frequency_damping", 0, 0, 0, 0);
            }
            var mode = root.string("mode", "frequency_damping");
            if (!mode.equals(tip.string("mode", "frequency_damping"))) {
                throw preset.error("requires matching root and tip spring modes");
            }
            var a = primary(mode);
            var b = secondary(mode);
            var stiffness = mode.equals("stiffness_damping");
            return new HairSprings(root.string("target"), mode, stiffness ? root.decimal(a, 10) : root.decimal(a),
                root.decimal(b, 0.5), stiffness ? tip.decimal(a, 10) : tip.decimal(a), tip.decimal(b, 0.5));
        }

        JsonObject at(int index, int count) {
            var spring = new JsonObject();
            spring.addProperty("target", target);
            if (!target.equals("off")) {
                double fraction = count == 1 ? 0 : (double)index / (count - 1);
                spring.addProperty("mode", mode);
                spring.addProperty(primary(mode), interpolate(fraction, rootA, tipA));
                spring.addProperty(secondary(mode), interpolate(fraction, rootB, tipB));
            }
            return spring;
        }

        private static double interpolate(double fraction, double root, double tip) {
            if (fraction == 0)
                return root;
            if (fraction == 1)
                return tip;
            return (1 - fraction) * root + fraction * tip;
        }

        private static String primary(String mode) {
            return mode.equals("stiffness_damping") ? "stiffness" : "frequency";
        }

        private static String secondary(String mode) {
            return mode.equals("stiffness_damping") ? "damping" : "damping_ratio";
        }
    }
}
