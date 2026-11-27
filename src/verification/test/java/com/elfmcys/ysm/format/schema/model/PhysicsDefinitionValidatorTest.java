package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AxisConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoxShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollectionNameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollisionShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ConeConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.FrequencyDampingProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LimitKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsDefinitionProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringParametersProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.StiffnessDampingProto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static com.elfmcys.ysm.format.schema.model.PhysicsWireFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class PhysicsDefinitionValidatorTest {
    @Test
    void acceptsAllPrimitivesAndRoleSpecificProperties() throws Exception {
        validate(definition());
        validate(PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC.withCollectionMask(1 << 19), ANCHOR)
            .addConstraints(FIXED).build());
    }

    @Test
    void acceptsClosedDynamicLoopWithOneProxyPathAndRejectsTheSameLoopWithoutIt() throws Exception {
        var second = DYNAMIC.withId("second").withBone("hair2");
        var third = DYNAMIC.withId("third").withBone("hair3");
        var definition = PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC, second, third, PROXY)
            .addConstraints(FIXED.withNodeA("second"),
                FIXED.withId("second-third").withNodeA("second").withNodeB("third"),
                FIXED.withId("third-first").withNodeA("third"))
            .build();
        assertRejected(definition, "no constraint path");
        validate(definition.toBuilder().addConstraints(POINT).build());
    }

    @Test
    void rejectsWrongTargetAndAmbiguousMainBone() {
        var wrongTarget = assertThrows(AssetLoadException.class,
            () -> PhysicsDefinitionValidator.validate(definition(), geometry("body", "hair"), "vehicle"));
        assertTrue(wrongTarget.getMessage().contains("vehicle"));

        var ambiguous = assertThrows(AssetLoadException.class,
            () -> PhysicsDefinitionValidator.validate(definition(), geometry("body", "hair", "hair"), "player"));
        assertTrue(ambiguous.getMessage().contains("duplicate bone"));
    }

    @Test
    void anExplicitEmptyBindingCanSelectTheUniqueUnnamedBone() throws Exception {
        var definition =
            PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC.withBone(""), ANCHOR).addConstraints(FIXED).build();
        PhysicsDefinitionValidator.validate(definition, geometry("body", ""), "player");
    }

    @Test
    void permitsSphereEquivalentCapsuleAndConeEndpoints() throws Exception {
        var capsule = CAPSULE.withCapsule(CAPSULE.capsule().toBuilder().clearPointB().addPointB(-1, 2, 3).build());
        var node = DYNAMIC.toBuilder().clearShapes().addShapes(capsule).build();
        var cone = ConeConfigProto.newBuilder().setLimit(LimitKind.HARD).setHalfAngle(0).build();
        validate(PhysicsDefinitionProto.newBuilder().addNodes(node, PROXY)
            .addConstraints(POINT.withCone(cone), POINT.withId("wide").withCone(cone.withHalfAngle((float)Math.PI)))
            .build());
    }

    @Test
    void rejectsRepeatedIdentitiesDynamicBoneAndCollectionNameSlot() {
        assertRejected(definition().toBuilder().addNodes(DYNAMIC).build(), "duplicate id");
        assertRejected(definition().toBuilder().addNodes(DYNAMIC.withId("second")).build(), "duplicates dynamic bone");
        assertRejected(definition().toBuilder().addConstraints(FIXED).build(), "duplicate id");
        assertRejected(definition().toBuilder().addCollections(definition().collections().get(0)).build(),
            "Duplicate physics collection slot");
        for (int invalidSlot : new int[] {0, 21, -1}) {
            var invalid = CollectionNameProto.newBuilder().setSlot(invalidSlot).setName("bad").build();
            assertRejected(definition().toBuilder().addCollections(invalid).build(), "within 1..20");
        }
    }

    @ParameterizedTest
    @MethodSource("invalidNodes")
    void rejectsInvalidNodes(PhysicsNodeProto replacement, String reason) {
        assertRejected(PhysicsDefinitionProto.newBuilder().addNodes(replacement, ANCHOR).addConstraints(FIXED).build(),
            reason);
    }

    static Stream<Arguments> invalidNodes() {
        var missingShape = DYNAMIC.toBuilder().clearShapes().build();
        var unknownShape = missingShape.toBuilder().addShapes(CollisionShapeProto.newBuilder().build()).build();
        var badBox = BoxShapeProto.newBuilder().addCenter(1, 2, 3).addSize(1, 0, 1).addRotation(0, 0, 0, 1).build();
        return Stream.of(Arguments.of(DYNAMIC.withId(""), "nonempty"),
            Arguments.of(DYNAMIC.toBuilder().clearBone().build(), "explicit bone binding"),
            Arguments.of(DYNAMIC.withKindValue(78), "unsupported node kind 78"),
            Arguments.of(DYNAMIC.withBone("arm"), "exactly one player/main bone"),
            Arguments.of(missingShape, "at least one collision shape"),
            Arguments.of(unknownShape, "no supported shape type"),
            Arguments.of(DYNAMIC.toBuilder().clearMass().build(), "needs mass"),
            Arguments.of(DYNAMIC.withMass(0), "mass"), Arguments.of(DYNAMIC.withMass(Float.POSITIVE_INFINITY), "mass"),
            Arguments.of(DYNAMIC.withFriction(-1), "friction"),
            Arguments.of(DYNAMIC.withFriction(Float.NaN), "friction"),
            Arguments.of(DYNAMIC.withBounciness(-0.1F), "bounciness"),
            Arguments.of(DYNAMIC.withBounciness(1.1F), "bounciness"),
            Arguments.of(DYNAMIC.withLinearDamping(-1), "linear damping"),
            Arguments.of(DYNAMIC.withAngularDamping(-1), "angular damping"),
            Arguments.of(DYNAMIC.withGravityScale(Float.NEGATIVE_INFINITY), "gravity scale"),
            Arguments.of(DYNAMIC.withCollectionMask(1 << 20), "collection mask"),
            Arguments.of(missingShape.toBuilder().addShapes(SPHERE.withSphere(SPHERE.sphere().withRadius(0))).build(),
                "radius"),
            Arguments
                .of(missingShape.toBuilder().addShapes(CAPSULE.withCapsule(CAPSULE.capsule().withRadius(-1))).build(),
                    "radius"),
            Arguments.of(missingShape.toBuilder().addShapes(BOX.withBox(badBox)).build(), "dimensions"),
            Arguments.of(missingShape.toBuilder()
                .addShapes(BOX.withBox(BOX.box().toBuilder().clearRotation().addRotation(0, 0, 0, 2).build())).build(),
                "unit rotation quaternion"),
            Arguments.of(missingShape.toBuilder()
                .addShapes(
                    SPHERE.withSphere(SPHERE.sphere().toBuilder().clearCenter().addCenter(0, Float.NaN, 0).build()))
                .build(), "center must be finite"));
    }

    @Test
    void rejectsBodyPropertiesOnAnchorsAndDynamicsOnProxies() {
        for (var anchor : new PhysicsNodeProto[] {ANCHOR.withMass(0), ANCHOR.withFriction(0), ANCHOR.withBounciness(0),
            ANCHOR.withCollectionMask(0), ANCHOR.withDisableWhenHidden(false),
            ANCHOR.toBuilder().addShapes(BOX).build()}) {
            assertRejected(PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC, anchor).addConstraints(FIXED).build(),
                "anchor can only bind");
        }
        for (var proxy : new PhysicsNodeProto[] {PROXY.withMass(0), PROXY.withGravityScale(1),
            PROXY.withLinearDamping(0), PROXY.withAngularDamping(0), PROXY.withDisableWhenHidden(false)}) {
            assertRejected(PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC, proxy).addConstraints(POINT).build(),
                "proxy cannot declare dynamic properties");
        }
        assertRejected(PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC, PROXY.toBuilder().clearShapes().build())
            .addConstraints(POINT).build(), "at least one collision shape");
    }

    @Test
    void rejectsIncompleteOrNonFiniteGravity() {
        assertRejected(definition().toBuilder().clearGravity().addGravity(1, 2).build(), "3 components");
        assertRejected(definition().toBuilder().clearGravity().addGravity(0, 0, Float.NaN).build(), "must be finite");
    }

    @ParameterizedTest
    @MethodSource("invalidConstraints")
    void rejectsInvalidConstraints(PhysicsConstraintProto constraint, String reason) {
        assertRejected(
            PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC, ANCHOR, PROXY).addConstraints(constraint).build(),
            reason);
    }

    static Stream<Arguments> invalidConstraints() {
        return Stream.of(Arguments.of(FIXED.withId(""), "nonempty"),
            Arguments.of(FIXED.withKindValue(92), "unsupported constraint kind 92"),
            Arguments.of(FIXED.withNodeB("anchor"), "itself"),
            Arguments.of(FIXED.withNodeB("missing"), "unknown node 'missing'"),
            Arguments.of(FIXED.withNodeB("proxy"), "at least one dynamic endpoint"),
            Arguments.of(FIXED.withLinearX(SOFT), "no configurable axes"),
            Arguments.of(HINGE.withAngularX(SOFT), "only angular Z"),
            Arguments.of(POINT.withLinearX(SOFT), "locks all linear axes"),
            Arguments.of(POINT.withAngularY(SOFT), "combine a cone"),
            Arguments.of(FIXED.withFrameA(IDENTITY.toBuilder().clearAxisX().addAxisX(0, 0, 0).build()),
                "orthonormal right-handed"),
            Arguments.of(FIXED.withFrameA(IDENTITY.toBuilder().clearAxisY().addAxisY(1, 0, 0).build()),
                "orthonormal right-handed"),
            Arguments.of(FIXED.withFrameB(IDENTITY.toBuilder().clearAxisZ().addAxisZ(0, 0, -1).build()),
                "orthonormal right-handed"),
            Arguments.of(
                FIXED.withFrameA(IDENTITY.toBuilder().clearPivotOffset().addPivotOffset(1, 2, Float.NaN).build()),
                "pivot offset must be finite"),
            Arguments.of(POINT.withCone(POINT.coneUnsafe().withHalfAngle(-0.1F)), "half-angle"),
            Arguments.of(POINT.withCone(POINT.coneUnsafe().withHalfAngle((float)Math.PI + 0.1F)), "half-angle"),
            Arguments.of(POINT.withCone(POINT.coneUnsafe().withHalfAngle(Float.NaN)), "half-angle"),
            Arguments.of(HINGE.withAngularZ(HINGE.angularZUnsafe().withMin(1)), "min <= max"),
            Arguments.of(HINGE.withAngularZ(HINGE.angularZUnsafe().withMax(Float.POSITIVE_INFINITY)), "finite limit"),
            Arguments.of(HINGE.withAngularZ(HINGE.angularZUnsafe().withMax(7)), "within [-pi, pi]"),
            Arguments.of(joint("twist", "anchor", PhysicsConstraintKind.POINT).withAngularX(SOFT.withMin(-7)),
                "within [-pi, pi]"));
    }

    @ParameterizedTest
    @MethodSource("invalidSprings")
    void rejectsInvalidSpringAndLimitCombinations(AxisConfigProto axis, String reason) {
        assertRejected(PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC, ANCHOR)
            .addConstraints(HINGE.withAngularZ(axis)).build(), reason);
    }

    static Stream<Arguments> invalidSprings() {
        var frequency = FREQUENCY.frequencyDamping();
        var stiffness = STIFFNESS.stiffnessDamping();
        var free = AxisConfigProto.newBuilder().setLimit(LimitKind.FREE).build();
        var locked = free.withLimit(LimitKind.LOCKED);
        return Stream.of(Arguments.of(SOFT.withLimitValue(45), "unsupported limit 45"),
            Arguments.of(SOFT.toBuilder().clearMin().build(), "both limit bounds"),
            Arguments.of(locked.withSpring(REST), "locked axis cannot enable a spring"),
            Arguments.of(locked.withSoftLimitSpring(FREQUENCY), "require the soft limit kind"),
            Arguments.of(free.withSoftLimitSpring(FREQUENCY), "require the soft limit kind"),
            Arguments.of(free.withMin(0), "bounds without a limit"),
            Arguments.of(SOFT.withSoftLimitSpring(null), "needs spring parameters"),
            Arguments.of(SOFT.withSpring(REST.withParameters(null)), "needs spring parameters"),
            Arguments.of(SOFT.withSpring(REST.withTargetValue(85)), "unsupported spring target 85"),
            Arguments.of(withFrequency(frequency.withFrequency(0)), "frequency"),
            Arguments.of(withFrequency(frequency.withFrequency(Float.NaN)), "frequency"),
            Arguments.of(withFrequency(frequency.withDampingRatio(-1)), "damping ratio"),
            Arguments.of(withFrequency(frequency.withDampingRatio(Float.POSITIVE_INFINITY)), "damping ratio"),
            Arguments.of(withStiffness(stiffness.withStiffness(-1)), "stiffness"),
            Arguments.of(withStiffness(stiffness.withDamping(-1)), "damping"),
            Arguments.of(withStiffness(stiffness.withDamping(Float.NEGATIVE_INFINITY)), "damping"), Arguments.of(
                SOFT.withSoftLimitSpring(SpringParametersProto.newBuilder().build()), "no supported parameter mode"));
    }

    private static AxisConfigProto withFrequency(FrequencyDampingProto frequency) {
        return SOFT.withSpring(REST.withParameters(FREQUENCY.withFrequencyDamping(frequency)));
    }

    @Test
    void enforcesAllStructuralLimitsAtTheBoundary() throws Exception {
        var nodes = PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC).addConstraints(FIXED);
        for (int i = 0; i < 127; i++) {
            nodes.addNodes(i == 0 ? ANCHOR : ANCHOR.withId("anchor-" + i));
        }
        validate(nodes.build());
        assertRejected(nodes.addNodes(ANCHOR.withId("excess")).build(), "at most 128 nodes");

        var constraints = PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC, ANCHOR);
        for (int i = 0; i < 256; i++) {
            constraints.addConstraints(FIXED.withId("joint-" + i));
        }
        validate(constraints.build());
        assertRejected(constraints.addConstraints(FIXED).build(), "at most 256 constraints");

        var fullNode = DYNAMIC.toBuilder().clearShapes()
            .addShapes(Collections.nCopies(32, SPHERE).toArray(CollisionShapeProto[]::new)).build();
        validate(body(fullNode));
        assertRejected(body(fullNode.toBuilder().addShapes(SPHERE).build()), "at most 32 collision shapes");

        var geometry = geometry("body").toBuilder();
        var model = PhysicsDefinitionProto.newBuilder().addNodes(ANCHOR);
        var bone = geometry("source").bones().get(0);
        for (int i = 0; i < 16; i++) {
            var id = "strand-" + i;
            geometry.addBones(bone.withName(id));
            model.addNodes(fullNode.withId(id).withBone(id));
            model.addConstraints(FIXED.withId("joint-" + i).withNodeB(id));
        }
        PhysicsDefinitionValidator.validate(model.build(), geometry.build(), "player");
        geometry.addBones(bone.withName("excess"));
        model.addNodes(DYNAMIC.toBuilder().setId("excess").setBone("excess").clearShapes().addShapes(SPHERE).build())
            .addConstraints(FIXED.withNodeB("excess"));
        var failure = assertThrows(AssetLoadException.class,
            () -> PhysicsDefinitionValidator.validate(model.build(), geometry.build(), "player"));
        assertTrue(failure.getMessage().contains("at most 512 collision shapes"), failure::getMessage);
    }

    @ParameterizedTest
    @MethodSource("bodyBounds")
    void appliesBodySafetyBounds(BiFunction<PhysicsNodeProto, Float, PhysicsNodeProto> replace, float min, float max,
        String field) throws Exception {
        validate(body(replace.apply(DYNAMIC, min)));
        validate(body(replace.apply(DYNAMIC, max)));
        assertRejected(body(replace.apply(DYNAMIC, Math.nextDown(min))), field);
        assertRejected(body(replace.apply(DYNAMIC, Math.nextUp(max))), field);
    }

    static Stream<Arguments> bodyBounds() {
        return Stream.of(bound(PhysicsNodeProto::withMass, 1e-4f, 1e6f, "mass"),
            bound(PhysicsNodeProto::withFriction, 0, 1e4f, "friction"),
            bound(PhysicsNodeProto::withBounciness, 0, 1, "bounciness"),
            bound(PhysicsNodeProto::withLinearDamping, 0, 1e4f, "linear damping"),
            bound(PhysicsNodeProto::withAngularDamping, 0, 1e4f, "angular damping"),
            bound(PhysicsNodeProto::withGravityScale, -1e4f, 1e4f, "gravity scale"));
    }

    private static Arguments bound(BiFunction<PhysicsNodeProto, Float, PhysicsNodeProto> replace, float min, float max,
        String field) {
        return Arguments.of(replace, min, max, field);
    }

    @Test
    void appliesGeometryAndLocalVectorSafetyBounds() throws Exception {
        for (float radius : new float[] {1e-3f, 1e4f}) {
            validate(shape(sphere(0, radius)));
            validate(shape(capsule(0, 10000, radius)));
            validate(shape(box(radius)));
        }
        for (float radius : new float[] {Math.nextDown(1e-3f), Math.nextUp(1e4f)}) {
            assertRejected(shape(sphere(0, radius)), "radius");
            assertRejected(shape(capsule(0, 0, radius)), "radius");
            assertRejected(shape(box(radius)), "dimensions");
        }
        validate(shape(capsule(0, 0, 1)));
        validate(shape(sphere(-8, 1)));
        assertRejected(shape(capsule(-1, 10000, 1)), "endpoint distance");
        for (float sign : new float[] {-1, 1}) {
            validate(shape(sphere(sign * 10000, 1)));
            validate(definition().toBuilder().clearGravity().addGravity(sign * 10000, 0, 0).build());
            assertRejected(shape(sphere(sign * 10001, 1)), "center");
            assertRejected(definition().toBuilder().clearGravity().addGravity(sign * 10001, 0, 0).build(), "gravity");
            var boundaryFrame = IDENTITY.toBuilder().clearPivotOffset().addPivotOffset(sign * 10000, 0, 0).build();
            validate(constraint(FIXED.withFrameA(boundaryFrame)));
            var unsafeFrame = boundaryFrame.toBuilder().clearPivotOffset().addPivotOffset(sign * 10001, 0, 0).build();
            assertRejected(constraint(FIXED.withFrameA(unsafeFrame)), "pivot offset");
        }
        validate(constraint(GENERIC.withLinearX(hard(-10000, 10000))));
        assertRejected(constraint(GENERIC.withLinearX(hard(-10001, 10000))), "within [-10000, 10000]");
        assertRejected(constraint(GENERIC.withLinearX(hard(-10000, 10001))), "within [-10000, 10000]");
    }

    @Test
    void appliesSpringSafetyBoundsAndPermitsZeroAndTinyCoefficients() throws Exception {
        var frequency = FREQUENCY.frequencyDamping();
        var stiffness = STIFFNESS.stiffnessDamping();
        for (var axis : new AxisConfigProto[] {withFrequency(frequency.withFrequency(1e-3f).withDampingRatio(0)),
            withFrequency(frequency.withFrequency(1e4f).withDampingRatio(1e4f)),
            withStiffness(stiffness.withStiffness(0).withDamping(0)),
            withStiffness(stiffness.withStiffness(1e-9f).withDamping(1e-9f)),
            withStiffness(stiffness.withStiffness(1e9f).withDamping(1e9f))}) {
            validate(constraint(HINGE.withAngularZ(axis)));
        }
        assertRejected(constraint(HINGE.withAngularZ(withFrequency(frequency.withFrequency(Math.nextDown(1e-3f))))),
            "frequency");
        assertRejected(constraint(HINGE.withAngularZ(withFrequency(frequency.withFrequency(Math.nextUp(1e4f))))),
            "frequency");
        assertRejected(constraint(HINGE.withAngularZ(withFrequency(frequency.withDampingRatio(Math.nextUp(1e4f))))),
            "damping ratio");
        assertRejected(constraint(HINGE.withAngularZ(withStiffness(stiffness.withStiffness(Math.nextUp(1e9f))))),
            "stiffness");
        assertRejected(constraint(HINGE.withAngularZ(withStiffness(stiffness.withDamping(Math.nextUp(1e9f))))),
            "damping");
    }

    @Test
    void requiresHardSwingLimitsToIntersectTheDoublePrecisionDisk() throws Exception {
        var locked = AxisConfigProto.newBuilder().setLimit(LimitKind.LOCKED).build();
        var free = locked.withLimit(LimitKind.FREE);
        float pi = (float)Math.PI;
        float edge = (float)Math.toRadians(179);
        float diagonal = (float)Math.toRadians(135);
        for (var type : new PhysicsConstraintKind[] {PhysicsConstraintKind.POINT, PhysicsConstraintKind.GENERIC}) {
            var swing = joint("swing", "anchor", type);
            assertRejected(constraint(swing.withAngularY(hard(-Math.nextUp(pi), 0))), "within [-pi, pi]");
            assertRejected(constraint(swing.withAngularZ(hard(0, Math.nextUp(pi)))), "within [-pi, pi]");
            assertRejected(constraint(swing.withAngularY(locked).withAngularZ(hard(pi, pi))), "179 degree disk");
            assertRejected(constraint(swing.withAngularY(free).withAngularZ(hard(pi, pi))), "179 degree disk");
            assertRejected(constraint(swing.withAngularY(hard(diagonal, pi)).withAngularZ(hard(diagonal, pi))),
                "179 degree disk");
            validate(constraint(swing.withAngularY(locked).withAngularZ(hard(edge, pi))));
            validate(constraint(swing.withAngularY(locked).withAngularZ(SOFT.withMin(pi).withMax(pi))));
        }
    }

    @Test
    void traversesOnlyMechanicallyEffectiveEdgesToAnAnimationAnchor() throws Exception {
        var generic = joint("generic", "anchor", PhysicsConstraintKind.GENERIC);
        var zero = STIFFNESS.withStiffnessDamping(STIFFNESS.stiffnessDamping().withStiffness(0).withDamping(0));
        var soft = SOFT.withSoftLimitSpring(zero).withSpring(ANIMATION.withParameters(zero));
        float pi = (float)Math.PI;
        for (var empty : new PhysicsConstraintProto[] {generic, generic.withAngularX(hard(-pi, pi)),
            generic.withLinearX(soft), generic.withAngularX(AxisConfigProto.newBuilder().setLimit(LimitKind.FREE)
                .setSpring(ANIMATION.withParameters(zero)).build())}) {
            assertRejected(constraint(empty), "no constraint path");
        }
        validate(constraint(generic.withLinearX(hard(-pi, pi))));
        validate(constraint(generic.withLinearX(SOFT.withSpring(null))));
        validate(constraint(joint("point", "anchor", PhysicsConstraintKind.POINT)));
        validate(constraint(joint("hinge", "anchor", PhysicsConstraintKind.HINGE)));

        var second = DYNAMIC.withId("second").withBone("hair2");
        var bridge = generic.withNodeA("strand").withNodeB("second");
        var graph = constraint(FIXED).toBuilder().addNodes(second).build();
        assertRejected(graph.toBuilder().addConstraints(bridge).build(), "'second' has no constraint path");
        validate(graph.toBuilder().addConstraints(bridge.withAngularY(hard(0, 0))).build());
    }

    @Test
    void fullAngularSoftLimitsNeedAnotherSourceOfAnchoring() throws Exception {
        float pi = (float)Math.PI;
        var generic = joint("generic", "anchor", PhysicsConstraintKind.GENERIC);
        List<BiFunction<PhysicsConstraintProto, AxisConfigProto, PhysicsConstraintProto>> axes =
            List.of(PhysicsConstraintProto::withAngularX, PhysicsConstraintProto::withAngularY,
                PhysicsConstraintProto::withAngularZ);
        var stiffness = STIFFNESS.withStiffnessDamping(STIFFNESS.stiffnessDamping().withStiffness(8).withDamping(0));
        for (var spring : new SpringParametersProto[] {FREQUENCY, stiffness, STIFFNESS}) {
            var fullTurn = SOFT.withSpring(null).withMin(-pi).withMax(pi).withSoftLimitSpring(spring);
            for (var setAxis : axes) {
                var empty = setAxis.apply(generic, fullTurn);
                assertRejected(constraint(empty), "via effective mechanical constraints");
                validate(constraint(setAxis.apply(generic, fullTurn.withMin(-pi / 2).withMax(pi / 2))));
                validate(constraint(setAxis.apply(generic, fullTurn.withSpring(ANIMATION.withParameters(spring)))));
                validate(constraint(empty).toBuilder().addConstraints(FIXED).build());
            }
        }
    }

    @Test
    void keepsAnimationOnlyBindingsOutsideTheDynamicBoneLineage() throws Exception {
        var bones = geometry("", "hair", "child", "grandchild");
        var tree = bones.toBuilder().clearBones().addBones(bones.bones().get(0), bones.bones().get(1).withParent(""),
            bones.bones().get(2).withParent("hair"), bones.bones().get(3).withParent("child")).build();
        for (var binding : new PhysicsNodeProto[] {ANCHOR, PROXY}) {
            var joint = FIXED.withNodeA(binding.id());
            var group = PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC).addConstraints(joint).build();
            PhysicsDefinitionValidator.validate(group.toBuilder().addNodes(binding.withBone("")).build(), tree,
                "player");
            for (var bone : new String[] {"hair", "child", "grandchild"}) {
                var invalid = group.toBuilder().addNodes(binding.withBone(bone)).build();
                var failure = assertThrows(AssetLoadException.class,
                    () -> PhysicsDefinitionValidator.validate(invalid, tree, "player"));
                assertTrue(failure.getMessage().contains("pure animation bone"), failure::getMessage);
            }
        }
    }

    private static PhysicsDefinitionProto body(PhysicsNodeProto node) {
        return PhysicsDefinitionProto.newBuilder().addNodes(node, ANCHOR).addConstraints(FIXED).build();
    }

    private static PhysicsDefinitionProto shape(CollisionShapeProto shape) {
        return body(DYNAMIC.toBuilder().clearShapes().addShapes(shape).build());
    }

    private static CollisionShapeProto sphere(float x, float radius) {
        return SPHERE
            .withSphere(SPHERE.sphere().toBuilder().clearCenter().addCenter(x, 0, 0).setRadius(radius).build());
    }

    private static CollisionShapeProto capsule(float ax, float bx, float radius) {
        return CAPSULE.withCapsule(CAPSULE.capsule().toBuilder().clearPointA().addPointA(ax, 0, 0).clearPointB()
            .addPointB(bx, 0, 0).setRadius(radius).build());
    }

    private static CollisionShapeProto box(float x) {
        return BOX.withBox(BOX.box().toBuilder().clearSize().addSize(x, 1, 1).build());
    }

    private static AxisConfigProto hard(float min, float max) {
        return AxisConfigProto.newBuilder().setLimit(LimitKind.HARD).setMin(min).setMax(max).build();
    }

    private static PhysicsDefinitionProto constraint(PhysicsConstraintProto joint) {
        return PhysicsDefinitionProto.newBuilder().addNodes(DYNAMIC, ANCHOR).addConstraints(joint).build();
    }

    private static AxisConfigProto withStiffness(StiffnessDampingProto stiffness) {
        return SOFT.withSpring(ANIMATION.withParameters(STIFFNESS.withStiffnessDamping(stiffness)));
    }

    private static void validate(PhysicsDefinitionProto definition) throws AssetLoadException {
        PhysicsDefinitionValidator.validate(definition, geometry("body", "hair", "hair2", "hair3"), "player");
    }

    private static void assertRejected(PhysicsDefinitionProto definition, String reason) {
        var failure = assertThrows(AssetLoadException.class, () -> validate(definition));
        assertEquals(AssetLoadException.Reason.CONTENT, failure.reason());
        assertTrue(failure.getMessage().contains(reason), failure::getMessage);
    }
}
