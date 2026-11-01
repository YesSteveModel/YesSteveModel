package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.expression.host.TestComponentHolders;
import com.elfmcys.ysm.util.StringPool;
import com.elfmcys.ysm.util.UnsafeUtil;
import dev.semaweave.RoamingPayload;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.player.LocalPlayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientRoamingSessionTest {
    @BeforeAll
    static void initializeMinecraftEntityClasses() throws ReflectiveOperationException {
        net.minecraft.SharedConstants.tryDetectVersion();
        var gate = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        gate.setAccessible(true);
        boolean previous = gate.getBoolean(null);
        gate.setBoolean(null, true);
        try {
            net.minecraft.core.registries.BuiltInRegistries.REGISTRY.size();
            Class.forName(LocalPlayer.class.getName(), true, ClientRoamingSessionTest.class.getClassLoader());
        } finally {
            gate.setBoolean(null, previous);
        }
    }

    @Test
    void absentFullClearsTheRemoteProjection() {
        int known = StringPool.computeIfAbsent("known");
        var runtime = new EntityExpressionRuntime();
        var session = new ClientRoamingSession(null, false, () -> {
        });
        try (runtime; var module = TestExpressionModules.create(Set.of("known"))) {
            installRemote(session, runtime, module);
            session.resetFromServer(runtime, 7, values(known, 1.0f));
            assertEquals(1.0f, session.snapshot(runtime, 7).values().get(known));

            session.clearFromServer(runtime);

            assertTrue(session.snapshot(runtime, 7).values().isEmpty());
            assertEquals(0F, runtime.roamingState().orElseThrow().read("known"));
        }
    }

    @Test
    void invalidRemotePayloadDoesNotModifyTheInstalledOrRetainedValues() {
        int known = StringPool.computeIfAbsent("known");
        int unknown = StringPool.computeIfAbsent("unknown");
        var runtime = new EntityExpressionRuntime();
        var session = new ClientRoamingSession(null, false, () -> {
        });
        try (runtime; var module = TestExpressionModules.create(Set.of("known"))) {
            installRemote(session, runtime, module);
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
            assertEquals(1F, runtime.roamingState().orElseThrow().read("known"));
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

        try (runtime; var module = TestExpressionModules.create(Set.of("known"))) {
            assertThrows(IllegalArgumentException.class, () -> installRemote(session, runtime, module));
            assertFalse(session.snapshot(runtime, 7).values().containsKey(unknown));
        }
    }

    @Test
    void samplingATemporaryRuntimeDoesNotClaimTheRestoreBaseline() throws Exception {
        int known = StringPool.computeIfAbsent("known");
        var reloads = new AtomicInteger();
        var session = localSession(reloads::incrementAndGet);
        try (var runtime = new EntityExpressionRuntime();
            var module = TestExpressionModules.create(Set.of("known"))) {
            install(session, runtime, module, 7);
            runtime.roamingState().orElseThrow().write("known", 9);
            var temporary = runtime.snapshot().orElseThrow().installation();
            assertFalse(session.snapshot(runtime, 7).available());
            session.prepareLocalProjection().adopt(new RoamingPayload(new TreeMap<>(Map.of("known", 9F))), false);

            session.resetFromServer(runtime, 7, values(known, 1F));

            assertEquals(1, reloads.get());
            assertFalse(temporary.commit(() -> {}));
            var restored = session.snapshot(runtime, 7);
            assertTrue(restored.available());
            assertEquals(1F, restored.values().get(known));
            assertNull(restored.installation());
            install(session, runtime, module, 7);
            assertEquals(1F, runtime.snapshot().orElseThrow().payload().values().get("known"));
        }
    }

    @Test
    void localAdoptionSurvivesSelfEchoAndModelUnload() throws Exception {
        int known = StringPool.computeIfAbsent("known");
        var session = localSession(() -> {});
        try (var runtime = new EntityExpressionRuntime();
            var module = TestExpressionModules.create(Set.of("known"))) {
            session.resetFromServer(runtime, 7, values(known, 1F));
            install(session, runtime, module, 7);
            runtime.roamingState().orElseThrow().write("known", 2);
            session.prepareLocalProjection().adopt(new RoamingPayload(new TreeMap<>(Map.of("known", 2F))), false);

            session.resetFromServer(runtime, 7, values(known, 1F));
            runtime.clear();
            session.modelReset();

            var retained = session.snapshot(runtime, 7);
            assertTrue(retained.available());
            assertEquals(2F, retained.values().get(known));
            assertNull(retained.installation());
            install(session, runtime, module, 7);
            assertEquals(2F, runtime.snapshot().orElseThrow().payload().values().get("known"));
        }
    }

    @Test
    void layoutAndPublicationFailuresPreserveTheLocalRestoreBaseline() throws Exception {
        int known = StringPool.computeIfAbsent("known");
        var session = localSession(() -> {});
        try (var runtime = new EntityExpressionRuntime();
            var incompatible = TestExpressionModules.create(Set.of("other"));
            var module = TestExpressionModules.create(Set.of("known"))) {
            session.resetFromServer(runtime, 7, values(known, 1F));
            assertThrows(IllegalArgumentException.class, () -> install(session, runtime, incompatible, 7));
            assertEquals(1F, session.snapshot(runtime, 7).values().get(known));

            try (var prepared = runtime.prepare(module, Map.of(), TestComponentHolders.linkageScope(),
                dev.semaweave.RoamingRole.PRODUCER)) {
                session.prepare(7, module, prepared);
                assertThrows(IllegalStateException.class, () -> runtime.install(prepared, () -> {
                    throw new IllegalStateException("host publication failed");
                }));
            }
            assertTrue(runtime.module().isEmpty());
            var retained = session.snapshot(runtime, 7);
            assertTrue(retained.available());
            assertEquals(1F, retained.values().get(known));
            assertNull(retained.installation());
            install(session, runtime, module, 7);
            assertEquals(1F, runtime.snapshot().orElseThrow().payload().values().get("known"));
        }
    }

    @Test
    void retainedNamespaceDoesNotBorrowAnUnrelatedInstallation() throws Exception {
        int known = StringPool.computeIfAbsent("known");
        var session = localSession(() -> {});
        try (var runtime = new EntityExpressionRuntime();
            var module = TestExpressionModules.create(Set.of("known"))) {
            session.resetFromServer(runtime, 7, values(known, 1F));
            session.resetFromServer(runtime, 8, values(known, 2F));
            install(session, runtime, module, 8);

            var retained = session.snapshot(runtime, 7);

            assertTrue(retained.available());
            assertEquals(1F, retained.values().get(known));
            assertNull(retained.installation());
            assertEquals(2F, runtime.snapshot().orElseThrow().payload().values().get("known"));
        }
    }

    @Test
    void replacedInstallationCannotAdoptAProjectionEvenWhenTheModuleIsReused() throws Exception {
        int known = StringPool.computeIfAbsent("known");
        var session = localSession(() -> {});
        try (var runtime = new EntityExpressionRuntime();
            var module = TestExpressionModules.create(Set.of("known"))) {
            session.resetFromServer(runtime, 7, values(known, 1F));
            install(session, runtime, module, 7);
            var old = runtime.snapshot().orElseThrow().installation();
            var projection = session.prepareLocalProjection();
            install(session, runtime, module, 7);

            assertFalse(old.commit(() -> {}));
            assertTrue(projection.adopt(new RoamingPayload(new TreeMap<>(Map.of("known", 9F))), true));
            runtime.clear();
            session.modelReset();

            assertEquals(1F, session.snapshot(runtime, 7).values().get(known));
        }
    }

    @Test
    void explicitClearIsKnownEmptyAndRevokesTheCurrentInstallation() throws Exception {
        int known = StringPool.computeIfAbsent("known");
        var session = localSession(() -> {});
        try (var runtime = new EntityExpressionRuntime();
            var module = TestExpressionModules.create(Set.of("known"))) {
            session.resetFromServer(runtime, 7, values(known, 1F));
            install(session, runtime, module, 7);
            var old = runtime.snapshot().orElseThrow().installation();

            session.clearFromServer(runtime, 7);

            var cleared = session.snapshot(runtime, 7);
            assertTrue(cleared.available());
            assertTrue(cleared.values().isEmpty());
            assertNull(cleared.installation());
            assertFalse(old.commit(() -> {}));
            install(session, runtime, module, 7);
            assertTrue(runtime.snapshot().orElseThrow().payload().values().isEmpty());
        }
    }

    @Test
    void clearingANewNamespaceRevokesThePreviousRuntimeAndItsPendingProjection() throws Exception {
        int known = StringPool.computeIfAbsent("known");
        var reloads = new AtomicInteger();
        var session = localSession(reloads::incrementAndGet);
        try (var runtime = new EntityExpressionRuntime();
            var module = TestExpressionModules.create(Set.of("known"))) {
            session.resetFromServer(runtime, 7, values(known, 1F));
            install(session, runtime, module, 7);
            var old = runtime.snapshot().orElseThrow().installation();
            var projection = session.prepareLocalProjection();

            session.clearFromServer(runtime, 8);

            assertFalse(old.commit(() -> {}));
            assertDoesNotThrow(() -> projection.adopt(new RoamingPayload(new TreeMap<>(Map.of("known", 9F))), true));
            var cleared = session.snapshot(runtime, 8);
            assertTrue(cleared.available());
            assertTrue(cleared.values().isEmpty());
            assertNull(cleared.installation());
            assertFalse(session.snapshot(runtime, 7).available());
            assertEquals(1, reloads.get());
        }
    }

    @Test
    void playerReplacementTransfersTheBaselineWithoutAnInstallation() throws Exception {
        int known = StringPool.computeIfAbsent("known");
        var source = localSession(() -> {});
        var target = localSession(() -> {});
        try (var runtime = new EntityExpressionRuntime();
            var module = TestExpressionModules.create(Set.of("known"))) {
            source.resetFromServer(runtime, 7, values(known, 1F));
            install(source, runtime, module, 7);
            var oldProjection = source.prepareLocalProjection();

            target.moveFrom(source);
            assertTrue(oldProjection.adopt(new RoamingPayload(new TreeMap<>(Map.of("known", 9F))), true));

            assertFalse(source.snapshot(runtime, 7).available());
            var transferred = target.snapshot(runtime, 7);
            assertTrue(transferred.available());
            assertEquals(1F, transferred.values().get(known));
            assertNull(transferred.installation());
            install(target, runtime, module, 7);
            assertEquals(1F, runtime.snapshot().orElseThrow().payload().values().get("known"));
            assertFalse(localSession(() -> {}).snapshot(runtime, 7).available());
        }
    }

    @Test
    void localOnlyInstallationEstablishesAnEmptyBaselineAndRetainsLocalChanges() throws Exception {
        int known = StringPool.computeIfAbsent("known");
        var session = localSession(() -> {});
        try (var runtime = new EntityExpressionRuntime();
            var module = TestExpressionModules.create(Set.of("known"))) {
            assertFalse(session.snapshot(runtime, 7).available());
            try (var prepared = runtime.prepare(module, Map.of(), TestComponentHolders.linkageScope(),
                dev.semaweave.RoamingRole.PRODUCER)) {
                session.prepare(7, module, prepared, true);
                assertThrows(IllegalStateException.class, () -> runtime.install(prepared, () -> {
                    throw new IllegalStateException("host publication failed");
                }));
            }
            assertFalse(session.snapshot(runtime, 7).available());

            install(session, runtime, module, 7, true);

            var initial = session.snapshot(runtime, 7);
            assertTrue(initial.available());
            assertTrue(initial.values().isEmpty());
            assertTrue(initial.installation().commit(() -> {}));
            runtime.roamingState().orElseThrow().write("known", 3);
            session.prepareLocalProjection().adopt(new RoamingPayload(new TreeMap<>(Map.of("known", 3F))), false);
            runtime.clear();
            session.modelReset();
            assertEquals(3F, session.snapshot(runtime, 7).values().get(known));
            install(session, runtime, module, 7, true);
            assertEquals(3F, runtime.snapshot().orElseThrow().payload().values().get("known"));
        }
    }

    private static ClientRoamingSession localSession(Runnable reload) throws InstantiationException {
        var player = (LocalPlayer)UnsafeUtil.getUnsafe().allocateInstance(LocalPlayer.class);
        return new ClientRoamingSession(player, true, reload);
    }

    private static void install(ClientRoamingSession session, EntityExpressionRuntime runtime,
        com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule module, int key) {
        install(session, runtime, module, key, false);
    }

    private static void install(ClientRoamingSession session, EntityExpressionRuntime runtime,
        com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule module, int key, boolean localOnly) {
        try (var prepared = runtime.prepare(module, Map.of(), TestComponentHolders.linkageScope(),
            dev.semaweave.RoamingRole.PRODUCER)) {
            runtime.install(prepared, session.prepare(key, module, prepared, localOnly));
        }
    }

    private static void installRemote(ClientRoamingSession session, EntityExpressionRuntime runtime,
        com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule module) {
        try (var prepared = runtime.prepare(module, Map.of(), TestComponentHolders.linkageScope(),
            dev.semaweave.RoamingRole.CONSUMER)) {
            runtime.install(prepared, session.prepare(7, module, prepared));
        }
    }

    private static Int2FloatOpenHashMap values(int name, float value) {
        var result = new Int2FloatOpenHashMap();
        result.put(name, value);
        return result;
    }
}
