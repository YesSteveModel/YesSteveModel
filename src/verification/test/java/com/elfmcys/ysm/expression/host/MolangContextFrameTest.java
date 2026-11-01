package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import dev.semaweave.ExecutionLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MolangContextFrameTest {
    private static final ExecutionLimits LIMITS = new ExecutionLimits(1, 1, 1, 1, 2);

    @Test
    void failureReportsContainOnlyActualNodeAndCurrentOwnerFacts() {
        var frame = frame(() -> false, () -> true);
        try (var logs = new com.elfmcys.ysm.testutil.LogCapture(com.elfmcys.ysm.YesSteveModel.LOGGER)) {
            frame.beginTopLevel(false);
            frame.reportFailure(3, "root/arg/0", "example:read", new IllegalStateException("original-cause"));
            var messages = logs.events().stream().map(event -> event.getMessage().getFormattedMessage()).toList();
            assertEquals(1, messages.size());
            assertEquals(org.apache.logging.log4j.Level.DEBUG, logs.events().get(0).getLevel());
            assertTrue(messages.get(0).contains("program=3 node=root/arg/0 callee=example:read"));
            assertTrue(messages.get(0).contains("owner=InvocationProvenance"));
            assertTrue(messages.get(0).contains("original-cause"));
        }
    }

    @Test
    void chargesEveryBudgetWithExactProvenance() {
        assertBudget(ExecutionFailure.Reason.LOOP_BUDGET, frame -> {
            frame.chargeLoopIteration();
            frame.chargeLoopIteration();
        });
        assertBudget(ExecutionFailure.Reason.PROVIDER_BUDGET, frame -> {
            frame.chargeProviderCall();
            frame.chargeProviderCall();
        });
        assertBudget(ExecutionFailure.Reason.CONTROL_TRANSFER_BUDGET, frame -> {
            frame.chargeControlTransfer();
            frame.chargeControlTransfer();
        });
        assertBudget(ExecutionFailure.Reason.LOOP_BUDGET, frame -> {
            frame.chargeValueWork(1);
            frame.chargeValueWork(1);
        });
    }

    @Test
    void recursionAndTopLevelScopesAreBalancedAndReusableWithoutGenericScratch() {
        var frame = frame(() -> false, () -> true);
        frame.beginTopLevel(false);
        assertTrue(frame.tryEnterUserFunction(3));
        assertFailure(ExecutionFailure.Reason.USER_FUNCTION_DEPTH, () -> frame.tryEnterUserFunction(3));
        frame.exitUserFunction(3);
        assertThrows(UnsupportedOperationException.class, () -> frame.acquireArgumentScratch(2));
        frame.endTopLevel();

        frame.beginTopLevel(false);
        assertTrue(frame.tryEnterUserFunction(8));
        frame.exitUserFunction(8);
        frame.chargeValueWork(1);
        frame.endTopLevel();
        frame.releaseExpression();
        assertThrows(IllegalStateException.class, () -> frame.beginTopLevel(false));
    }

    @Test
    void failedTopLevelClearsRecursionAndCancellationAndStaleAreStable() {
        var frame = frame(() -> false, () -> true);
        frame.beginTopLevel(false);
        frame.tryEnterUserFunction(1);
        var unbalanced = assertThrows(ExecutionFailure.class, frame::endTopLevel);
        assertEquals(ExecutionFailure.Reason.FRAME_STATE, unbalanced.reason());
        frame.beginTopLevel(false);
        frame.tryEnterUserFunction(2);
        frame.exitUserFunction(2);
        frame.endTopLevel();

        assertFailure(ExecutionFailure.Reason.CANCELLATION, () -> frame(() -> true, () -> true));
        assertFailure(ExecutionFailure.Reason.STALE_GENERATION, () -> frame(() -> false, () -> false));
    }

    @Test
    void topLevelResetAndEntityIsolationKeepInnerFunctionChargesAndRejectedReentry() {
        var first = frame(() -> false, () -> true);
        var other = frame(() -> false, () -> true);
        first.beginTopLevel(false);
        first.chargeProviderCall();
        assertThrows(IllegalStateException.class, () -> first.beginTopLevel(false));
        assertTrue(first.isActive());
        first.tryEnterUserFunction(1);
        assertFailure(ExecutionFailure.Reason.PROVIDER_BUDGET, first::chargeProviderCall);
        first.exitUserFunction(1);
        first.endTopLevel();
        assertEquals(PROVENANCE, first.provenance());
        first.beginTopLevel(false);
        first.chargeProviderCall();
        first.endTopLevel();
        other.beginTopLevel(false);
        other.chargeProviderCall();
        other.endTopLevel();
    }

    @Test
    void valueWorkOverflowRejectsBeforeAdvancingAndUnusedScratchStillRejects() {
        var frame = frame(() -> false, () -> true);
        frame.beginTopLevel(false);
        assertThrows(IllegalArgumentException.class, () -> frame.chargeValueWork(-1));
        assertFailure(ExecutionFailure.Reason.LOOP_BUDGET, () -> frame.chargeValueWork(Integer.MAX_VALUE));
        frame.chargeValueWork(1);
        assertThrows(UnsupportedOperationException.class, () -> frame.acquireArgumentScratch(0));
        frame.endTopLevel();
    }

    private static void assertBudget(ExecutionFailure.Reason reason,
        java.util.function.Consumer<MolangContext<?>> body) {
        var frame = frame(() -> false, () -> true);
        frame.beginTopLevel(false);
        var failure = assertThrows(ExecutionFailure.class, () -> body.accept(frame));
        assertEquals(reason, failure.reason());
        assertEquals(PROVENANCE, failure.provenance());
    }

    private static void assertFailure(ExecutionFailure.Reason reason, org.junit.jupiter.api.function.Executable body) {
        var failure = assertThrows(ExecutionFailure.class, body);
        assertEquals(reason, failure.reason());
        assertEquals(PROVENANCE, failure.provenance());
    }

    private static MolangContext<?> frame(java.util.function.BooleanSupplier cancelled,
        java.util.function.BooleanSupplier current) {
        var context = MolangContext.observation(ENTITY_VIEW, RENDER_VIEW, new EmptyAccess(),
            com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.CONSUMER), PROVENANCE,
            cancelled, current);
        context.configureLimits(LIMITS);
        context.bindExpression(PROVENANCE.programLocation(), PROVENANCE.programId());
        return context;
    }

    private static final InvocationProvenance PROVENANCE = new InvocationProvenance("target", "program", 5, 7, 9);
    private static final EntityView ENTITY_VIEW = new EntityView() {};
    private static final RenderView RENDER_VIEW = new RenderView() {};

    private static final class EmptyAccess implements EffectValidator {
        @Override
        public void prevalidateEffect(PreparedEffect command, MolangContext<?> scope) {}
    }
}
