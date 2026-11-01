package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import com.elfmcys.ysm.expression.host.builtin.ControlBuiltins;
import com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedDomainEffectTest {
    @Test
    void controllerEffectsKeepExactTargetsAndOrderUntilCommit() throws Throwable {
        var first = new Controller();
        var second = new Controller();
        var context = context();
        try {
            EffectTransaction.execute(context, EffectSink.direct(), active -> {
                active.setControllerContext(new ControllerExecutionState(false, first));
                active.bindExpression("first", 1); active.beginTopLevel(true);
                try {
                    ControlBuiltins.setBeginningTransitionLength(active, .25f);
                    ControlBuiltins.setAnimation(active, 2, "walk", 10);
                } finally { active.endTopLevel(); active.releaseExpression(); }
                active.setControllerContext(new ControllerExecutionState(false, second));
                active.bindExpression("second", 2); active.beginTopLevel(true);
                try {
                    ControlBuiltins.reset(active);
                    assertTrue(first.calls.isEmpty());
                    assertTrue(second.calls.isEmpty());
                } finally { active.endTopLevel(); active.releaseExpression(); }
                active.bindExpression("final-read", 2); active.beginTopLevel(false);
                try { assertNotNull(active.inputs().controller()); }
                finally { active.endTopLevel(); active.releaseExpression(); }
                return null;
            });
            assertEquals(List.of("transition:0.25", "animation:walk:LOOP"), first.calls);
            assertEquals(List.of("reset"), second.calls);
        } finally { context.closeRound(); }
    }

    @Test
    void finalValidationFailurePublishesNothingAndCommitFailureDropsTailReferences() {
        var context = context();
        var values = new ArrayList<Integer>();
        var reject = new IllegalStateException("final validation");
        var finalCheck = new PreparedEffect() {
            int validations;
            public void validate() { if (++validations == 2) throw reject; }
            public void commit() { values.add(2); }
        };
        context.stageOwner((PreparedEffect)() -> values.add(1));
        context.stageOwner(finalCheck);
        var batch = context.effects().orElseThrow();
        var rejectedContext = context;
        assertSame(reject, assertThrows(IllegalStateException.class,
            () -> EffectTransaction.finish(rejectedContext, EffectSink.direct(), true)));
        assertTrue(values.isEmpty()); assertEquals(EffectBatch.State.DISCARDED, batch.state());
        assertEquals(0, batch.size()); context.closeRound();

        context = context();
        var failure = new IllegalStateException("external commit");
        context.stageOwner((PreparedEffect)() -> { values.add(3); throw failure; });
        context.stageOwner((PreparedEffect)() -> values.add(4));
        batch = context.effects().orElseThrow();
        var failed = context;
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> EffectTransaction.finish(failed, EffectSink.direct(), true)));
        assertEquals(List.of(3), values);
        assertEquals(EffectBatch.State.COMMITTED, batch.state()); assertEquals(0, batch.size());
        context.closeRound();
    }

    @Test
    void boneEffectsClampInTheirDomainAndKeepThePreparedBone() throws Throwable {
        var owner = TestComponentHolders.owner(null);
        var first = (Bone)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(Bone.class);
        var second = (Bone)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(Bone.class);
        first.calls = new ArrayList<>(); second.calls = new ArrayList<>();
        var model = new com.elfmcys.ysm.expression.host.builtin.ModelBuiltins(owner);
        var context = context();
        try {
            EffectTransaction.execute(context, EffectSink.direct(), active -> {
                active.bindExpression("presentation", 0); active.beginTopLevel(true);
                try {
                    TestComponentHolders.setBone(owner, first);
                    model.boneColor(active, "root", -1, 127.6f, 999);
                    model.boneGlow(active, "root", 15.6f);
                    TestComponentHolders.setBone(owner, second);
                    model.boneTransparency(active, "root", 127.5f);
                    TestComponentHolders.setBone(owner, null);
                    model.boneColor(active, "missing", 1, 2, 3);
                    assertTrue(first.calls.isEmpty()); assertTrue(second.calls.isEmpty());
                } finally { active.endTopLevel(); active.releaseExpression(); }
                return null;
            });
            assertEquals(List.of("color:0:128:255", "glow:15"), first.calls);
            assertEquals(List.of("alpha:128"), second.calls);
        } finally { context.closeRound(); }
    }

    private static final class Bone extends com.elfmcys.ysm.geckolib3.model.AnimatedGeoBone {
        List<String> calls;
        private Bone() { super(null, new float[14], 0); }
        public void setColor(int red, int green, int blue) { calls.add("color:" + red + ":" + green + ":" + blue); }
        public void setGlow(int glow) { calls.add("glow:" + glow); }
        public void setTransparency(int alpha) { calls.add("alpha:" + alpha); }
    }

    private static MolangContext<Entity> context() {
        return MolangContext.logical(new EntityView() {}, new RenderView() {}, MolangContext.LogicalPhase.ACTION,
            (effect, context) -> effect.validate(),
            com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.PRODUCER),
            new InvocationProvenance("owner", "round", -1, 1, 1), () -> false, () -> true, 8);
    }

    private static final class Controller extends CodedAnimationController<AnimatableEntity<Entity>> {
        final List<String> calls = new ArrayList<>();
        Controller() { super(TestComponentHolders.owner(null), "test", 0, event -> PlayState.CONTINUE); }
        @Override public void setBeginningTransitionLength(float value) { calls.add("transition:" + value); }
        @Override public void setAnimation(String name, LoopType loop) { calls.add("animation:" + name + ":" + loop); }
        @Override public void reset() { calls.add("reset"); }
    }
}
