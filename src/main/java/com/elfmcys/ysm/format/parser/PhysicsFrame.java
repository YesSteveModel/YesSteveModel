package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.model.PhysicsBindPose;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ConstraintFrameProto;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;

record PhysicsFrame(Vector3dc pivot, Vector3dc x, Vector3dc y, Vector3dc z) {
    ConstraintFrameProto proto() {
        return ConstraintFrameProto.newBuilder().addAllPivotOffset(components(pivot)).addAllAxisX(components(x))
            .addAllAxisY(components(y)).addAllAxisZ(components(z)).build();
    }

    static PhysicsFrame read(PhysicsJson frame) throws AssetLoadException {
        return read(frame, frame.optionalVector("pivot_offset"));
    }

    static PhysicsFrame readLocal(PhysicsJson frame) throws AssetLoadException {
        return read(frame, vector(frame.optionalFloatVector("pivot_offset")));
    }

    private static PhysicsFrame read(PhysicsJson frame, Vector3dc pivot) throws AssetLoadException {
        frame.only("pivot_offset", "rotation_deg", "axis_x", "axis_y");
        if (frame.has("axis_x") || frame.has("axis_y")) {
            if (frame.has("rotation_deg")) {
                throw frame.error("must use either rotation_deg or the axis_x and axis_y pair");
            }
            var x = frame.vector("axis_x");
            var y = frame.vector("axis_y");
            normalize(x, frame);
            normalize(y, frame);
            if (Math.abs(x.dot(y)) > 1e-3) {
                throw frame.error("needs an orthonormal right-handed basis (input axes must be orthogonal)");
            }
            var z = x.cross(y, new Vector3d()).normalize();
            return new PhysicsFrame(pivot, x, z.cross(x, new Vector3d()), z);
        }
        var rotation = degrees(frame.optionalVector("rotation_deg"));
        return new PhysicsFrame(pivot, rotation.transform(new Vector3d(1, 0, 0)),
            rotation.transform(new Vector3d(0, 1, 0)), rotation.transform(new Vector3d(0, 0, 1)));
    }

    PhysicsFrame at(Vector3dc position) {
        return new PhysicsFrame(new Vector3d(position).add(pivot), x, y, z);
    }

    ConstraintFrameProto localize(PhysicsBindPose.Pose pose) {
        var inverse = new Quaterniond(pose.rotation()).conjugate();
        var relative = new Vector3d(pivot).sub(pose.position());
        return new PhysicsFrame(inverse.transform(relative), inverse.transform(x, new Vector3d()),
            inverse.transform(y, new Vector3d()), inverse.transform(z, new Vector3d())).proto();
    }

    static List<Float> components(Vector3dc value) {
        return List.of((float)value.x(), (float)value.y(), (float)value.z());
    }

    static Vector3d vector(List<Float> value) {
        return new Vector3d(value.get(0), value.get(1), value.get(2));
    }

    static Quaterniond degrees(Vector3d angles) {
        return PhysicsBindPose.rotation(angles.mul(Math.PI / 180));
    }

    static Vector3d normalize(Vector3d axis, PhysicsJson context) throws AssetLoadException {
        double scale = Math.max(Math.abs(axis.x), Math.max(Math.abs(axis.y), Math.abs(axis.z)));
        if (!(scale > 0 && Double.isFinite(scale))) {
            throw context.error("needs finite nonzero axes");
        }
        return axis.set(axis.x / scale, axis.y / scale, axis.z / scale).normalize();
    }
}
