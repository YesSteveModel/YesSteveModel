package com.elfmcys.ysm.expression.host;

import dev.semaweave.ExecutionLimits;
import dev.semaweave.RuntimeValue;
import dev.semaweave.RuntimeValues;
import dev.semaweave.CompiledRoamingState;
import dev.semaweave.RoamingPayload;
import dev.semaweave.RoamingRole;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EffectTransactionTest {
    @Test
    void logicalPhaseCommitsOneOrderedBatchAndObservationCannotStage() throws Throwable {
        var fixture = new ControlledHostFixture();
        var roaming = com.elfmcys.ysm.expression.TestRoamingStates.create(null, RoamingRole.PRODUCER);
        roaming.write(RoamingMember.EMOTION.memberName(), 1.0f);
        var context = logical(fixture, roaming, () -> false, () -> true);

        EffectTransaction.execute(context, fixture, invocation -> {
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_PLAY_SOUND,
                java.util.List.of(soundId("voice"), RuntimeValues.ofString("ysm:test")));
            invocation.stageOwner(new EffectCommand.Particle(false, "ysm:spark", new float[0]));
            invocation.stageOwner(
                new EffectCommand.Controller(HostSymbol.CTRL_SET_BEGINNING_TRANSITION_LENGTH, "", 0.25f, 0, 0));
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_DEFER,
                java.util.List.of(RuntimeValues.ofFloat(2.0f)));
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_SYNC, java.util.List.of(RuntimeValues.ofFloat(3)));
            invocation.authorizeRoamingWrite(invocation.roamingState());
            invocation.roamingState().write(RoamingMember.EMOTION.memberName(), 2.0f);
            return null;
        });

        assertEquals(1, fixture.prepareCalls());
        assertEquals(1, fixture.commitCalls());
        assertSame(PROVENANCE, fixture.committedProvenance());
        var committed = fixture.committedEffects();
        assertEquals(5, committed.size());
        assertInstanceOf(EffectCommand.Sound.class, committed.get(0));
        assertInstanceOf(EffectCommand.Particle.class, committed.get(1));
        assertInstanceOf(EffectCommand.Controller.class, committed.get(2));
        assertInstanceOf(EffectCommand.Deferred.class, committed.get(3));
        assertInstanceOf(EffectCommand.Sync.class, committed.get(4));
        assertEquals(2.0f, roaming.read(RoamingMember.EMOTION.memberName()));
        assertEquals(EffectBatch.State.COMMITTED, context.effects().orElseThrow().state());

        var observation = observation(fixture, roaming, () -> false, () -> true);
        var failure = assertThrows(ExecutionFailure.class, () -> TestHostInvocations.invoke(observation,
            HostSymbol.YSM_PLAY_SOUND, java.util.List.of(soundId("voice"), RuntimeValues.ofString("ysm:test"))));
        assertEquals(ExecutionFailure.Reason.EFFECT_NOT_AUTHORIZED, failure.reason());
        assertEquals(1, fixture.commitCalls());
    }

    @Test
    void everyPreCommitFailureDiscardsTheWholeBatch() {
        assertDiscarded(null, (context, cancelled, current) -> {
            TestHostInvocations.invoke(context, HostSymbol.YSM_SYNC, java.util.List.of(RuntimeValues.ofFloat(1)));
            TestHostInvocations.invoke(context, HostSymbol.YSM_PLAY_SOUND,
                java.util.List.of(soundId("voice"), RuntimeValues.ofString("ysm:test")));
            return null;
        });
        assertDiscarded(ExecutionFailure.Reason.PROVIDER_BUDGET, (context, cancelled, current) -> {
            TestHostInvocations.invoke(context, HostSymbol.YSM_SYNC, java.util.List.of(RuntimeValues.ofFloat(1)));
            context.configureLimits(new ExecutionLimits(1, 1, 1, 1, 1));
            context.bindExpression("program", 5);
            context.beginTopLevel(true);
            try {
                context.chargeProviderCall();
                context.chargeProviderCall();
            } finally {
                context.endTopLevel();
                context.releaseExpression();
            }
            return null;
        });
        assertDiscarded(ExecutionFailure.Reason.CANCELLATION, (context, cancelled, current) -> {
            TestHostInvocations.invoke(context, HostSymbol.YSM_SYNC, java.util.List.of(RuntimeValues.ofFloat(1)));
            cancelled.set(true);
            return null;
        });
        assertDiscarded(ExecutionFailure.Reason.STALE_GENERATION, (context, cancelled, current) -> {
            TestHostInvocations.invoke(context, HostSymbol.YSM_SYNC, java.util.List.of(RuntimeValues.ofFloat(1)));
            current.set(false);
            return null;
        });
    }

    @Test
    void roamingViewsRemainInvocationLocal() {
        var firstAccess = new ControlledHostFixture();
        var secondAccess = new ControlledHostFixture();
        var firstRoaming = com.elfmcys.ysm.expression.TestRoamingStates.create(null, RoamingRole.PRODUCER);
        var secondRoaming = com.elfmcys.ysm.expression.TestRoamingStates.create(null, RoamingRole.PRODUCER);
        firstRoaming.write(RoamingMember.EMOTION.memberName(), 3.0f);
        secondRoaming.write(RoamingMember.EMOTION.memberName(), 4.0f);
        var first = observation(firstAccess, firstRoaming, () -> false, () -> true);
        var second = observation(secondAccess, secondRoaming, () -> false, () -> true);

        assertEquals(3, first.roamingState().read(RoamingMember.EMOTION.memberName()));
        assertEquals(4, second.roamingState().read(RoamingMember.EMOTION.memberName()));
    }

    private static void assertDiscarded(ExecutionFailure.Reason expectedReason, FailingBody body) {
        var commits = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        var current = new AtomicBoolean(true);
        var access = new ControlledHostFixture();
        access.failEffect(HostSymbol.YSM_PLAY_SOUND, new IllegalStateException("prevalidation failure"));
        var roaming = com.elfmcys.ysm.expression.TestRoamingStates.create(null, RoamingRole.PRODUCER);
        var context = logical(access, roaming, cancelled::get, current::get);
        var failure = assertThrows(Throwable.class,
            () -> EffectTransaction.execute(context, (commands, provenance) -> commits::incrementAndGet, invocation -> {
                invocation.authorizeRoamingWrite(invocation.roamingState());
                invocation.roamingState().write(RoamingMember.EMOTION.memberName(), 2.0f);
                return body.run(invocation, cancelled, current);
            }));
        if (expectedReason != null) {
            var executionFailure = assertInstanceOf(ExecutionFailure.class, failure);
            assertEquals(expectedReason, executionFailure.reason());
            assertEquals(expectedReason == ExecutionFailure.Reason.PROVIDER_BUDGET
                ? new InvocationProvenance(PROVENANCE.targetId(), "program", 5, PROVENANCE.targetGeneration(),
                    PROVENANCE.entityGeneration())
                : PROVENANCE, executionFailure.provenance());
        }
        assertEquals(0, commits.get());
        assertEquals(EffectBatch.State.DISCARDED, context.effects().orElseThrow().state());
        assertEquals(2.0f, roaming.read(RoamingMember.EMOTION.memberName()));
        assertEquals(
            new RoamingPayload(new java.util.TreeMap<>(java.util.Map.of(RoamingMember.EMOTION.memberName(), 2.0f))),
            roaming.delta());
    }

    private static MolangContext<Entity> observation(EffectValidator host, CompiledRoamingState roaming,
        java.util.function.BooleanSupplier cancellation, java.util.function.BooleanSupplier current) {
        return MolangContext.observation(ENTITY_VIEW, RENDER_VIEW, host, roaming, PROVENANCE, cancellation, current);
    }

    private static RuntimeValue soundId(String value) {
        return RuntimeValues.ofString(value);
    }

    private static MolangContext<Entity> logical(EffectValidator host, CompiledRoamingState roaming,
        java.util.function.BooleanSupplier cancellation, java.util.function.BooleanSupplier current) {
        return MolangContext.logical(ENTITY_VIEW, RENDER_VIEW, MolangContext.LogicalPhase.UPDATE, host, roaming,
            PROVENANCE, cancellation, current, EffectBatch.DEFAULT_MAX_COMMANDS);
    }

    private static final InvocationProvenance PROVENANCE = new InvocationProvenance("target", "program", 7, 11, 13);
    private static final EntityView ENTITY_VIEW = new EntityView() {};
    private static final RenderView RENDER_VIEW = new RenderView() {};

    @FunctionalInterface
    private interface FailingBody {
        Object run(MolangContext<Entity> context, AtomicBoolean cancellation, AtomicBoolean current) throws Throwable;
    }

}
