package com.elfmcys.ysm.client.sound.instance;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import com.elfmcys.ysm.api.client.audio.AudioBackend;
import com.elfmcys.ysm.expression.host.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundInstanceManagerTest {
    @Test
    void localSoundEffectKeepsTheAnimationScopeSelectedWhenStaged() throws Throwable {
        verifyStagedSoundScope(false, false, false);
    }

    @Test
    void stopAllControllerAndExplicitGlobalEffectsKeepTheirStagedTargets() throws Throwable {
        for (var controllerScope : new boolean[] {false, true}) {
            for (var stopAll : new boolean[] {false, true}) {
                for (var global : new boolean[] {false, true}) {
                    verifyStagedSoundScope(controllerScope, stopAll, global);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyStagedSoundScope(boolean controllerScope, boolean stopAll, boolean global)
        throws Throwable {
        var firstAnimation = new AnimationExecutionState();
        var nextAnimation = new AnimationExecutionState();
        var firstController = new ControllerExecutionState(true);
        var nextController = new ControllerExecutionState(true);
        var firstSounds = controllerScope ? firstController.soundManager() : firstAnimation.soundManager();
        var nextSounds = controllerScope ? nextController.soundManager() : nextAnimation.soundManager();
        var globalSounds = new SoundInstanceManager();
        var firstPlayback = new FakePlayback();
        var nextPlayback = new FakePlayback();
        var globalPlayback = new FakePlayback();
        var id = ModelSoundId.string("voice");
        assertTrue(firstSounds.startResolved(id, false, () -> firstPlayback));
        assertTrue(nextSounds.startResolved(id, false, () -> nextPlayback));
        assertTrue(globalSounds.startResolved(id, false, () -> globalPlayback));

        var owner = TestComponentHolders.owner(null);
        TestComponentHolders.installProcessor(owner, net.minecraft.util.RandomSource.create(), globalSounds);
        var model = new com.elfmcys.ysm.expression.host.builtin.ModelBuiltins(owner);
        var inputs = new InvocationInputs<net.minecraft.world.entity.Entity>() {
            public net.minecraft.world.entity.Entity entity() { return null; }
        };
        var context =
            MolangContext.logical(inputs, inputs, MolangContext.LogicalPhase.ACTION, inputs::prevalidateEffect,
                com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.PRODUCER),
                new InvocationProvenance("sound-owner", "local-scope", 1, 2, 3), () -> false, () -> true, 8);
        EffectTransaction.execute(context, EffectSink.direct(), active -> {
            active.setControllerContext(firstController);
            active.setAnimationContext(controllerScope ? null : firstAnimation);
            active.bindExpression("local-scope", 1);
            active.beginTopLevel(true);
            try {
                if (stopAll)
                    model.stopAllSounds(active, 1, global);
                else
                    model.stopSound(active, 2, "voice", global);
            } finally {
                active.endTopLevel();
                active.releaseExpression();
            }
            active.setControllerContext(nextController);
            active.setAnimationContext(controllerScope ? null : nextAnimation);
            return null;
        });

        org.junit.jupiter.api.Assertions.assertAll(
            () -> assertEquals(!global, firstPlayback.stopped,
                "the staged local effect must stop its original sampling scope"),
            () -> assertFalse(nextPlayback.stopped, "later sampling must not retarget the staged effect"),
            () -> assertEquals(global, globalPlayback.stopped,
                "only an explicit global effect may stop the owner-global playback"));
    }

    @Test
    void committedNamedZeroStopsWithoutStoppingAnonymousZeroOrAnotherOwner() throws Throwable {
        var manager = new SoundInstanceManager();
        var otherOwner = new SoundInstanceManager();
        var anonymous = new FakePlayback();
        var named = new FakePlayback();
        var other = new FakePlayback();
        assertTrue(otherOwner.startResolved(ModelSoundId.string("0"), false, () -> other));
        var starts = new AtomicInteger();
        var fixture = new com.elfmcys.ysm.expression.host.ControlledHostFixture();
        var invocation =
            MolangContext.logical(new EntityView() {}, new RenderView() {}, MolangContext.LogicalPhase.ACTION, fixture,
                com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.PRODUCER),
                new InvocationProvenance("sound-owner", "zero", 1, 2, 3), () -> false, () -> true, 8);
        EffectTransaction.execute(invocation, EffectSink.direct(), context -> {
            context.bindExpression("zero", 1); context.beginTopLevel(true);
            try {
                context.submit((PreparedEffect)() -> manager.startResolved(ModelSoundId.numeric(0), false, () -> {
                    starts.incrementAndGet(); return anonymous;
                }));
                context.submit((PreparedEffect)() -> manager.startResolved(ModelSoundId.string("0"), false, () -> {
                    starts.incrementAndGet(); return named;
                }));
                context.submit((PreparedEffect)() -> manager.stopPlayingSound(ModelSoundId.string("0")));
            } finally { context.endTopLevel(); context.releaseExpression(); }
            assertEquals(0, starts.get());
            return null;
        });
        assertEquals(2, starts.get());
        assertTrue(named.stopped);
        assertFalse(anonymous.stopped);
        assertFalse(other.stopped);
        org.junit.jupiter.api.Assertions.assertThrows(ExecutionFailure.class,
            () -> EffectTransaction.finish(invocation, EffectSink.direct(), true));
        assertEquals(2, starts.get());
        manager.stopAllPlayingSounds();
        assertEquals(AudioBackend.CancelReason.SCOPE_RETIRED, anonymous.reason);
        otherOwner.stopAllPlayingSounds();
    }

    @Test
    void namedPlaybackRejectsDuplicateAndStopsTheExactAcceptedHandle() {
        var manager = new SoundInstanceManager();
        var id = ModelSoundId.string("voice");
        var first = new FakePlayback();
        var duplicateStarts = new AtomicInteger();

        assertTrue(manager.startResolved(id, false, () -> first));
        assertFalse(manager.startResolved(id, false, () -> {
            duplicateStarts.incrementAndGet();
            return new FakePlayback();
        }));
        assertEquals(0, duplicateStarts.get());
        assertTrue(manager.stopPlayingSound(id));
        assertEquals(AudioBackend.CancelReason.AUTHOR_STOP, first.reason);
        assertFalse(manager.stopPlayingSound(id));
    }

    @Test
    void forceReplacementSealsTheOldHandleBeforeStartingTheNewOne() {
        var manager = new SoundInstanceManager();
        var id = ModelSoundId.numeric(7);
        var first = new FakePlayback();
        var observedOldStop = new AtomicBoolean();
        var second = new FakePlayback();

        assertTrue(manager.startResolved(id, false, () -> first));
        assertTrue(manager.startResolved(id, true, () -> {
            observedOldStop.set(first.stopped);
            return second;
        }));

        assertTrue(observedOldStop.get());
        assertEquals(AudioBackend.CancelReason.FORCE_REPLACED, first.reason);
        assertTrue(manager.stopPlayingSound(id));
        assertEquals(AudioBackend.CancelReason.AUTHOR_STOP, second.reason);
    }

    @Test
    void failedForcedStartDoesNotLeaveTheReplacedHandleAddressable() {
        var manager = new SoundInstanceManager();
        var id = ModelSoundId.string("voice");
        var first = new FakePlayback();
        assertTrue(manager.startResolved(id, false, () -> first));

        assertFalse(manager.startResolved(id, true, () -> null));
        assertEquals(AudioBackend.CancelReason.FORCE_REPLACED, first.reason);
        assertFalse(manager.stopPlayingSound(id));
    }

    @Test
    void synchronouslyRejectedCandidateIsNotRegistered() {
        var manager = new SoundInstanceManager();
        var id = ModelSoundId.string("voice");
        var rejected = new FakePlayback();
        rejected.setStopped(AudioBackend.CancelReason.BACKEND_FAILURE);

        assertFalse(manager.startResolved(id, false, () -> rejected));
        assertFalse(manager.stopPlayingSound(id));
    }

    @Test
    void anonymousPlaybacksRemainIndependentUntilScopeRetirement() {
        var manager = new SoundInstanceManager();
        var unnamed = ModelSoundId.numeric(0);
        var first = new FakePlayback();
        var second = new FakePlayback();

        assertTrue(manager.startResolved(unnamed, false, () -> first));
        assertTrue(manager.startResolved(unnamed, false, () -> second));
        assertFalse(manager.stopPlayingSound(unnamed));
        manager.stopAllPlayingSounds();

        assertEquals(AudioBackend.CancelReason.SCOPE_RETIRED, first.reason);
        assertEquals(AudioBackend.CancelReason.SCOPE_RETIRED, second.reason);
    }

    private static final class FakePlayback implements ICanStopSound {
        private boolean stopped;
        private AudioBackend.CancelReason reason;

        @Override
        public void setStopped() {
            setStopped(AudioBackend.CancelReason.AUTHOR_STOP);
        }

        @Override
        public void setStopped(AudioBackend.CancelReason reason) {
            stopped = true;
            this.reason = reason;
        }

        @Override
        public boolean isPlaybackStopped() {
            return stopped;
        }
    }
}
