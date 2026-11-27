package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AxisConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollisionShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ConeConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ConstraintFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LimitKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsDefinitionProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringParametersProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringTarget;
import it.unimi.dsi.fastutil.floats.FloatList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/** Establishes the static physics contract shared by raw compilation and ModelData admission. */
public final class PhysicsDefinitionValidator {
    private static final int COLLECTION_SLOT_COUNT = 20;
    private static final int COLLECTION_MASK = (1 << COLLECTION_SLOT_COUNT) - 1;
    private static final int MAX_NODES = 128;
    private static final int MAX_CONSTRAINTS = 256;
    private static final int MAX_NODE_SHAPES = 32;
    private static final int MAX_MODEL_SHAPES = 512;
    private static final float MAX_SCALAR = 1e4f;
    private static final float MIN_SHAPE_SIZE = 1e-3f;
    private static final float PI = (float)Math.PI;
    private static final double SWING_RADIUS = Math.toRadians(179);
    // Unit axes and quaternion components pass through float serialization.
    private static final double UNIT_TOLERANCE = 1e-5;

    private PhysicsDefinitionValidator() {}

    public static void validate(PhysicsDefinitionProto physics, GeoModelProto mainGeo, String targetId)
        throws AssetLoadException {
        validate(physics, new PhysicsBindPose(mainGeo), targetId);
    }

    public static void validate(PhysicsDefinitionProto physics, PhysicsBindPose bindPose, String targetId)
        throws AssetLoadException {
        if (!RenderTargetIds.PLAYER.equals(targetId)) {
            throw AssetLoadException.content("Render target '" + targetId + "' cannot carry physics");
        }
        require(physics.nodes().size() <= MAX_NODES, "Physics supports at most 128 nodes");
        require(physics.constraints().size() <= MAX_CONSTRAINTS, "Physics supports at most 256 constraints");
        if (!physics.gravity().isEmpty()) {
            validateLocalVector(physics.gravity(), "Physics gravity");
        }
        var slots = new HashSet<Integer>();
        for (var collection : physics.collections()) {
            int slot = collection.slot();
            require(slot >= 1 && slot <= COLLECTION_SLOT_COUNT,
                "Physics collection slot must be within 1..20: " + Integer.toUnsignedString(slot));
            require(slots.add(slot), "Duplicate physics collection slot " + slot);
        }

        var nodes = new LinkedHashMap<String, PhysicsNodeKind>();
        var dynamicBones = new HashSet<String>();
        int shapes = 0;
        for (var node : physics.nodes()) {
            var context = "Physics node '" + node.id() + "'";
            require(!node.id().isEmpty(), "Physics node id must be nonempty");
            require(nodes.putIfAbsent(node.id(), node.kind()) == null, context + " has a duplicate id");
            require(node.hasBone(), context + " needs an explicit bone binding");
            var bone = node.boneUnsafe();
            bindPose.resolve(bone, context);
            require(node.shapes().size() <= MAX_NODE_SHAPES, context + " supports at most 32 collision shapes");
            shapes += node.shapes().size();
            require(shapes <= MAX_MODEL_SHAPES, "Physics supports at most 512 collision shapes per model");
            validateNode(node, context);
            if (node.kind() == PhysicsNodeKind.DYNAMIC) {
                require(dynamicBones.add(bone), context + " duplicates dynamic bone '" + bone + "'");
            }
        }
        if (!dynamicBones.isEmpty()) {
            var dynamicLineage = bindPose.bonesWithDynamicAncestor(dynamicBones);
            for (var node : physics.nodes()) {
                if (node.kind() != PhysicsNodeKind.DYNAMIC) {
                    require(!dynamicLineage.contains(node.boneUnsafe()), "Physics node '" + node.id() + "' bone '"
                        + node.boneUnsafe() + "' must be a pure animation bone outside the dynamic bone lineages");
                }
            }
        }

        var constraintIds = new HashSet<String>();
        var dynamicEdges = new HashMap<String, List<String>>();
        var anchored = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        for (var constraint : physics.constraints()) {
            var context = "Physics constraint '" + constraint.id() + "'";
            require(!constraint.id().isEmpty(), "Physics constraint id must be nonempty");
            require(constraintIds.add(constraint.id()), context + " has a duplicate id");
            var a = constraint.nodeA();
            var b = constraint.nodeB();
            require(!a.equals(b), context + " connects node '" + a + "' to itself");
            require(nodes.containsKey(a), context + " references unknown node '" + a + "'");
            require(nodes.containsKey(b), context + " references unknown node '" + b + "'");
            boolean dynamicA = nodes.get(a) == PhysicsNodeKind.DYNAMIC;
            boolean dynamicB = nodes.get(b) == PhysicsNodeKind.DYNAMIC;
            require(dynamicA || dynamicB, context + " needs at least one dynamic endpoint");
            validateConstraint(constraint, context);
            if (!isEffectiveMechanical(constraint)) {
                continue;
            }
            if (dynamicA && dynamicB) {
                dynamicEdges.computeIfAbsent(a, key -> new ArrayList<>()).add(b);
                dynamicEdges.computeIfAbsent(b, key -> new ArrayList<>()).add(a);
            } else {
                var dynamic = dynamicA ? a : b;
                if (anchored.add(dynamic)) {
                    queue.addLast(dynamic);
                }
            }
        }
        validateAnchoring(nodes, dynamicEdges, anchored, queue);
    }

