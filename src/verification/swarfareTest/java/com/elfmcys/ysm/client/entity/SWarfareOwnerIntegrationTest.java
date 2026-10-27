package com.elfmcys.ysm.client.entity;

import com.atsuishio.superbwarfare.api.event.RenderPlayerArmEvent;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.compat.swarfare.ReplacePlayerArmRender;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.event.api.SpecialPlayerRenderEvent;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.geckolib3.geo.CustomTranslucentRenderType;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraftforge.common.MinecraftForge;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.GeoBone;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "YSM_FIRST_PERSON_FIXTURES", matches = ".+")
class SWarfareOwnerIntegrationTest extends FirstPersonOwnerIntegrationTest {
    @Test
    void superbAdapterUsesUnifiedOutputAndCleansEachPlacement() throws Exception {
        try (var fixture = new Fixture("parts-a")) {
            var owner = install(fixture);
            for (var arm : HumanoidArm.values())
                for (boolean old : new boolean[] {false, true}) {
                    var stack = new PoseStack();
                    stack.translate(0.5, -0.2, 0.7);
                    stack.mulPose(com.mojang.math.Axis.XP.rotation(0.23f));
                    stack.mulPose(com.mojang.math.Axis.YP.rotation(0.37f));
                    var original = new Matrix4f(stack.last().pose());
                    var output = new Probe();
                    var event = superbEvent(arm, old, stack, output);
                    var bone = event.getBone();
                    float side = arm == HumanoidArm.LEFT ? -1 : 1;
                    var expected = new Matrix4f(original).translate(side / 16f + side * 0.275f, 2 / 16f + 0.0625f, 0);
                    if (old)
                        expected.translate((bone.getPivotX() - 1) / 16f, (bone.getPivotY() - 2) / 16f,
                            bone.getPivotZ() / 16f);
                    else
                        expected.translate(bone.getPivotX() / 16f, (bone.getPivotY() + 7) / 16f, bone.getPivotZ() / 16f)
                            .rotateY((float)Math.PI).rotateZ((float)Math.PI);
                    var referenceStack = new PoseStack();
                    referenceStack.last().pose().set(expected);
                    referenceStack.last().normal().set(new org.joml.Matrix3f(expected));
                    var reference = new Probe();
                    assertTrue(renderer.render(player, cap, arm, referenceStack, reference, 123, 0));
                    renderSuperb(event);
                    assertTrue(event.isCanceled());
                    assertVertices(reference, output);
                    assertMarkers(output, arm == HumanoidArm.LEFT ? new int[] {0, 1} : new int[] {2, 3});
                    assertTrue(original.equals(stack.last().pose(), 0));
                    assertTrue(stack.clear());
                }
            assertEquals(1, owner.updates, "Repeated placements observe one logical opportunity");
            for (var setting : List.of(ClientConfig.DISABLE_SELF_MODEL, ClientConfig.DISABLE_SELF_HANDS)) {
                setting.set(true);
                var output = new Probe();
                var event = superbEvent(HumanoidArm.LEFT, false, new PoseStack(), output);
                renderSuperb(event);
                assertFalse(event.isCanceled());
                assertTrue(output.vertices.isEmpty());
                setting.set(false);
                owner = install(fixture);
            }
            var output = new Probe();
            Consumer<SpecialPlayerRenderEvent> reject = hook -> hook.setCanceled(true);
            MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                SpecialPlayerRenderEvent.class, reject);
            try {
                var event = superbEvent(HumanoidArm.RIGHT, true, new PoseStack(), output);
                renderSuperb(event);
                assertFalse(event.isCanceled());
                assertTrue(output.vertices.isEmpty());
            } finally {
                MinecraftForge.EVENT_BUS.unregister(reject);
            }
            var pose = new PoseStack();
            var original = new Matrix4f(pose.last().pose());
            var failure = new IllegalStateException("T3 consumer propagation");
            output.failure = failure;
            var event = superbEvent(HumanoidArm.LEFT, false, pose, output);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> renderSuperb(event)));
            assertFalse(event.isCanceled());
            assertTrue(original.equals(pose.last().pose(), 0));
            assertTrue(pose.clear());
        }
    }

    private void renderSuperb(RenderPlayerArmEvent event) throws Exception {
        var read = ReplacePlayerArmRender.class.getDeclaredMethod("readHost", RenderPlayerArmEvent.class);
        read.setAccessible(true);
        var input = read.invoke(null, event);
        var render = ReplacePlayerArmRender.class.getDeclaredMethod("render", RenderPlayerArmEvent.class,
            input.getClass(), PlayerAnimatableCapability.class);
        render.setAccessible(true);
        try {
            render.invoke(null, event, input, cap);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException original)
                throw original;
            if (failure.getCause() instanceof Error original)
                throw original;
            throw failure;
        }
    }

    @Test
    void superbTextureHiddenReplacementAndControlledFailureRetainPerSideResults() throws Exception {
        try (var a = new Fixture("parts-a"); var b = new Fixture("parts-b");
            var missing = new Fixture("missing-leftarm")) {
            var owner = install(a);
            var override = new ResourceLocation("ysm", "sw_override");
            Consumer<SpecialPlayerRenderEvent> texture = hook -> hook.setTextureLocationOverride(override);
            MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                SpecialPlayerRenderEvent.class, texture);
            try {
                for (var arm : HumanoidArm.values()) {
                    var output = new Probe();
                    var event = superbEvent(arm, false, new PoseStack(), output);
                    renderSuperb(event);
                    assertTrue(event.isCanceled());
                    assertSame(CustomTranslucentRenderType.create(override), output.types.get(0));
                }
            } finally {
                MinecraftForge.EVENT_BUS.unregister(texture);
            }
            var output = new Probe();
            var next = superbEvent(HumanoidArm.LEFT, true, new PoseStack(), output);
            renderSuperb(next);
            assertTrue(next.isCanceled());
            assertSame(CustomTranslucentRenderType.create(owner.getTextureLocation()), output.types.get(0));
            Consumer<SpecialPlayerRenderEvent> replace = hook -> owner.getLoadedGeoModel().setModelInplace(b.model);
            MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                SpecialPlayerRenderEvent.class, replace);
            try {
                var rejected = superbEvent(HumanoidArm.LEFT, false, new PoseStack(), new Probe());
                renderSuperb(rejected);
                assertFalse(rejected.isCanceled());
            } finally {
                MinecraftForge.EVENT_BUS.unregister(replace);
            }
            owner.install(b.model);
            var attributes = owner.getLoadedGeoModel().getBoneAttributes();
            attributes[AnimatedGeoModel.BONE_ATTRIBUTE_COUNT + 12] = -1;
            output = new Probe();
            var stack = new PoseStack();
            var initial = new Matrix4f(stack.last().pose());
            var failed = superbEvent(HumanoidArm.LEFT, true, stack, output);
            renderSuperb(failed);
            assertFalse(failed.isCanceled());
            assertTrue(output.vertices.isEmpty());
            assertTrue(initial.equals(stack.last().pose(), 0));
            var otherSide = superbEvent(HumanoidArm.RIGHT, false, stack, output);
            renderSuperb(otherSide);
            assertTrue(otherSide.isCanceled());
            assertMarkers(output, 2, 3);
            for (var bone : owner.getLoadedGeoModel().getSortedBones())
                bone.setHidden(true, false);
            player.tickCount++;
            var empty = new Probe();
            var hidden = superbEvent(HumanoidArm.RIGHT, true, stack, empty);
            renderSuperb(hidden);
            assertTrue(hidden.isCanceled());
            assertTrue(empty.vertices.isEmpty());
            owner.install(missing.model);
            var absent = superbEvent(HumanoidArm.LEFT, false, stack, new Probe());
            renderSuperb(absent);
            assertFalse(absent.isCanceled());
            var right = superbEvent(HumanoidArm.RIGHT, true, stack, new Probe());
            renderSuperb(right);
            assertTrue(right.isCanceled());
            assertTrue(initial.equals(stack.last().pose(), 0));
            assertTrue(stack.clear());
        }
    }

    @Test
    void superbHostCancellationWindow() throws Exception {
        try (var fixture = new Fixture("parts-a")) {
            System.out.println("T3 runtime Superb Warfare artifact: "
                + RenderPlayerArmEvent.class.getProtectionDomain().getCodeSource().getLocation());
            install(fixture);
            var dispatcher = allocate(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class);
            var hostRenderer = allocate(net.minecraft.client.renderer.entity.player.PlayerRenderer.class);
            var hostModel = allocate(net.minecraft.client.model.PlayerModel.class);
            for (Class<?> type = hostModel.getClass(); type != Object.class; type = type.getSuperclass()) {
                for (var property : type.getDeclaredFields())
                    if (property.getType() == net.minecraft.client.model.geom.ModelPart.class) {
                        property.setAccessible(true);
                        property.set(hostModel,
                            new net.minecraft.client.model.geom.ModelPart(
                                List.of(new net.minecraft.client.model.geom.ModelPart.Cube(14, 14, 0, 0, 0, 1, 1, 1, 0,
                                    0, 0, false, 16, 16, java.util.EnumSet.allOf(net.minecraft.core.Direction.class))),
                                java.util.Map.of()));
                    }
            }
            field(net.minecraft.client.renderer.entity.LivingEntityRenderer.class, "model").set(hostRenderer,
                hostModel);
            field(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class, "playerRenderers").set(dispatcher,
                java.util.Map.of("default", hostRenderer));
            field(Minecraft.class, "entityRenderDispatcher").set(Minecraft.getInstance(), dispatcher);
            // The headless JVM lacks Forge's constructor transformer; supply only its listener-list seam.
            var lists = field(net.minecraftforge.eventbus.api.EventListenerHelper.class, "listeners").get(null);
            java.util.Map<Class<?>, net.minecraftforge.eventbus.ListenerList> cache = null;
            for (var f : lists.getClass().getDeclaredFields()) {
                if (java.util.Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    cache = (java.util.Map<Class<?>, net.minecraftforge.eventbus.ListenerList>)f.get(lists);
                }
            }
            assertNotNull(cache);
            var previous = cache.put(RenderPlayerArmEvent.class,
                new net.minecraftforge.eventbus.ListenerList((net.minecraftforge.eventbus.ListenerList)field(
                    net.minecraftforge.eventbus.api.EventListenerHelper.class, "EVENTS_LIST").get(null)));
            boolean[] cancel = {false};
            int[] observations = {0};
            HumanoidArm[] expectedArm = {null};
            boolean[] expectedOld = {false};
            Consumer<RenderPlayerArmEvent> observer = event -> {
                assertEquals(expectedArm[0], event.getArm());
                assertEquals(expectedOld[0], event.isUseOldHandRender());
                assertEquals(123, event.getPackedLightIn());
                observations[0]++;
                if (cancel[0]) {
                    try {
                        renderSuperb(event);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    assertTrue(event.isCanceled());
                }
            };
            MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                RenderPlayerArmEvent.class, observer);
            try {
                for (var arm : HumanoidArm.values())
                    for (boolean old : new boolean[] {false, true}) {
                        String name = arm == HumanoidArm.LEFT ? "Lefthand" : "Righthand";
                        var bone = new GeoBone(null, name, false, 0.0, false, false);
                        expectedArm[0] = arm;
                        expectedOld[0] = old;
                        var stack = new PoseStack();
                        var original = new Matrix4f(stack.last().pose());
                        var output = new Probe();
                        int seen = observations[0];
                        cancel[0] = false;
                        com.atsuishio.superbwarfare.client.AnimationHelper.renderArms(player,
                            net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, stack, name, bone,
                            output, RenderType.lines(), 123, old);
                        assertFalse(output.vertices.isEmpty());
                        int hostVertices = output.vertices.size();
                        assertEquals(seen + 1, observations[0]);
                        assertTrue(stack.clear());
                        assertTrue(original.equals(stack.last().pose(), 0));
                        cancel[0] = true;
                        var replacement = new Probe();
                        com.atsuishio.superbwarfare.client.AnimationHelper.renderArms(player,
                            net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, stack, name, bone,
                            replacement, RenderType.lines(), 123, old);
                        assertEquals(hostVertices, output.vertices.size());
                        assertMarkers(replacement, arm == HumanoidArm.LEFT ? new int[] {0, 1} : new int[] {2, 3});
                        System.out.println("T3 actual host path: " + arm + " old=" + old + " hostVertices="
                            + hostVertices + " replacementVertices=" + replacement.vertices.size());
                        assertEquals(seen + 2, observations[0]);
                        assertTrue(stack.clear());
                        assertTrue(original.equals(stack.last().pose(), 0));
                    }
            } finally {
                MinecraftForge.EVENT_BUS.unregister(observer);
                if (previous == null)
                    cache.remove(RenderPlayerArmEvent.class);
                else
                    cache.put(RenderPlayerArmEvent.class, previous);
            }
        }
    }

    private RenderPlayerArmEvent superbEvent(HumanoidArm arm, boolean old, PoseStack stack, Probe output)
        throws Exception {
        var event = allocate(RenderPlayerArmEvent.class);
        var bone = allocate(GeoBone.class);
        bone.setPivotX(3);
        bone.setPivotY(4);
        bone.setPivotZ(5);
        for (var property : RenderPlayerArmEvent.class.getDeclaredFields()) {
            property.setAccessible(true);
            var type = property.getType();
            if (type == LocalPlayer.class)
                property.set(event, player);
            else if (type == HumanoidArm.class)
                property.set(event, arm);
            else if (type == PoseStack.class)
                property.set(event, stack);
            else if (type == MultiBufferSource.class)
                property.set(event, output);
            else if (type == GeoBone.class)
                property.set(event, bone);
            else if (type == int.class)
                property.setInt(event, 123);
            else if (type == boolean.class)
                property.setBoolean(event, old);
        }
        return event;
    }

}
