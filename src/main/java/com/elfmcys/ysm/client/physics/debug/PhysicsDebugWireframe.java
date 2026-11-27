package com.elfmcys.ysm.client.physics.debug;

import com.elfmcys.ysm.client.physics.debug.PhysicsDebugController.Layer;
import com.elfmcys.ysm.client.physics.debug.PhysicsDebugController.Selection;
import com.elfmcys.ysm.client.physics.debug.PhysicsDebugController.View;
import com.elfmcys.ysm.natives.physics.PhysicsDebugSnapshot.Pose;
import com.elfmcys.ysm.natives.physics.PhysicsDebugSnapshot.Reading;
import com.elfmcys.ysm.natives.physics.PhysicsDebugSnapshot.Vec3;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AxisConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollisionShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LimitKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class PhysicsDebugWireframe {
    private static final float AXIS_LENGTH = 0.19F;
    private static final float LIMIT_LENGTH = 0.26F;
    private static final int RING_SEGMENTS = 32;
    private static final int X_COLOR = 0xFF5959;
    private static final int Y_COLOR = 0x73E04D;
    private static final int Z_COLOR = 0x578CFF;
    private static final int ACTIVE_COLOR = 0xFFBC55;
    private static final int PASSIVE_COLOR = 0x5EE7F2;
    private static final int INACTIVE_COLOR = 0xBD8DC9;
    private static final int FIRST_COLOR = 0xFFFFFF;
    private static final int SECOND_COLOR = 0xADB8C9;
    private static final int HARD_COLOR = 0xFFC95D;
    private static final int SOFT_COLOR = 0xF58DE3;
    private static final int REST_COLOR = 0xFFEA91;
    private static final int ANIMATION_COLOR = 0xACEDFF;

    private PhysicsDebugWireframe() {}

    public static void render(PoseStack stack, MultiBufferSource buffers, View view) {
        var snapshot = view.snapshot();
        if (snapshot == null) {
            return;
        }
        var lines = new Lines(stack.last(), buffers.getBuffer(RenderType.lines()));
        var metadata = view.metadata();
        var nodes = metadata.nodes();
        var constraints = metadata.constraints();
        var selection = view.selection();
        if (view.layer() == Layer.ALL || view.layer() == Layer.SHAPES) {
            for (int i = 0; i < nodes.size(); i++) {
                var definition = nodes.get(i);
                if (!showsNode(selection, constraints, i, definition.id())) {
                    continue;
                }
                var node = snapshot.nodes().get(i);
                if (node.pose() == null) {
                    continue;
                }
                var frame = new Frame(node.pose());
                int color = !node.active() ? INACTIVE_COLOR
                    : definition.kind() == PhysicsNodeKind.DYNAMIC ? ACTIVE_COLOR : PASSIVE_COLOR;
                if (node.scale() > 0) {
                    for (var shape : definition.shapes()) {
                        drawShape(lines, frame, shape, node.scale() / 16F, color);
                    }
                }
                if (!node.active() || definition.kind() == PhysicsNodeKind.ANCHOR) {
                    cross(lines, frame, 0.04F, color);
                }
            }
        }

        if (view.layer() == Layer.SHAPES) {
            return;
        }
        for (int i = 0; i < constraints.size(); i++) {
            var definition = constraints.get(i);
            if (!showsJoint(selection, definition, i, nodes)) {
                continue;
            }
            var joint = snapshot.joints().get(i);
            var a = joint.frameA() == null ? null : new Frame(joint.frameA());
            var b = joint.frameB() == null ? null : new Frame(joint.frameB());
            if (view.layer() == Layer.ALL || view.layer() == Layer.ANCHORS) {
                if (a != null) {
                    cross(lines, a, 0.047F, FIRST_COLOR);
                }
                if (b != null) {
                    cross(lines, b, 0.027F, SECOND_COLOR);
                }
                if (a != null && b != null) {
                    lines.draw(a.origin(), b.origin(), SECOND_COLOR);
                }
            }
            if (view.layer() == Layer.ALL || view.layer() == Layer.AXES) {
                axes(lines, a, AXIS_LENGTH);
                axes(lines, b, AXIS_LENGTH * 0.65F);
            }
            if (a != null && (view.layer() == Layer.ALL || view.layer() == Layer.LIMITS)) {
                limits(lines, a, definition);
                if (joint.scaleA() > 0 && hasTarget(definition)) {
                    target(lines, a, definition.kind(), joint.rest(), joint.scaleA(), REST_COLOR);
                    if (joint.animation() != null) {
                        target(lines, a, definition.kind(), joint.animation(), 1F, ANIMATION_COLOR);
                    }
                }
            }
        }
    }

    private static boolean showsNode(Selection selection, List<PhysicsConstraintProto> constraints, int index,
        String id) {
        return switch (selection.kind()) {
            case ALL -> true;
            case NODE -> index == selection.first();
            case PAIR -> index == selection.first() || index == selection.second();
            case CONSTRAINT -> {
                var joint = constraints.get(selection.first());
                yield joint.nodeA().equals(id) || joint.nodeB().equals(id);
            }
        };
    }

    private static boolean showsJoint(Selection selection, PhysicsConstraintProto joint, int index,
        List<PhysicsNodeProto> nodes) {
        return switch (selection.kind()) {
            case ALL -> true;
            case CONSTRAINT -> index == selection.first();
            case NODE -> joint.nodeA().equals(nodes.get(selection.first()).id())
                || joint.nodeB().equals(nodes.get(selection.first()).id());
            case PAIR -> {
                String first = nodes.get(selection.first()).id();
                String second = nodes.get(selection.second()).id();
                yield joint.nodeA().equals(first) && joint.nodeB().equals(second)
                    || joint.nodeA().equals(second) && joint.nodeB().equals(first);
            }
        };
    }

    private static void drawShape(Lines lines, Frame body, CollisionShapeProto shape, float scale, int color) {
        switch (shape.shapeCase()) {
            case SPHERE -> {
                var sphere = shape.sphere();
                sphere(lines, new Frame(body.point(vector(sphere.center()).mul(scale)), body.rotation()),
                    sphere.radius() * scale, color);
            }
            case CAPSULE -> {
                var capsule = shape.capsule();
                capsule(lines, body, vector(capsule.pointA()).mul(scale), vector(capsule.pointB()).mul(scale),
                    capsule.radius() * scale, color);
            }
            case BOX -> {
                var box = shape.box();
                var q = box.rotation();
                var rotation = new Quaternionf(q.get(0), q.get(1), q.get(2), q.get(3)).normalize();
                var frame = new Frame(body.point(vector(box.center()).mul(scale)),
                    new Quaternionf(body.rotation()).mul(rotation));
                var half = vector(box.size_()).mul(scale * 0.5F);
                var corners = new Vector3f[8];
                for (int i = 0; i < corners.length; i++) {
                    corners[i] = frame.point(new Vector3f((i & 1) == 0 ? -half.x : half.x,
                        (i & 2) == 0 ? -half.y : half.y, (i & 4) == 0 ? -half.z : half.z));
                }
                for (int bit = 1; bit <= 4; bit <<= 1) {
                    for (int i = 0; i < corners.length; i++) {
                        if ((i & bit) == 0) {
                            lines.draw(corners[i], corners[i | bit], color);
                        }
                    }
                }
            }
            case NONE -> throw new IllegalStateException("Unvalidated collision shape");
        }
    }

    private static void sphere(Lines lines, Frame frame, float radius, int color) {
        ring(lines, frame, new Vector3f(), new Vector3f(radius, 0, 0), new Vector3f(0, radius, 0), color);
        ring(lines, frame, new Vector3f(), new Vector3f(radius, 0, 0), new Vector3f(0, 0, radius), color);
        ring(lines, frame, new Vector3f(), new Vector3f(0, radius, 0), new Vector3f(0, 0, radius), color);
    }

    private static void capsule(Lines lines, Frame frame, Vector3f a, Vector3f b, float radius, int color) {
        var axis = new Vector3f(b).sub(a);
        if (axis.lengthSquared() == 0) {
            sphere(lines, new Frame(frame.point(a), frame.rotation()), radius, color);
            return;
        }
        axis.normalize();
        var radial = Math.abs(axis.y) < 0.9F ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
        radial.cross(axis).normalize(radius);
        var tangent = new Vector3f(axis).cross(radial);
        ring(lines, frame, a, radial, tangent, color);
        ring(lines, frame, b, radial, tangent, color);
        for (var r : List.of(radial, tangent)) {
            for (var side : List.of(r, new Vector3f(r).negate())) {
                lines.draw(frame.point(new Vector3f(a).add(side)), frame.point(new Vector3f(b).add(side)), color);
            }
            arc(lines, frame, a, r, new Vector3f(axis).mul(-radius), 0, Math.PI, color);
            arc(lines, frame, b, r, new Vector3f(axis).mul(radius), 0, Math.PI, color);
        }
    }

    private static void cross(Lines lines, Frame frame, float size, int color) {
        lines.draw(frame.point(new Vector3f(-size, 0, 0)), frame.point(new Vector3f(size, 0, 0)), color);
        lines.draw(frame.point(new Vector3f(0, -size, 0)), frame.point(new Vector3f(0, size, 0)), color);
        lines.draw(frame.point(new Vector3f(0, 0, -size)), frame.point(new Vector3f(0, 0, size)), color);
    }

    private static void axes(Lines lines, @Nullable Frame frame, float size) {
        if (frame != null) {
            lines.draw(frame.origin(), frame.point(new Vector3f(size, 0, 0)), X_COLOR);
            lines.draw(frame.origin(), frame.point(new Vector3f(0, size, 0)), Y_COLOR);
            lines.draw(frame.origin(), frame.point(new Vector3f(0, 0, size)), Z_COLOR);
        }
    }

    private static void limits(Lines lines, Frame frame, PhysicsConstraintProto joint) {
        if (joint.kind() == PhysicsConstraintKind.HINGE) {
            twist(lines, frame, Bounds.of(joint.angularZUnsafe()), true);
            return;
        }
        if (joint.kind() == PhysicsConstraintKind.FIXED) {
            return;
        }
        twist(lines, frame, Bounds.of(joint.angularXUnsafe()), false);
        if (joint.hasCone()) {
            var cone = joint.coneUnsafe();
            double alpha = Math.min(cone.halfAngleUnsafe(), Math.PI);
            int color = cone.limit() == LimitKind.SOFT ? SOFT_COLOR : HARD_COLOR;
            if (alpha == Math.PI) {
                sphere(lines, frame, LIMIT_LENGTH, color);
                return;
            }
            float x = LIMIT_LENGTH * (float)Math.cos(alpha);
            float radius = LIMIT_LENGTH * (float)Math.sin(alpha);
            var center = new Vector3f(x, 0, 0);
            var y = new Vector3f(0, radius, 0);
            var z = new Vector3f(0, 0, radius);
            ring(lines, frame, center, y, z, color);
            for (var edge : List.of(y, new Vector3f(y).negate(), z, new Vector3f(z).negate())) {
                lines.draw(frame.origin(), frame.point(new Vector3f(center).add(edge)), color);
            }
            return;
        }
        var y = Bounds.of(joint.angularYUnsafe());
        var z = Bounds.of(joint.angularZUnsafe());
        if (y.kind() != LimitKind.FREE || z.kind() != LimitKind.FREE) {
            int color = y.kind() == LimitKind.SOFT || z.kind() == LimitKind.SOFT ? SOFT_COLOR : HARD_COLOR;
            SwingLimitOutline.trace(y.min(), y.max(), z.min(), z.max(), (y1, z1, y2, z2) -> {
                var a = frame.point(swingDirection(y1, z1));
                if (y1 == y2 && z1 == z2) {
                    lines.draw(frame.origin(), a, color);
                } else {
                    lines.draw(a, frame.point(swingDirection(y2, z2)), color);
                }
            });
        }
    }

    private static void twist(Lines lines, Frame frame, Bounds bounds, boolean hinge) {
        if (bounds.kind() == LimitKind.FREE && !hinge) {
            return;
        }
        int color = bounds.kind() == LimitKind.SOFT ? SOFT_COLOR : HARD_COLOR;
        var u = hinge ? new Vector3f(LIMIT_LENGTH, 0, 0) : new Vector3f(0, LIMIT_LENGTH, 0);
        var v = hinge ? new Vector3f(0, LIMIT_LENGTH, 0) : new Vector3f(0, 0, LIMIT_LENGTH);
        var center = new Vector3f();
        if (bounds.max() > bounds.min()) {
            arc(lines, frame, center, u, v, bounds.min(), bounds.max(), color);
        }
        if (bounds.max() - bounds.min() < 2 * Math.PI) {
            lines.draw(frame.origin(), frame.point(radial(center, u, v, bounds.min())), color);
            if (bounds.max() != bounds.min()) {
                lines.draw(frame.origin(), frame.point(radial(center, u, v, bounds.max())), color);
            }
        }
    }

    private static Vector3f swingDirection(double y, double z) {
        double magnitude = Math.hypot(y, z);
        // Rodrigues' formula for rotating +X by the signed Y/Z rotation vector.
        double factor = magnitude == 0 ? LIMIT_LENGTH : LIMIT_LENGTH * Math.sin(magnitude) / magnitude;
        return new Vector3f(LIMIT_LENGTH * (float)Math.cos(magnitude), (float)(z * factor), (float)(-y * factor));
    }

    private static boolean hasTarget(PhysicsConstraintProto joint) {
        for (var axis : new AxisConfigProto[] {joint.linearXUnsafe(), joint.linearYUnsafe(), joint.linearZUnsafe(),
            joint.angularXUnsafe(), joint.angularYUnsafe(), joint.angularZUnsafe()}) {
            if (axis != null && axis.hasSpring() && axis.springUnsafe().target() != SpringTarget.OFF) {
                return true;
            }
        }
        return false;
    }

    private static void target(Lines lines, Frame frame, PhysicsConstraintKind kind, Reading reading, float scale,
        int color) {
        var offset = vector(reading.linear()).mul(scale);
        var point = frame.point(offset);
        lines.draw(frame.origin(), point, color);
        if (!reading.rotationSingular()) {
            var angle = reading.angular();
            boolean hinge = kind == PhysicsConstraintKind.HINGE;
            var direction =
                hinge ? new Vector3f((float)Math.cos(angle.z()), (float)Math.sin(angle.z()), 0).mul(LIMIT_LENGTH)
                    : swingDirection(angle.y(), angle.z());
            lines.draw(point, frame.point(direction.add(offset)), color);
            if (!hinge) {
                var orientation = new Quaternionf();
                float swing = (float)Math.hypot(angle.y(), angle.z());
                if (swing > 0) {
                    orientation.rotationAxis(swing, 0, angle.y() / swing, angle.z() / swing);
                }
                orientation.rotateX(angle.x());
                lines.draw(point,
                    frame.point(orientation.transform(new Vector3f(0, AXIS_LENGTH * 0.6F, 0)).add(offset)), color);
            }
        } else {
            cross(lines, new Frame(point, frame.rotation()), 0.02F, color);
        }
    }

    private static Vector3f vector(Vec3 values) {
        return new Vector3f(values.x(), values.y(), values.z());
    }

    private static Vector3f vector(List<Float> values) {
        return new Vector3f(values.get(0), values.get(1), values.get(2));
    }

    private static Vector3f radial(Vector3f center, Vector3f u, Vector3f v, double angle) {
        return new Vector3f(center).fma((float)Math.cos(angle), u).fma((float)Math.sin(angle), v);
    }

    private static void ring(Lines lines, Frame frame, Vector3f center, Vector3f u, Vector3f v, int color) {
        arc(lines, frame, center, u, v, 0, 2 * Math.PI, color);
    }

    private static void arc(Lines lines, Frame frame, Vector3f center, Vector3f u, Vector3f v, double start, double end,
        int color) {
        var previous = frame.point(radial(center, u, v, start));
        int segments = Math.max(1, (int)Math.ceil(RING_SEGMENTS * (end - start) / (2 * Math.PI)));
        for (int i = 1; i <= segments; i++) {
            var next = frame.point(radial(center, u, v, start + (end - start) * i / segments));
            lines.draw(previous, next, color);
            previous = next;
        }
    }

    private record Bounds(LimitKind kind, double min, double max) {
        static Bounds of(@Nullable AxisConfigProto axis) {
            var kind = axis == null ? LimitKind.FREE : axis.limit().orElse(LimitKind.FREE);
            return switch (kind) {
                case LOCKED -> new Bounds(kind, 0, 0);
                case HARD, SOFT -> new Bounds(kind, clamp(axis.minUnsafe()), clamp(axis.maxUnsafe()));
                case FREE -> new Bounds(kind, -Math.PI, Math.PI);
                default -> throw new IllegalStateException("Unvalidated angular limit");
            };
        }

        private static double clamp(float radians) {
            return Math.max(-Math.PI, Math.min(Math.PI, radians));
        }
    }

    private record Frame(Vector3f origin, Quaternionf rotation) {
        Frame(Pose pose) {
            this(vector(pose.position()),
                new Quaternionf(pose.rotation().x(), pose.rotation().y(), pose.rotation().z(), pose.rotation().w()));
        }

        Vector3f point(Vector3f local) {
            return rotation.transform(local).add(origin);
        }
    }

    private record Lines(PoseStack.Pose pose, VertexConsumer buffer) {
        void draw(Vector3f a, Vector3f b, int rgb) {
            var direction = new Vector3f(b).sub(a);
            if (direction.lengthSquared() == 0) {
                return;
            }
            direction.normalize();
            int r = rgb >> 16;
            int g = (rgb >> 8) & 255;
            int blue = rgb & 255;
            vertex(a, direction, r, g, blue);
            vertex(b, direction, r, g, blue);
        }

        private void vertex(Vector3f p, Vector3f direction, int r, int g, int b) {
            buffer.vertex(pose.pose(), p.x, p.y, p.z).color(r, g, b, 255)
                .normal(pose.normal(), direction.x, direction.y, direction.z).endVertex();
        }
    }
}