    private static void validateAnchoring(Map<String, PhysicsNodeKind> nodes, Map<String, List<String>> dynamicEdges,
        HashSet<String> anchored, ArrayDeque<String> queue) throws AssetLoadException {
        // Each seeded dynamic node has a direct path to an anchor or proxy. A
        // walk from all seeds handles both open branches and closed joint loops.
        while (!queue.isEmpty()) {
            for (var neighbor : dynamicEdges.getOrDefault(queue.removeFirst(), List.of())) {
                if (anchored.add(neighbor)) {
                    queue.addLast(neighbor);
                }
            }
        }
        for (var node : nodes.entrySet()) {
            require(node.getValue() != PhysicsNodeKind.DYNAMIC || anchored.contains(node.getKey()),
                "Physics node '" + node.getKey()
                    + "' has no constraint path to an anchor or proxy via effective mechanical constraints");
        }
    }

    private static void validateNode(PhysicsNodeProto node, String context) throws AssetLoadException {
        boolean hasDynamics = node.hasMass() || node.hasLinearDamping() || node.hasAngularDamping()
            || node.hasGravityScale() || node.hasDisableWhenHidden();
        switch (node.kind()) {
            case ANCHOR -> {
                require(node.shapes().isEmpty() && !node.hasCollectionMask() && !node.hasFriction()
                    && !node.hasBounciness() && !hasDynamics, context + " anchor can only bind an animation bone");
                return;
            }
            case PROXY -> require(!hasDynamics, context + " proxy cannot declare dynamic properties");
            case DYNAMIC -> {
                require(node.hasMass(), context + " dynamic node needs mass");
                validateRange(node.massUnsafe(), 1e-4f, 1e6f, context + " mass");
                validateRange(node.linearDamping().orElse(0F), 0, MAX_SCALAR, context + " linear damping");
                validateRange(node.angularDamping().orElse(0F), 0, MAX_SCALAR, context + " angular damping");
                validateRange(node.gravityScale().orElse(1F), -MAX_SCALAR, MAX_SCALAR, context + " gravity scale");
            }
            default -> throw AssetLoadException.content(context + " has unsupported node kind " + node.kindValue());
        }

        require((node.collectionMask().orElse(1) & ~COLLECTION_MASK) == 0,
            context + " collection mask must use only slots 1..20");
        validateRange(node.friction().orElse(0F), 0, MAX_SCALAR, context + " friction");
        validateRange(node.bounciness().orElse(0F), 0, 1, context + " bounciness");
        require(!node.shapes().isEmpty(), context + " needs at least one collision shape");
        int index = 0;
        for (var shape : node.shapes()) {
            validateShape(shape, context + " shape[" + index++ + "]");
        }
    }

