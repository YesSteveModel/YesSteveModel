package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.MolangContextView;
import dev.semaweave.RoamingRole;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MolangContextTest {
    private static final InvocationProvenance OWNER = new InvocationProvenance("target", "owner", -1, 7, 9);

    @Test
    void scopesShareOneObjectAndPermissionStaysFixedUntilEnd() {
        var context = context();
        MolangContextView<Entity> read = context;
        MolangContextView<Entity> action = context;
        assertSame(read, action);
        assertThrows(IllegalStateException.class, () -> action.defer(0));
        context.bindExpression("action", 3);
        context.beginTopLevel(true);
        assertTrue(context.actionAllowed());
        assertTrue(context.actionAllowed());
        action.defer(0);
        context.endTopLevel();
        context.releaseExpression();
        assertFalse(context.actionAllowed());
        context.bindExpression("bone", 4);
        context.beginTopLevel(false);
        assertThrows(IllegalStateException.class, () -> action.defer(0));
        context.endTopLevel();
        context.releaseExpression();
    }

    @Test
    void hostWindowIsBoundBeforeActivationAndDoesNotLeakBetweenEntries() {
        var context = context();
        context.bindExpression("resample", 1, false);
        context.beginTopLevel(true);
        assertFalse(context.actionAllowed());
        assertThrows(IllegalStateException.class, () -> context.defer(0));
        context.endTopLevel();
        context.releaseExpression();
        context.bindExpression("explicit-execute", 2, true);
        context.beginTopLevel(true);
        assertTrue(context.actionAllowed());
        context.defer(0);
        context.endTopLevel();
        context.releaseExpression();
        context.bindExpression("watch", 3, true);
        context.beginTopLevel(false);
        assertFalse(context.actionAllowed());
        context.endTopLevel();
        context.releaseExpression();
        context.closeRound();
    }

    @Test
    void localRoamingWritesDoNotAcquireActionPermission() {
        var context = context();
        context.bindExpression("context-entry-in-update", 1, true);
        context.beginTopLevel(false);
        context.roamingState().write((short)context.roamingState().fieldNames().indexOf("shared"), 7);
        assertEquals(7F, context.roamingState().read((short)context.roamingState().fieldNames().indexOf("shared")));
        assertFalse(context.actionAllowed());
        context.endTopLevel();
        context.releaseExpression();
        context.bindExpression("observation-host-window", 2, false);
        context.beginTopLevel(false);
        context.roamingState().write((short)context.roamingState().fieldNames().indexOf("shared"), 9);
        assertEquals(9F, context.roamingState().read((short)context.roamingState().fieldNames().indexOf("shared")));
        assertFalse(context.actionAllowed());
        context.endTopLevel();
        context.releaseExpression();
        context.closeRound();
    }

    @Test
    void escapedDeferredPayloadKeepsItsValuesAndSourceWithoutRetainingTheContext() throws Exception {
        var context = context();
        var escaped = new ArrayList<Object>();
        context.deferredSubmit((request, source) -> {
            escaped.add(request);
            escaped.add(source);
        });
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

        context.closeRound();
        var captured = (EventArguments)escaped.get(0);
        assertEquals(List.of(2f, 3f, 4f), captured);
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
        var context = context();
        var cause = new IllegalArgumentException("original");
        context.bindExpression("first", 3);
        context.beginTopLevel(true);
        context.reportFailure(context.provenance(), cause);
        context.endTopLevel();
        context.releaseExpression();
        var snapshot = context.firstFailure();
        assertEquals(new InvocationProvenance("target", "first", 3, 7, 9), snapshot.provenance());
        assertSame(cause, snapshot.cause());
        context.bindExpression("second", 4);
        context.beginTopLevel(false);
        context.endTopLevel();
        context.releaseExpression();
        assertSame(snapshot, context.firstFailure());
        for (var name : List.of("programLocation")) {
            var field = MolangContext.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(context));
        }
        context.closeRound();
        for (var name : List.of("subject", "entityView", "renderView", "roamingState", "controllerContext",
            "animationContext")) {
            var field = MolangContext.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(context), name);
        }
    }

    @Test
    void sampledAnimationSurvivesExpressionResetUntilTheSchedulerChangesIt() {
        var context = context();
        var first = new AnimationExecutionState();
        first.setAnimTime(2.5f);
        context.setAnimationContext(first);
        for (int program = 0; program < 2; program++) {
            context.bindExpression("bone", program);
            context.beginTopLevel(false);
            assertEquals(2.5f, context.inputs().animation().time());
            context.endTopLevel();
            context.releaseExpression();
        }
        var second = new AnimationExecutionState();
        second.setAnimTime(7);
        context.setAnimationContext(second);
        context.bindExpression("next-bone", 2);
        context.beginTopLevel(false);
        assertEquals(7, context.inputs().animation().time());
        context.endTopLevel();
        context.releaseExpression();
        context.closeRound();
    }

    private static MolangContext<Entity> context() {
        var context = MolangContext.logical(new EntityView() {}, new RenderView() {}, MolangContext.LogicalPhase.UPDATE,
            com.elfmcys.ysm.expression.TestRoamingStates.create(null, RoamingRole.PRODUCER,
                java.util.List.of("shared")),
            OWNER);
        context.deferredSubmit((values, source) -> {
        });
        return context;
    }
}
