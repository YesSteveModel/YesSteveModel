package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.MolangActionView;
import com.elfmcys.ysm.api.molang.MolangContextView;
import dev.semaweave.RoamingRole;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MolangContextTest {
    private static final InvocationProvenance OWNER = new InvocationProvenance("target", "owner", -1, 7, 9);

    @Test
    void scopesShareOneObjectAndPermissionStaysFixedUntilEnd() {
        var context = context(new AtomicBoolean(true));
        MolangContextView<Entity> read = context;
        MolangActionView<Entity> action = context;
        assertSame(read, action);
        assertThrows(IllegalStateException.class, action::resetController);
        context.bindExpression("action", 3);
        context.beginTopLevel(true);
        assertTrue(context.actionAllowed());
        assertThrows(IllegalStateException.class, () -> context.bindExpression("nested", 6));
        assertThrows(IllegalStateException.class, () -> context.beginTopLevel(false));
        assertTrue(context.actionAllowed());
        action.resetController();
        context.endTopLevel();
        context.releaseExpression();
        assertFalse(context.actionAllowed());
        context.bindExpression("bone", 4);
        context.beginTopLevel(false);
        assertThrows(IllegalStateException.class, action::resetController);
        context.endTopLevel();
        context.releaseExpression();
        assertEquals(1, context.effects().orElseThrow().size());
    }

    @Test
    void hostWindowIsBoundBeforeActivationAndDoesNotLeakBetweenEntries() {
        var context = context(new AtomicBoolean(true));
        context.bindExpression("resample", 1, false);
        context.beginTopLevel(true);
        assertFalse(context.actionAllowed());
        assertThrows(IllegalStateException.class, context::resetController);
        context.endTopLevel();
        context.releaseExpression();
        context.bindExpression("explicit-execute", 2, true);
        context.beginTopLevel(true);
        assertTrue(context.actionAllowed());
        context.resetController();
        context.endTopLevel();
        context.releaseExpression();
        context.bindExpression("watch", 3, true);
        context.beginTopLevel(false);
        assertFalse(context.actionAllowed());
        context.endTopLevel();
        context.releaseExpression();
        assertEquals(1, context.effects().orElseThrow().size());
        context.closeRound();
    }

    @Test
    void roamingStorageKeepsTheHostWindowInsteadOfTheCallableEffectPermission() {
        var context = context(new AtomicBoolean(true));
        context.bindExpression("context-entry-in-update", 1, true);
        context.beginTopLevel(false);
        context.authorizeRoamingWrite(context.roamingState());
        assertFalse(context.actionAllowed());
        context.endTopLevel();
        context.releaseExpression();
        context.bindExpression("observation-host-window", 2, false);
        context.beginTopLevel(false);
        assertThrows(ExecutionFailure.class, () -> context.authorizeRoamingWrite(context.roamingState()));
        context.endTopLevel();
        context.releaseExpression();
        context.closeRound();
    }

    @Test
    void escapedDeferredPayloadKeepsItsValuesAndSourceWithoutRetainingTheContext() throws Exception {
        var context = context(new AtomicBoolean(true));
        var values = new float[] {3, 4};
        context.bindExpression("A", 1);
        context.beginTopLevel(true);
        context.defer(2, values);
        context.endTopLevel();
        context.releaseExpression();
        values[0] = 99;
        context.bindExpression("B", 2);
        context.beginTopLevel(false);
        context.endTopLevel();
        context.releaseExpression();
        var escaped = new ArrayList<Object>();
        EffectTransaction.finish(context, (commands, source) -> () -> {
            escaped.addAll(commands);
            escaped.add(source);
        }, true);
        context.closeRound();
        var captured = (EffectCommand.Deferred)escaped.get(0);
        assertEquals(List.of(2f, 3f, 4f), captured.arguments());
        assertEquals(new InvocationProvenance("target", "A", 1, 7, 9), escaped.get(1));
        assertNoContextReference(escaped, new java.util.IdentityHashMap<>());
    }

    private static void assertNoContextReference(Object value, java.util.IdentityHashMap<Object, Boolean> seen)
        throws IllegalAccessException {
        if (value == null || seen.put(value, Boolean.TRUE) != null)
            return;
        assertFalse(value instanceof MolangContext, "escaped payload retained the mutable round");
        if (value instanceof Iterable<?> values) {
            for (var element : values)
                assertNoContextReference(element, seen);
        }
        var type = value.getClass();
        if (type.isArray() && !type.getComponentType().isPrimitive()) {
            for (var element : (Object[])value)
                assertNoContextReference(element, seen);
        }
        if (type.isEnum() || !type.getPackageName().startsWith("com.elfmcys.ysm.expression.host"))
            return;
        for (var field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())
                continue;
            field.setAccessible(true);
            assertNoContextReference(field.get(value), seen);
        }
    }

    @Test
    void resetPreservesCapturedFailureAndReleasesTemporaryState() throws Exception {
        var context = context(new AtomicBoolean(true));
        var cause = new IllegalArgumentException("original");
        context.bindExpression("first", 3);
        context.beginTopLevel(true);
        context.reportFailure(3, "root/arg/0", "weapon:query", cause);
        context.endTopLevel();
        context.releaseExpression();
        var snapshot = context.firstFailure();
        assertEquals(3, snapshot.programId());
        assertEquals(new InvocationProvenance("target", "first", 3, 7, 9), snapshot.provenance());
        assertSame(cause, snapshot.cause());
        assertEquals("root/arg/0", snapshot.path());
        context.bindExpression("second", 4);
        context.beginTopLevel(false);
        context.chargeProviderCall();
        context.endTopLevel();
        context.releaseExpression();
        assertSame(snapshot, context.firstFailure());
        for (var name : List.of("programLocation", "ownerThread")) {
            var field = MolangContext.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(context));
        }
        context.closeRound();
        for (var name : List.of("subject", "entityView", "renderView", "hostAccess", "roamingState", "cancellation",
            "currentGeneration", "controllerContext", "animationContext")) {
            var field = MolangContext.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(context), name);
        }
    }

    @Test
    void sealAfterReadonlyResetKeepsActionSourceAndOwnerQualification() {
        var context = context(new AtomicBoolean(true));
        context.bindExpression("action", 3);
        context.beginTopLevel(true);
        context.resetController();
        context.endTopLevel();
        context.releaseExpression();
        context.bindExpression("readonly", 4);
        context.beginTopLevel(false);
        context.endTopLevel();
        context.releaseExpression();
        context.stageOwner(new EffectCommand.Sync(EventArguments.sync(new float[] {7})));
        var seen = new ArrayList<InvocationProvenance>();
        var commands = new ArrayList<EffectCommand>();
        EffectTransaction.finish(context, (values, source) -> {
            seen.add(source);
            return () -> commands.addAll(values);
        }, true);
        assertEquals(List.of(new InvocationProvenance("target", "action", 3, 7, 9), OWNER), seen);
        assertEquals(2, commands.size());
        assertEquals(EffectBatch.State.COMMITTED, context.effects().orElseThrow().state());
    }

    @Test
    void revokedRoundCannotSealAndPublicViewsExposeOnlyBusinessMethods() throws Exception {
        var current = new AtomicBoolean(true);
        var context = context(current);
        context.stageOwner(new EffectCommand.Sync(EventArguments.sync(new float[] {7})));
        current.set(false);
        assertThrows(ExecutionFailure.class, () -> EffectTransaction.finish(context, (values, source) -> {
            throw new AssertionError("revoked effects");
        }, true));
        assertEquals(EffectBatch.State.DISCARDED, context.effects().orElseThrow().state());
        for (var type : List.of(MolangContextView.class, MolangActionView.class)) {
            assertFalse(AutoCloseable.class.isAssignableFrom(type));
            for (var method : type.getMethods()) {
                assertFalse(List.of("reset", "beginTopLevel", "endTopLevel", "execute", "evaluate", "effects", "phase",
                    "verifyEligible", "configureLimits").contains(method.getName()));
                assertFalse(method.toGenericString().contains("semaweave"));
            }
        }
        assertEquals(float[].class,
            MolangActionView.class.getMethod("defer", float.class, float[].class).getParameterTypes()[1]);
    }

    @Test
    void sampledAnimationSurvivesExpressionResetUntilTheSchedulerChangesIt() {
        var context = context(new AtomicBoolean(true));
        var first = new AnimationExecutionState();
        first.setAnimTime(2.5f);
        context.setAnimationContext(first);
        for (int program = 0; program < 2; program++) {
            context.bindExpression("bone", program);
            context.beginTopLevel(false);
            assertEquals(2.5f, context.animationTime());
            context.endTopLevel();
            context.releaseExpression();
        }
        var second = new AnimationExecutionState();
        second.setAnimTime(7);
        context.setAnimationContext(second);
        context.bindExpression("next-bone", 2);
        context.beginTopLevel(false);
        assertEquals(7, context.animationTime());
        context.endTopLevel();
        context.releaseExpression();
        context.closeRound();
    }

    @Test
    void laterSourceValidationFailureCannotCommitAnEarlierPreparedGroup() {
        var context = context(new AtomicBoolean(true));
        for (int program = 0; program < 2; program++) {
            context.bindExpression("action-" + program, program);
            context.beginTopLevel(true);
            context.resetController();
            context.endTopLevel();
            context.releaseExpression();
        }
        var prepared = new ArrayList<Integer>();
        var committed = new ArrayList<Integer>();
        var failure = new IllegalStateException("second source validation");
        assertSame(failure,
            assertThrows(IllegalStateException.class, () -> EffectTransaction.finish(context, (commands, source) -> {
                prepared.add(source.programId());
                if (source.programId() == 1)
                    throw failure;
                return () -> committed.add(source.programId());
            }, true)));
        assertEquals(List.of(0, 1), prepared);
        assertEquals(List.of(), committed);
        assertEquals(EffectBatch.State.DISCARDED, context.effects().orElseThrow().state());
        context.closeRound();
    }

    private static MolangContext<Entity> context(AtomicBoolean current) {
        return MolangContext.logical(new EntityView() {}, new RenderView() {}, MolangContext.LogicalPhase.UPDATE,
            (command, scope) -> {
            }, com.elfmcys.ysm.expression.TestRoamingStates.create(null, RoamingRole.PRODUCER), OWNER, () -> false,
            current::get, 8);
    }
}
