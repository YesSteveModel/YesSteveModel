package com.elfmcys.ysm.expression.oracle;

import com.elfmcys.ysm.expression.TestRoamingStates;
import com.elfmcys.ysm.expression.host.TestHostInvocations;
import com.elfmcys.ysm.expression.host.EffectBatch;
import com.elfmcys.ysm.expression.host.EffectCommand;
import com.elfmcys.ysm.expression.host.EntityView;
import com.elfmcys.ysm.expression.host.ExecutionFailure;
import com.elfmcys.ysm.expression.host.HostSymbol;
import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.expression.host.InvocationProvenance;
import com.elfmcys.ysm.expression.host.EffectTransaction;
import com.elfmcys.ysm.expression.host.RenderView;
import com.elfmcys.ysm.expression.host.ModelSoundId;
import dev.semaweave.RoamingRole;
import dev.semaweave.RuntimeValue;
import dev.semaweave.RuntimeValues;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent oracle for the logical-action terminal states and entity-local state isolation (Frozen O-06, O-07; A-06,
 * A-07).
 *
 * <p>
 * Expectations are authored here: commit order and payloads, at-most-once commit, whole-batch discard for every
 * pre-commit failure, retention of legal staging across a non-finite soft stop, and independent per-entity
 * random/physics state.
 */
final class BuiltinTransactionOracleTest {
    private static final EntityView ENTITY_VIEW = new EntityView() {};
    private static final RenderView RENDER_VIEW = new RenderView() {};
    private static final InvocationProvenance PROVENANCE =
        new InvocationProvenance("t01-oracle", "transaction", 1, 2, 3);