    private static void validateShape(CollisionShapeProto shape, String context) throws AssetLoadException {
        switch (shape.shapeCase()) {
            case SPHERE -> {
                var sphere = shape.sphere();
                validateLocalVector(sphere.center(), context + " center");
                validateRange(sphere.radius(), MIN_SHAPE_SIZE, MAX_SCALAR, context + " radius");
            }
            case CAPSULE -> {
                var capsule = shape.capsule();
                validateLocalVector(capsule.pointA(), context + " point A");
                validateLocalVector(capsule.pointB(), context + " point B");
                validateRange(capsule.radius(), MIN_SHAPE_SIZE, MAX_SCALAR, context + " radius");
                double squaredLength = 0;
                for (int i = 0; i < 3; i++) {
                    double delta = (double)capsule.pointB().getFloat(i) - capsule.pointA().getFloat(i);
                    squaredLength += delta * delta;
                }
                require(squaredLength <= (double)MAX_SCALAR * MAX_SCALAR,
                    context + " capsule endpoint distance exceeds 10000 pixels");
            }
            case BOX -> {
                var box = shape.box();
                validateLocalVector(box.center(), context + " center");
                validateVector(box.size_(), 3, context + " size");
                for (float size : box.size_()) {
                    validateRange(size, MIN_SHAPE_SIZE, MAX_SCALAR, context + " box dimensions");
                }
                validateVector(box.rotation(), 4, context + " rotation");
                require(near(dot(box.rotation(), box.rotation()), 1), context + " needs a unit rotation quaternion");
            }
            case NONE -> throw AssetLoadException.content(context + " has no supported shape type");
        }
    }

    private static void validateConstraint(PhysicsConstraintProto constraint, String context)
        throws AssetLoadException {
        validateFrame(constraint.frameA(), context + " frame A");
        validateFrame(constraint.frameB(), context + " frame B");

        boolean linear = constraint.hasLinearX() || constraint.hasLinearY() || constraint.hasLinearZ();
        switch (constraint.kind()) {
            case FIXED ->
                require(!linear && !constraint.hasAngularX() && !constraint.hasAngularY() && !constraint.hasAngularZ()
                    && !constraint.hasCone(), context + " fixed joint has no configurable axes");
            case HINGE -> {
                require(!linear && !constraint.hasAngularX() && !constraint.hasAngularY() && !constraint.hasCone(),
                    context + " hinge exposes only angular Z");
                validateAxis(constraint.angularZUnsafe(), true, false, context + " angular Z");
            }
            case POINT -> {
                require(!linear, context + " point joint locks all linear axes");
                boolean cone = constraint.hasCone();
                if (cone) {
                    validateCone(constraint.coneUnsafe(), context + " cone");
                }
                validateAxis(constraint.angularXUnsafe(), true, false, context + " angular X");
                validateAxis(constraint.angularYUnsafe(), true, cone, context + " angular Y");
                validateAxis(constraint.angularZUnsafe(), true, cone, context + " angular Z");
                validateSwingDisk(constraint, context);
            }
            case GENERIC -> {
                require(!constraint.hasCone(), context + " cone requires a point joint");
                validateAxis(constraint.linearXUnsafe(), false, false, context + " linear X");
                validateAxis(constraint.linearYUnsafe(), false, false, context + " linear Y");
                validateAxis(constraint.linearZUnsafe(), false, false, context + " linear Z");
                validateAxis(constraint.angularXUnsafe(), true, false, context + " angular X");
                validateAxis(constraint.angularYUnsafe(), true, false, context + " angular Y");
                validateAxis(constraint.angularZUnsafe(), true, false, context + " angular Z");
                validateSwingDisk(constraint, context);
            }
            default -> throw AssetLoadException
                .content(context + " has unsupported constraint kind " + constraint.kindValue());
        }
    }

