package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.api.molang.MolangInputs;
import com.elfmcys.ysm.expression.host.builtin.EntityBuiltins;
import com.elfmcys.ysm.expression.host.builtin.LivingBuiltins;
import com.elfmcys.ysm.expression.host.builtin.RenderBuiltins;
import com.elfmcys.ysm.util.UnsafeUtil;
import dev.semaweave.SymbolKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RestoredEntityBuiltinsTest {
    @Test
    void declarationsPublishRestoredReads() throws ReflectiveOperationException {
        ComponentScopeTest.initializeMinecraftEntityClasses();
        var host = ExpressionHost.create(HostAvailability.none());
        for (String name : new String[] {"query:equipment_count", "query:player_level", "query:body_x_rotation",
            "query:delta_time", "query:walk_distance", "ysm:xxa", "ysm:zza", "ysm:attack_damage", "ysm:bone_pos",
            "ysm:bone_scale", "ysm:elytra_rot_x", "ysm:is_biting", "query:rotation_to_camera",
            "query:distance_from_camera", "query:is_first_person", "query:cape_flap_amount", "ysm:person_view",
            "ysm:rendering_in_paperdoll", "ysm:first_person_mod_hide", "ysm:ladder_facing", "ysm:hit_target_id",
            "ysm:hit_target_type", "ysm:left_shoulder_parrot_variant", "ysm:right_shoulder_parrot_variant",
            "ysm:has_left_shoulder_parrot", "ysm:has_right_shoulder_parrot"}) {
            assertTrue(host.registry().function(new SymbolKey(name)).isPresent(), name);
        }
    }

    @Test
    void invalidCameraAxisReturnsZeroWithoutCamera() {
        assertEquals(0f, RenderBuiltins.rotationToCamera(null, -1));
        assertEquals(0f, RenderBuiltins.rotationToCamera(null, 2));
    }

    @Test
    void elytraRotationOverflowReturnsZero() throws Exception {
        ComponentScopeTest.initializeMinecraftEntityClasses();
        var player = (net.minecraft.client.player.RemotePlayer)UnsafeUtil.getUnsafe()
            .allocateInstance(net.minecraft.client.player.RemotePlayer.class);
        player.elytraRotX = Float.MAX_VALUE;
        player.elytraRotY = -Float.MAX_VALUE;
        var view = new View<net.minecraft.client.player.AbstractClientPlayer>(player);
        assertEquals(0f, LivingBuiltins.elytraRotX(view));
        assertEquals(0f, LivingBuiltins.elytraRotY(view));
    }

    @Test
    void readsUseCurrentEntityAndInvocationValues() throws Exception {
        ComponentScopeTest.initializeMinecraftEntityClasses();
        var subject = (ArmorStand)UnsafeUtil.getUnsafe().allocateInstance(ArmorStand.class);
        subject.walkDist = 3.25f;
        subject.moveDist = 7.5f;
        subject.hurtTime = 6;
        subject.swingTime = 4;
        subject.swinging = true;
        var entity = new View<Entity>(subject);
        var living = new View<LivingEntity>(subject);
        assertEquals(3.25f, EntityBuiltins.queryModifiedDistanceMoved(entity));
        assertEquals(7.5f, EntityBuiltins.queryWalkDistance(entity));
        assertEquals(6f, EntityBuiltins.queryHurtTime(living));
        assertEquals(4f, EntityBuiltins.ysmSwingTime(living));
        assertTrue(EntityBuiltins.ysmSwinging(living));
        assertEquals(0.5f, EntityBuiltins.queryDeltaTime(entity));
        assertEquals(0.5f, EntityBuiltins.ysmTimeDelta(entity));
    }

    private record View<E extends Entity>(E subject) implements MolangContextView<E> {
        public com.elfmcys.ysm.api.molang.SubjectSampleView subjectSample() {
            var draft = new SubjectSample.Draft();
            draft.position(0, 0, 0, 0, 0, 0, 10, 0, 0, 0, 0);
            return draft.freeze();
        }

        @Override
        public MolangInputs inputs() {
            return new MolangInputs(0.25f, 10, null, null);
        }

        @Override
        public void defer(float delay, float... values) {
            throw new AssertionError();
        }
    }
}
