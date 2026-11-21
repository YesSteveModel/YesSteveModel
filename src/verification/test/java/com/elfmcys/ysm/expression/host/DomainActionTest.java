package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.expression.host.builtin.ControllerActionBuiltins;
import com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DomainActionTest {
    @Test
    void invalidAndUnknownParticlesDoNotReachTheClientParticleOwner() throws ReflectiveOperationException {
        ComponentScopeTest.initializeMinecraftEntityClasses();
        for (String id : List.of("", "missing:unknown", "minecraft:dust invalid")) {
            assertDoesNotThrow(() -> com.elfmcys.ysm.client.particle.ParticleSpawner.spawnParticle(null, id, null, null,
                0, 0, 0, false, null), id);
        }
    }

    @Test
    void controllerActionsUseOnlyTheBorrowedEventWindowAndApplyInOrder() {
        var target = new Controller();
        var control = new ControllerActionBuiltins(TestComponentHolders.owner(null));
        var event = new AnimationEvent<AnimatableEntity<Entity>>(null, 0, 0, 0, null, null, null);
        var context = context();
        context.setControllerContext(new ControllerExecutionState(false, target));
        context.bindExpression("actions", 0);
        context.beginTopLevel(true);
        control.beginEval(event);
        try {
            control.reset(context);
            assertTrue(target.calls.isEmpty());
            event.setCodedAnimationController(target);
            control.setAnimation(context, 2, "", 10);
            control.setBeginningTransitionLength(context, -1);
            assertTrue(target.calls.isEmpty());
            control.reset(context);
            control.setAnimation(context, 2, "walk", 10);
            control.indicateReload(context);
            control.setBeginningTransitionLength(context, .25f);
            assertEquals(List.of("reset", "animation:walk:LOOP", "reload", "transition:0.25"), target.calls);
            event.setCodedAnimationController(null);
            control.setAnimation(context, 2, "later", 10);
            assertEquals(4, target.calls.size());
        } finally {
            control.endEval();
            context.endTopLevel();
            context.releaseExpression();
            context.closeRound();
        }
    }

    @Test
    void boneChangesAreImmediatelyVisibleAndUnknownBonesAreNoOps() throws Throwable {
        var owner = TestComponentHolders.owner(null);
        var first = (Bone)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(Bone.class);
        first.calls = new ArrayList<>();
        var model = new com.elfmcys.ysm.expression.host.builtin.EffectBuiltins(owner);
        var context = context();
        context.bindExpression("presentation", 0);
        context.beginTopLevel(true);
        try {
            TestComponentHolders.setBone(owner, first);
            model.boneColor(context, "root", -1, 127.6f, 999);
            assertEquals(List.of("color:0:128:255"), first.calls);
            model.boneGlow(context, "root", 15.6f);
            model.boneTransparency(context, "root", 127.5f);
            TestComponentHolders.setBone(owner, null);
            model.boneColor(context, "missing", 1, 2, 3);
            assertEquals(List.of("color:0:128:255", "glow:15", "alpha:128"), first.calls);
        } finally {
            context.endTopLevel();
            context.releaseExpression();
            context.closeRound();
        }
    }

    private static final class Bone extends com.elfmcys.ysm.geckolib3.model.AnimatedGeoBone {
        List<String> calls;

        private Bone() {
            super(null, new float[14], 0);
        }

        public void setColor(int red, int green, int blue) {
            calls.add("color:" + red + ":" + green + ":" + blue);
        }

        public void setGlow(int glow) {
            calls.add("glow:" + glow);
        }

        public void setTransparency(int alpha) {
            calls.add("alpha:" + alpha);
        }
    }

    private static MolangContext<Entity> context() {
        return MolangContext.logical(new EntityView() {}, new RenderView() {}, MolangContext.LogicalPhase.ACTION,
            com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.PRODUCER),
            new InvocationProvenance("owner", "round", -1, 1, 1));
    }

    private static final class Controller extends CodedAnimationController<AnimatableEntity<Entity>> {
        final List<String> calls = new ArrayList<>();

        Controller() {
            super(TestComponentHolders.owner(null), "test", 0, event -> PlayState.CONTINUE);
        }

        @Override
        public void setBeginningTransitionLength(float value) {
            calls.add("transition:" + value);
        }

        @Override
        public void setAnimation(String name, LoopType loop) {
            calls.add("animation:" + name + ":" + loop);
        }

        @Override
        public void reset() {
            calls.add("reset");
        }

        @Override
        public void indicateReload() {
            calls.add("reload");
        }
    }
}