    @Test
    void successfulLogicalActionCommitsOneOrderedBatchWithAuthoredPayloads() throws Throwable {
        var recorder = new OracleRecorder();
        var context = context(recorder, () -> false, () -> true);
        EffectTransaction.execute(context, recorder, invocation -> {
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_PLAY_SOUND,
                List.of(sound("voice"), RuntimeValues.ofString("ysm:test")));
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_PARTICLE,
                List.of(RuntimeValues.ofString("ysm:spark")));
            TestHostInvocations.invoke(invocation, HostSymbol.CTRL_SET_ANIMATION,
                List.of(RuntimeValues.ofString("wave")));
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_DEFER, List.of(RuntimeValues.ofFloat(2)));
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_SYNC, List.of(RuntimeValues.ofFloat(3)));
            return null;
        });

        assertEquals(1, recorder.prepareCalls());
        assertEquals(1, recorder.commitCalls());
        assertEquals(5, recorder.stagedCommands().size());
        var committed = recorder.committedCommands();
        assertEquals(List.of("Sound", "Particle", "Controller", "Deferred", "Sync"),
            committed.stream().map(command -> command.getClass().getSimpleName()).toList());
        var sound = assertInstanceOf(EffectCommand.Sound.class, committed.get(0));
        assertEquals(new ModelSoundId.StringKey("voice"), sound.soundId());
        assertEquals(List.of(RuntimeValues.ofString("ysm:test"), RuntimeValues.ofFloat(0), RuntimeValues.ofFloat(1),
            RuntimeValues.ofFloat(1)), plainArguments(committed.get(0)));
        assertEquals(List.of(RuntimeValues.ofString("wave"), RuntimeValues.ofFloat(0)),
            plainArguments(committed.get(2)), "the declared loop default reaches the typed command");
        assertEquals(List.of(RuntimeValues.ofFloat(2)), arguments(committed.get(3)));
        assertEquals(List.of(RuntimeValues.ofFloat(3)), plainArguments(committed.get(4)));
        assertEquals(EffectBatch.State.COMMITTED, context.effects().orElseThrow().state());
    }

    @Test
    void everyPreCommitFailureDiscardsTheWholeBatch() {
        assertDiscarded(ExecutionFailure.Reason.CANCELLATION, (recorder, cancelled, current, invocation) -> {
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_SYNC, List.of(RuntimeValues.ofFloat(1)));
            cancelled.set(true);
        });
        assertDiscarded(ExecutionFailure.Reason.STALE_GENERATION, (recorder, cancelled, current, invocation) -> {
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_SYNC, List.of(RuntimeValues.ofFloat(1)));
            current.set(false);
        });
        assertDiscarded(null, (recorder, cancelled, current, invocation) -> {
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_SYNC, List.of(RuntimeValues.ofFloat(1)));
            throw new IllegalStateException("provider failure");
        });
    }

    @Test
    void effectPrevalidationFailureDiscardsEarlierStagedCommands() {
        var recorder = new OracleRecorder();
        recorder.failPrevalidation(EffectCommand.Sound.class, new IllegalStateException("prevalidation failure"));
        var context = context(recorder, () -> false, () -> true);
        var failure = assertThrows(Throwable.class, () -> EffectTransaction.execute(context, recorder, invocation -> {
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_SYNC, List.of(RuntimeValues.ofFloat(1)));
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_PLAY_SOUND,
                List.of(sound("voice"), RuntimeValues.ofString("ysm:test")));
            return null;
        }));
        assertInstanceOf(IllegalStateException.class, failure);
        assertEquals(0, recorder.commitCalls(),
            "an earlier legal command must not commit when a later one fails prevalidation");
        assertEquals(List.of(), recorder.committedCommands());
        assertEquals(EffectBatch.State.DISCARDED, context.effects().orElseThrow().state());
    }

    @Test
    void retentionAcrossASoftStopDoesNotRollBackButALaterFailureStillDiscards() throws Throwable {
        var retained = new OracleRecorder();
        var context = context(retained, () -> false, () -> true);
        EffectTransaction.execute(context, retained, invocation -> {
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_SYNC, List.of(RuntimeValues.ofFloat(1)));
            return null;
        });
        assertEquals(1, retained.commitCalls(), "legal staging retained across a soft stop still commits once");
        assertEquals(1, retained.committedCommands().size());

        assertDiscarded(ExecutionFailure.Reason.CANCELLATION, (recorder, cancelled, current, invocation) -> {
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_SYNC, List.of(RuntimeValues.ofFloat(1)));
            cancelled.set(true);
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_SYNC, List.of(RuntimeValues.ofFloat(2)));
        });
    }

    @Test
    void twoEntitiesKeepIndependentRandomPhysicsAndStagedState() {
        var aloneA = new ArrayList<Float>();
        var soloA = new OracleEntityState("entity-a", 11L);
        for (var index = 0; index < 5; index++) {
            aloneA.add(soloA.nextRandomFloat(0, 100));
        }
        var aloneB = new ArrayList<Float>();
        var soloB = new OracleEntityState("entity-b", 29L);
        for (var index = 0; index < 5; index++) {
            aloneB.add(soloB.nextRandomFloat(0, 100));
        }

        var entityA = new OracleEntityState("entity-a", 11L);
        var entityB = new OracleEntityState("entity-b", 29L);
        var interleavedA = new ArrayList<Float>();
        var interleavedB = new ArrayList<Float>();
        for (var index = 0; index < 5; index++) {
            interleavedA.add(entityA.nextRandomFloat(0, 100));
            interleavedB.add(entityB.nextRandomFloat(0, 100));
        }
        assertEquals(aloneA, interleavedA, "entity A must reproduce its un-interleaved sequence");
        assertEquals(aloneB, interleavedB, "entity B must reproduce its un-interleaved sequence");
        assertNotEquals(interleavedA, interleavedB, "distinct seeds must not share a sequence");

        var physicsA = new OracleEntityState("entity-a", 1L);
        var physicsB = new OracleEntityState("entity-b", 2L);
        var expectedA = new ArrayList<Float>();
        var state = 0.0f;
        for (var index = 0; index < 4; index++) {
            state = state + (index - state) * 0.25f;
            expectedA.add(state);
            assertEquals(state, physicsA.firstOrder("sway", index, 0.25f), 0.0001f);
            physicsB.firstOrder("sway", 10, 0.5f);
        }
        assertEquals(expectedA.get(expectedA.size() - 1), physicsA.physicsState("sway"), 0.0001f,
            "controlled physics input must reproduce its own expected series");
        assertEquals(9.375f, physicsB.physicsState("sway"), 0.0001f,
            "the interleaved entity keeps its own physics state");
        assertNotEquals(physicsA.physicsState("sway"), physicsB.physicsState("sway"));

        var recorderA = new OracleRecorder();
        var recorderB = new OracleRecorder();
        var contextA = context(recorderA, () -> false, () -> true);
        assertThrows(Throwable.class, () -> EffectTransaction.execute(contextA, recorderA, invocation -> {
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_SYNC, List.of(RuntimeValues.ofFloat(1)));
            throw new IllegalStateException("entity A failure");
        }));
        assertEquals(0, recorderA.commitCalls());
        assertEquals(0, recorderB.commitCalls());
        assertEquals(0, recorderB.stagedCommands().size(),
            "one entity's staged batch must not appear in another entity's recorder");
    }

    @Test
    void generationReplacementDoesNotReusePreviousEntityState() {
        var entity = new OracleEntityState("entity-a", 7L);
        var generation = entity.generation();
        entity.firstOrder("sway", 5, 1.0f);
        assertTrue(entity.physicsState("sway") != 0.0f);

        var replaced = entity.replaceGeneration();
        assertNotEquals(generation, replaced);
        assertTrue(entity.isCurrent(replaced));
        assertTrue(!entity.isCurrent(generation), "the previous generation must no longer be current");
        assertEquals(0.0f, entity.physicsState("sway"), "a replaced generation must not reuse old physics history");

        var other = new OracleEntityState("entity-b", 8L);
        other.firstOrder("sway", 5, 1.0f);
        entity.replaceGeneration();
        assertTrue(other.physicsState("sway") != 0.0f,
            "replacing one entity's generation must not touch another entity");
    }

    @Test
    void repeatedObservationAfterCommitAddsNoSecondAction() throws Throwable {
        var recorder = new OracleRecorder();
        var commit = MolangContext.logical(ENTITY_VIEW, RENDER_VIEW, MolangContext.LogicalPhase.UPDATE, recorder,
            roaming(), PROVENANCE, () -> false, () -> true, EffectBatch.DEFAULT_MAX_COMMANDS);
        EffectTransaction.execute(commit, recorder, invocation -> {
            TestHostInvocations.invoke(invocation, HostSymbol.YSM_PLAY_SOUND,
                List.of(sound("voice"), RuntimeValues.ofString("ysm:test")));
            return null;
        });
        assertEquals(1, recorder.commitCalls());
        assertEquals(1, recorder.committedCommands().size());

        var observation = MolangContext.observation(ENTITY_VIEW, RENDER_VIEW, recorder, roaming(), PROVENANCE,
            () -> false, () -> true);
        var failure = assertThrows(ExecutionFailure.class, () -> TestHostInvocations.invoke(observation,
            HostSymbol.YSM_PLAY_SOUND, List.of(sound("voice"), RuntimeValues.ofString("ysm:test"))));
        assertEquals(ExecutionFailure.Reason.EFFECT_NOT_AUTHORIZED, failure.reason());
        assertEquals(1, recorder.commitCalls(), "an observation pass must not add a second action commit");
        assertEquals(1, recorder.committedCommands().size());
    }

    @FunctionalInterface
    private interface FailingBody {
        void run(OracleRecorder recorder, AtomicBoolean cancelled, AtomicBoolean current,
            MolangContext<Entity> invocation) throws Throwable;
    }

    private static void assertDiscarded(ExecutionFailure.Reason expected, FailingBody body) {
        var recorder = new OracleRecorder();
        var cancelled = new AtomicBoolean();
        var current = new AtomicBoolean(true);
        var context = MolangContext.logical(ENTITY_VIEW, RENDER_VIEW, MolangContext.LogicalPhase.UPDATE, recorder,
            roaming(), PROVENANCE, cancelled::get, current::get, EffectBatch.DEFAULT_MAX_COMMANDS);
        var failure = assertThrows(Throwable.class, () -> EffectTransaction.execute(context, recorder, invocation -> {
            body.run(recorder, cancelled, current, invocation);
            return null;
        }));
        if (expected != null) {
            assertEquals(expected, assertInstanceOf(ExecutionFailure.class, failure).reason(), failure::toString);
        }
        assertEquals(0, recorder.commitCalls());
        assertEquals(List.of(), recorder.committedCommands());
        assertEquals(EffectBatch.State.DISCARDED, context.effects().orElseThrow().state());
    }

    private static MolangContext<Entity> context(OracleRecorder recorder,
        java.util.function.BooleanSupplier cancellation, java.util.function.BooleanSupplier current) {
        return MolangContext.logical(ENTITY_VIEW, RENDER_VIEW, MolangContext.LogicalPhase.UPDATE, recorder, roaming(),
            PROVENANCE, cancellation, current, EffectBatch.DEFAULT_MAX_COMMANDS);
    }

    private static dev.semaweave.CompiledRoamingState roaming() {
        return TestRoamingStates.create(null, RoamingRole.PRODUCER);
    }

    private static List<RuntimeValue> plainArguments(EffectCommand command) {
        var values = OracleCommandValues.arguments(command);
        return command instanceof EffectCommand.Sound sound && sound.soundId() != null
            ? values.subList(1, values.size()) : values;
    }

    private static List<RuntimeValue> arguments(EffectCommand command) {
        return OracleCommandValues.arguments(command);
    }

    private static RuntimeValue sound(String value) {
        return RuntimeValues.ofString(value);
    }
}
