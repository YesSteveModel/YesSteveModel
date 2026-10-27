package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.expression.host.TestComponentHolders;
import com.elfmcys.ysm.util.StringPool;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientRoamingSessionTest {
    @Test
    void absentFullClearsTheRemoteProjection() {
        int known = StringPool.computeIfAbsent("known");
        var runtime = new EntityExpressionRuntime();
        var session = new ClientRoamingSession(null, false, () -> {
        });
        try (var module = TestExpressionModules.create(Set.of("known"))) {
            prepare(session, runtime, module).run();
            session.resetFromServer(runtime, 7, values(known, 1.0f));
            assertEquals(1.0f, session.snapshot(runtime, 7).values().get(known));

            session.clearFromServer(runtime);

            assertTrue(session.snapshot(runtime, 7).values().isEmpty());
        }
    }

    @Test
    void exactLayoutFailureDoesNotModifyHostStorageBeforeRuntimeInstallation() {
        int known = StringPool.computeIfAbsent("known");
        int unknown = StringPool.computeIfAbsent("unknown");
        var runtime = new EntityExpressionRuntime();
        var session = new ClientRoamingSession(null, false, () -> {
        });
        try (var module = TestExpressionModules.create(Set.of("known"))) {
            prepare(session, runtime, module).run();
            session.resetFromServer(runtime, 7, values(known, 1.0f));

            var invalidFull = values(known, 2.0f);
            invalidFull.put(unknown, 3.0f);
            assertThrows(IllegalArgumentException.class, () -> session.resetFromServer(runtime, 7, invalidFull));
            var afterFull = session.snapshot(runtime, 7).values();
            assertEquals(1.0f, afterFull.get(known));
            assertFalse(afterFull.containsKey(unknown));

            var invalidPatch = values(known, 4.0f);
            invalidPatch.put(unknown, 5.0f);
            assertThrows(IllegalArgumentException.class, () -> session.updateRemote(runtime, 7, invalidPatch));
            var afterPatch = session.snapshot(runtime, 7).values();
            assertEquals(1.0f, afterPatch.get(known));
            assertFalse(afterPatch.containsKey(unknown));
        }
    }

    @Test
    void stagedPayloadIsValidatedBeforeItBecomesHostStorage() {
        int unknown = StringPool.computeIfAbsent("unknown");
        var runtime = new EntityExpressionRuntime();
        var session = new ClientRoamingSession(null, false, () -> {
        });
        session.resetFromServer(runtime, 7, values(unknown, 3.0f));
        assertFalse(session.snapshot(runtime, 7).values().containsKey(unknown));

        try (var module = TestExpressionModules.create(Set.of("known"))) {
            assertThrows(IllegalArgumentException.class, () -> prepare(session, runtime, module));
            assertFalse(session.snapshot(runtime, 7).values().containsKey(unknown));
        }
    }

    private static Runnable prepare(ClientRoamingSession session, EntityExpressionRuntime runtime,
        com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule module) {
        try (var prepared =
            runtime.prepare(module, java.util.Map.of(), TestComponentHolders.linkageScope(),
                dev.semaweave.RoamingRole.CONSUMER)) {
            return session.prepare(7, module, prepared);
        }
    }

    private static Int2FloatOpenHashMap values(int name, float value) {
        var result = new Int2FloatOpenHashMap();
        result.put(name, value);
        return result;
    }
}
