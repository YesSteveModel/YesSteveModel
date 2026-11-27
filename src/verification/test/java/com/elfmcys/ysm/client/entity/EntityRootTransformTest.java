package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.animation.AnimationParallelTicker.PassengerTransform;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityRootTransformTest {
    @Test
    void standingFacesAwayFromTheInterpolatedYawAndRetainsTheDispatcherOffset() {
        var model = EntityRootTransform.modelPose(90, false, null, 0, false, 0, null);
        assertDirection(new Vector3f(1, 0, 0), model.rotation().transform(new Vector3f(0, 0, 1)));
        assertPosition(new Vec3(32, 7.885, -5), model.worldPosition(new Vec3(32, 8, -5), -0.125));
    }

    @Test
    void sleepingUsesTheBedDirectionAndRotatesModelLiftIntoWorldSpace() {
        var model = EntityRootTransform.modelPose(133, true, Direction.WEST, 1.5F, false, 0, null);
        assertDirection(new Vector3f(0, -1, 0), model.rotation().transform(new Vector3f(0, 0, 1)));
        assertPosition(new Vec3(28_000_001.49, 8, -5), model.worldPosition(new Vec3(28_000_000, 8, -5), 0));

        var drawn = new PoseStack();
        model.applyTo(drawn);
        assertDirection(new Vector3f(1.49F, 0, 0), drawn.last().pose().transformPosition(new Vector3f()));

        var noBed = EntityRootTransform.modelPose(90, true, null, 0, false, 0, null);
        assertPosition(new Vec3(0, 0, 0.01), noBed.worldPosition(Vec3.ZERO, 0));
    }

    @Test
    void dinnerboneUsesTheHeightTranslationAndFlipsTheModelLift() {
        var model = EntityRootTransform.modelPose(180, false, null, 0, true, 1.9F, null);
        assertDirection(new Vector3f(0, -1, 0), model.rotation().transform(new Vector3f(0, 1, 0)));
        assertPosition(new Vec3(0, 1.89, 0), model.worldPosition(Vec3.ZERO, 0));
    }

    @Test
    void passengerTiltIsInvertedAndComposedAfterBodyYaw() {
        float quarterTurn = (float)(Math.PI / 2);
        var passenger = new PassengerTransform(-1.25, quarterTurn, quarterTurn);
        var model = EntityRootTransform.modelPose(90, false, null, 0, false, 0, passenger);
        assertDirection(new Vector3f(0, 0, -1), model.rotation().transform(new Vector3f(0, 1, 0)));
        assertDirection(new Vector3f(0, 1, 0), model.rotation().transform(new Vector3f(0, 0, 1)));
        assertPosition(new Vec3(0, -1.375, -0.01), model.worldPosition(Vec3.ZERO, -0.125 + passenger.yOffset()));

        var drawn = new PoseStack();
        drawn.translate(0, -0.125, 0);
        drawn.translate(0, passenger.yOffset(), 0);
        model.applyTo(drawn);
        assertDirection(new Vector3f(0, -1.375F, -0.01F), drawn.last().pose().transformPosition(new Vector3f()));
    }

    private static void assertDirection(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-6F);
        assertEquals(expected.y, actual.y, 1e-6F);
        assertEquals(expected.z, actual.z, 1e-6F);
    }

    private static void assertPosition(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1e-6);
        assertEquals(expected.y, actual.y, 1e-6);
        assertEquals(expected.z, actual.z, 1e-6);
    }
}