    private static void validateFrame(ConstraintFrameProto frame, String context) throws AssetLoadException {
        validateLocalVector(frame.pivotOffset(), context + " pivot offset");
        var x = frame.axisX();
        var y = frame.axisY();
        var z = frame.axisZ();
        validateVector(x, 3, context + " X axis");
        validateVector(y, 3, context + " Y axis");
        validateVector(z, 3, context + " Z axis");
        double determinant = x.get(0) * ((double)y.get(1) * z.get(2) - (double)y.get(2) * z.get(1))
            + x.get(1) * ((double)y.get(2) * z.get(0) - (double)y.get(0) * z.get(2))
            + x.get(2) * ((double)y.get(0) * z.get(1) - (double)y.get(1) * z.get(0));
        require(near(dot(x, x), 1) && near(dot(y, y), 1) && near(dot(z, z), 1) && near(dot(x, y), 0)
            && near(dot(x, z), 0) && near(dot(y, z), 0) && near(determinant, 1),
            context + " needs an orthonormal right-handed basis");
    }

    private static void validateSwingDisk(PhysicsConstraintProto constraint, String context) throws AssetLoadException {
        double y = closestHardBound(constraint.angularYUnsafe());
        double z = closestHardBound(constraint.angularZUnsafe());
        require(y * y + z * z <= SWING_RADIUS * SWING_RADIUS,
            context + " hard swing bounds must intersect the 179 degree disk");
    }

    private static double closestHardBound(@Nullable AxisConfigProto axis) {
        if (axis == null || axis.limitUnsafe() != LimitKind.HARD) {
            return 0;
        }
        return Math.max(axis.minUnsafe(), Math.min(0, axis.maxUnsafe()));
    }

    private static boolean isEffectiveMechanical(PhysicsConstraintProto constraint) {
        // Fixed, point and hinge joints always constrain translation.
        return constraint.kind() != PhysicsConstraintKind.GENERIC || isEffectiveAxis(constraint.linearXUnsafe(), false)
            || isEffectiveAxis(constraint.linearYUnsafe(), false) || isEffectiveAxis(constraint.linearZUnsafe(), false)
            || isEffectiveAxis(constraint.angularXUnsafe(), true) || isEffectiveAxis(constraint.angularYUnsafe(), true)
            || isEffectiveAxis(constraint.angularZUnsafe(), true);
    }

    private static boolean isEffectiveAxis(@Nullable AxisConfigProto axis, boolean angular) {
        if (axis == null) {
            return false;
        }
        var limit = axis.limit().orElse(LimitKind.FREE);
        boolean effectiveLimit = switch (limit) {
            case FREE -> false;
            case LOCKED -> true;
            case HARD, SOFT -> {
                boolean boundedRange = !angular || axis.minUnsafe() > -PI || axis.maxUnsafe() < PI;
                yield boundedRange && (limit == LimitKind.HARD || isEffectiveSpring(axis.softLimitSpringUnsafe()));
            }
            default -> throw new IllegalStateException("Unvalidated limit");
        };
        var spring = axis.springUnsafe();
        return effectiveLimit
            || spring != null && spring.target() != SpringTarget.OFF && isEffectiveSpring(spring.parametersUnsafe());
    }

    private static boolean isEffectiveSpring(SpringParametersProto parameters) {
        return switch (parameters.modeCase()) {
            case FREQUENCY_DAMPING -> true;
            case STIFFNESS_DAMPING -> {
                var values = parameters.stiffnessDamping();
                yield values.stiffnessUnsafe() > 0 || values.dampingUnsafe() > 0;
            }
            case NONE -> throw new IllegalStateException("Unvalidated spring parameters");
        };
    }

