package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import it.unimi.dsi.fastutil.floats.FloatList;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Resolves player/main bind poses under the same root convention as the native bone hierarchy. */
public final class PhysicsBindPose {
    private static final double PIXELS_PER_METER = 16;
    private final Map<String, Pose> poses = new LinkedHashMap<>();

    public PhysicsBindPose(GeoModelProto geometry) throws AssetLoadException {
        this(geometry, "Physics player/main");
    }

    public PhysicsBindPose(GeoModelProto geometry, String context) throws AssetLoadException {
        var bones = new LinkedHashMap<String, BoneProto>();
        for (var bone : geometry.bones()) {
            if (bones.putIfAbsent(bone.name(), bone) != null) {
                throw AssetLoadException.content(context + " has duplicate bone '" + bone.name() + "'");
            }
        }
        var path = new ArrayDeque<BoneProto>();
        var pending = new HashSet<String>();
        for (var bone : bones.values()) {
            var current = bone;
            Pose parent = null;
            // The memoized parent-first walk accepts arbitrary input order and never recurses.
            while (current != null) {
                parent = poses.get(current.name());
                if (parent != null) {
                    break;
                }
                if (!pending.add(current.name())) {
                    throw AssetLoadException.content(context + " has a bone cycle at '" + current.name() + "'");
                }
                path.push(current);
                var parentName = parentName(current);
                current = parentName == null ? null : bones.get(parentName);
                if (parentName != null && current == null) {
                    throw AssetLoadException.content(
                        context + " bone '" + path.peek().name() + "' has missing parent '" + parentName + "'");
                }
            }
            while (!path.isEmpty()) {
                var definition = path.pop();
                var boneContext = context + " bone '" + definition.name() + "'";
                var pivot = vector(definition.pivot(), boneContext + " pivot");
                var rotation = rotation(vector(definition.rotate(), boneContext + " rotate"));
                var position = new Vector3d(pivot);
                boolean finite = true;
                if (parent != null) {
                    // Geometry pivots are absolute before bind rotation and native FK stores translation in meters.
                    position.sub(parent.geometryPivot());
                    parent.rotation().transform(position).add(parent.position());
                    rotation.premul(parent.rotation());
                    finite = parent.finite();
                }
                finite &= Float.isFinite((float)(position.x / PIXELS_PER_METER))
                    && Float.isFinite((float)(position.y / PIXELS_PER_METER))
                    && Float.isFinite((float)(position.z / PIXELS_PER_METER)) && rotation.isFinite();
                parent = new Pose(parentName(definition), pivot, position, rotation, finite);
                poses.put(definition.name(), parent);
            }
            pending.clear();
        }
    }

    private static @Nullable String parentName(BoneProto bone) {
        return bone.hasParent() && !bone.parentUnsafe().isEmpty() ? bone.parentUnsafe() : null;
    }

    public Pose resolve(String bone, String context) throws AssetLoadException {
        var pose = poses.get(bone);
        if (pose == null) {
            throw AssetLoadException.content(context + " must bind exactly one player/main bone named '" + bone + "'");
        }
        if (!pose.finite()) {
            throw AssetLoadException.content(context + " has a nonfinite bind pose at bone '" + bone + "'");
        }
        return pose;
    }

    Set<String> bonesWithDynamicAncestor(Set<String> dynamicBones) {
        var result = new HashSet<String>();
        for (var entry : poses.entrySet()) {
            if (dynamicBones.contains(entry.getKey()) || result.contains(entry.getValue().parent())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private static Vector3d vector(FloatList values, String context) throws AssetLoadException {
        if (values.size() != 3) {
            throw AssetLoadException.content(context + " needs three components");
        }
        var vector = new Vector3d(values.getFloat(0), values.getFloat(1), values.getFloat(2));
        if (!vector.isFinite()) {
            throw AssetLoadException.content(context + " must be finite");
        }
        return vector;
    }

    public static Quaterniond rotation(Vector3dc radians) {
        return new Quaterniond().rotationZYX(radians.z(), radians.y(), radians.x());
    }

    public record Pose(@Nullable String parent, Vector3dc geometryPivot, Vector3dc position, Quaterniondc rotation,
        boolean finite) {
    }
}
