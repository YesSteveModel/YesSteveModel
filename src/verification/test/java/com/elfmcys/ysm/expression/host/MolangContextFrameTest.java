package com.elfmcys.ysm.expression.host;

import dev.semaweave.ExecutionLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MolangContextFrameTest {
    private static final ExecutionLimits LIMITS = new ExecutionLimits(1);

    @Test
    void roundCloseReleasesCapturedSubjectInputs() {
        var context = frame();
        var draft = new SubjectSample.Draft();
        draft.position(17, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        context.captureSubjectInputs(draft.freeze());
        assertEquals(17, context.subjectSample().position(0));
        context.closeRound();
        org.junit.jupiter.api.Assertions.assertSame(SubjectSample.MISSING, context.subjectSample());
        org.junit.jupiter.api.Assertions.assertSame(PlayerQueryProjection.MISSING, context.playerProjection());
    }

    @Test
    void failureReportsContainTopLevelIdentityAndOriginalThrowable() {
        var frame = frame();
        try (var logs = new com.elfmcys.ysm.testutil.LogCapture(com.elfmcys.ysm.YesSteveModel.LOGGER)) {
            frame.beginTopLevel(false);
            frame.reportFailure(frame.provenance(), new IllegalStateException("original-cause"));
            var messages = logs.events().stream().map(event -> event.getMessage().getFormattedMessage()).toList();
            assertEquals(1, messages.size());
            assertEquals(org.apache.logging.log4j.Level.DEBUG, logs.events().get(0).getLevel());
            assertTrue(messages.get(0).contains("source=InvocationProvenance"));
            assertEquals("original-cause", logs.events().get(0).getThrown().getMessage());
        }
    }

    @Test
    void depthFailureCarriesExactProvenance() {
        assertDepthFailure(frame -> {
            frame.enterUserFunction();
            frame.enterUserFunction();
        });
    }

    @Test
    void recursionAndTopLevelScopesAreBalancedAndReusable() {
        var frame = frame();
        frame.beginTopLevel(false);
        frame.enterUserFunction();
        assertFailure(ExecutionFailure.Reason.USER_FUNCTION_DEPTH, () -> frame.enterUserFunction());
        frame.exitUserFunction();
        frame.endTopLevel();

        frame.beginTopLevel(false);
        frame.enterUserFunction();
        frame.exitUserFunction();
        frame.endTopLevel();
        frame.releaseExpression();
    }

    @Test
    void topLevelResetAndEntityIsolationKeepIndependentDepth() {
        var first = frame();
        var other = frame();
        first.beginTopLevel(false);
        first.enterUserFunction();
        assertFailure(ExecutionFailure.Reason.USER_FUNCTION_DEPTH, first::enterUserFunction);
        other.beginTopLevel(false);
        other.enterUserFunction();
        other.exitUserFunction();
        other.endTopLevel();
        first.endTopLevel();
        first.beginTopLevel(false);
        first.enterUserFunction();
        first.exitUserFunction();
        first.endTopLevel();
    }

    @Test
    void diceRetainLegacySampling() {
        var random = net.minecraft.util.RandomSource.create(41);
        var expected = net.minecraft.util.RandomSource.create(41);
        var owner = TestComponentHolders.<net.minecraft.world.entity.Entity>owner(null);
        TestComponentHolders.installProcessor(owner, random,
            new com.elfmcys.ysm.client.sound.instance.SoundInstanceManager());
        var dice = new com.elfmcys.ysm.expression.host.builtin.RandomBuiltins(owner);
        var frame = frame();
        frame.configureLimits(new ExecutionLimits(1));
        frame.beginTopLevel(false);
        assertEquals(2 + expected.nextFloat() * 3 + 2 + expected.nextFloat() * 3, dice.roll(frame, 2.9f, 5, 2),
            0.000001f);
        assertEquals(expected.nextFloat(), dice.dieRoll(frame, 1, 0, 1));
        assertEquals(16f, dice.dieRollInteger(frame, 4, 4, 5));
        frame.endTopLevel();

        frame.beginTopLevel(false);
        assertEquals(8f, dice.rolli(frame, 1.5f, 5, 4));
        assertEquals(0f, dice.dieRollInteger(frame, 1, 4, 4));
        assertEquals(0f, dice.dieRoll(frame, 1, -Float.MAX_VALUE, Float.MAX_VALUE));
        frame.endTopLevel();
        frame.releaseExpression();
        frame.closeRound();
    }

    private static void assertDepthFailure(java.util.function.Consumer<MolangContext<?>> body) {
        var frame = frame();
        frame.beginTopLevel(false);
        var failure = assertThrows(ExecutionFailure.class, () -> body.accept(frame));
        assertEquals(ExecutionFailure.Reason.USER_FUNCTION_DEPTH, failure.reason());
        assertEquals(PROVENANCE, failure.provenance());
        assertEquals(LIMITS.maxUserFunctionDepth(), failure.maximum().orElseThrow());
        assertEquals("EXECUTION", failure.phase());
        try (var logs = new com.elfmcys.ysm.testutil.LogCapture(com.elfmcys.ysm.YesSteveModel.LOGGER)) {
            frame.reportFailure(frame.provenance(), failure);
            assertEquals(1, logs.events().size());
            var event = logs.events().get(0);
            assertTrue(event.getMessage().getFormattedMessage().startsWith("Molang user-function depth exhausted:"));
            assertTrue(event.getMessage().getFormattedMessage()
                .contains("kind=USER_FUNCTION_DEPTH maximum=1 phase=EXECUTION"));
            assertEquals(failure, event.getThrown());
        }
    }

    private static void assertFailure(ExecutionFailure.Reason reason, org.junit.jupiter.api.function.Executable body) {
        var failure = assertThrows(ExecutionFailure.class, body);
        assertEquals(reason, failure.reason());
        assertEquals(PROVENANCE, failure.provenance());
    }

    private static MolangContext<net.minecraft.world.entity.Entity> frame() {
        var context = MolangContext.observation(ENTITY_VIEW, RENDER_VIEW,
            com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.CONSUMER), PROVENANCE);
        context.configureLimits(LIMITS);
        context.bindExpression(PROVENANCE.programLocation(), PROVENANCE.programId());
        return context;
    }

    private static final InvocationProvenance PROVENANCE = new InvocationProvenance("target", "program", 5, 7, 9);
    private static final EntityView ENTITY_VIEW = new EntityView() {};
    private static final RenderView RENDER_VIEW = new RenderView() {};

}