    private static void validateAxis(@Nullable AxisConfigProto axis, boolean angular, boolean cone, String context)
        throws AssetLoadException {
        if (axis == null) {
            return;
        }
        var limit = axis.limit().orElse(LimitKind.FREE);
        switch (limit) {
            case FREE, LOCKED -> require(!axis.hasMin() && !axis.hasMax(), context + " has bounds without a limit");
            case HARD, SOFT -> {
                require(axis.hasMin() && axis.hasMax(), context + " needs both limit bounds");
                float min = axis.minUnsafe();
                float max = axis.maxUnsafe();
                require(Float.isFinite(min) && Float.isFinite(max) && min <= max,
                    context + " needs finite limit bounds with min <= max");
                if (angular) {
                    require(min >= -PI && max <= PI, context + " limit must be within [-pi, pi]");
                } else {
                    require(min >= -MAX_SCALAR && max <= MAX_SCALAR,
                        context + " limit must be within [-10000, 10000] pixels");
                }
            }
            default ->
                throw AssetLoadException.content(context + " has unsupported limit " + axis.limitValue().orElse(0));
        }
        require(!cone || limit == LimitKind.FREE, context + " cannot combine a cone with per-axis swing limits");
        validateSoftLimit(limit, axis.softLimitSpringUnsafe(), context);
        if (axis.hasSpring()) {
            var spring = axis.springUnsafe();
            switch (spring.target()) {
                case OFF -> require(!spring.hasParameters(), context + " disabled spring cannot have parameters");
                case REST_POSE, ANIMATION -> {
                    require(limit != LimitKind.LOCKED, context + " locked axis cannot enable a spring");
                    validateSpringParameters(spring.parametersUnsafe(), context + " target spring");
                }
                default -> throw AssetLoadException
                    .content(context + " has unsupported spring target " + spring.targetValue());
            }
        }
    }

    private static void validateCone(ConeConfigProto cone, String context) throws AssetLoadException {
        var limit = cone.limit();
        require(limit == LimitKind.HARD || limit == LimitKind.SOFT, context + " needs a hard or soft limit");
        require(cone.hasHalfAngle(), context + " needs a half-angle");
        validateRange(cone.halfAngleUnsafe(), 0, PI, context + " half-angle");
        validateSoftLimit(limit, cone.softLimitSpringUnsafe(), context);
    }

    private static void validateSoftLimit(LimitKind limit, @Nullable SpringParametersProto parameters, String context)
        throws AssetLoadException {
        if (limit == LimitKind.SOFT) {
            validateSpringParameters(parameters, context + " soft limit");
        } else {
            require(parameters == null, context + " soft limit parameters require the soft limit kind");
        }
    }

    private static void validateSpringParameters(@Nullable SpringParametersProto parameters, String context)
        throws AssetLoadException {
        if (parameters == null) {
            throw AssetLoadException.content(context + " needs spring parameters");
        }
        switch (parameters.modeCase()) {
            case FREQUENCY_DAMPING -> {
                var values = parameters.frequencyDamping();
                validateRange(values.frequency(), 1e-3f, MAX_SCALAR, context + " frequency");
                require(values.hasDampingRatio(), context + " needs a damping ratio");
                validateRange(values.dampingRatioUnsafe(), 0, MAX_SCALAR, context + " damping ratio");
            }
            case STIFFNESS_DAMPING -> {
                var values = parameters.stiffnessDamping();
                require(values.hasStiffness() && values.hasDamping(), context + " needs stiffness and damping");
                validateRange(values.stiffnessUnsafe(), 0, 1e9f, context + " stiffness");
                validateRange(values.dampingUnsafe(), 0, 1e9f, context + " damping");
            }
            case NONE -> throw AssetLoadException.content(context + " has no supported parameter mode");
        }
    }

    private static double dot(FloatList a, FloatList b) {
        double result = 0;
        for (int i = 0; i < a.size(); i++) {
            result += (double)a.get(i) * b.get(i);
        }
        return result;
    }

    private static boolean near(double value, double expected) {
        return Math.abs(value - expected) <= UNIT_TOLERANCE;
    }

    private static void validateVector(FloatList values, int components, String field) throws AssetLoadException {
        require(values.size() == components, field + " needs " + components + " components");
        for (float value : values) {
            require(Float.isFinite(value), field + " must be finite");
        }
    }

    private static void validateLocalVector(FloatList values, String field) throws AssetLoadException {
        require(values.size() == 3, field + " needs 3 components");
        for (float value : values) {
            validateRange(value, -MAX_SCALAR, MAX_SCALAR, field);
        }
    }

    private static void validateRange(float value, float min, float max, String field) throws AssetLoadException {
        require(value >= min && value <= max, field + " must be finite and within [" + min + ", " + max + "]");
    }

    private static void require(boolean condition, String error) throws AssetLoadException {
        if (!condition) {
            throw AssetLoadException.content(error);
        }
    }
}
