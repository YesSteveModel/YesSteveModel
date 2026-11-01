package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.expression.host.TestComponentHolders;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.network.protocol.PlayerStateReportPlanner;
import com.elfmcys.ysm.network.protocol.PlayerStateReportPolicy;
import com.elfmcys.ysm.network.protocol.PlayerStateValidator;
import com.elfmcys.ysm.proto.network.PlayerStateReportProto;
import com.elfmcys.ysm.proto.network.StateWriteMode;
import com.elfmcys.ysm.util.StringPool;
import dev.semaweave.RoamingRole;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RoamingStorageRestoreTest {
    @Test
    void reportingBeforeInstallationPreservesTheNbtBaseline() {
        restoreAndReport(false);
    }

    @Test
    void installationBeforeReportingPreservesTheNbtBaseline() {
        restoreAndReport(true);
    }

    private static void restoreAndReport(boolean installFirst) {
        var hashBytes = new byte[Hash256.SIZE];
        hashBytes[0] = 7;
        var model = new Hash256(hashBytes);
        var key = model.roamingHash();
        var expected = Map.of("a", 1F, "b", 1F);
        var saved = new ModelInfoCapability();
        saved.setModelAndTexture(model, "skin");
        saved.applyClientRoaming(key, new Object2FloatOpenHashMap<>(expected), true);
        var server = new ModelInfoCapability();
        server.deserializeNBT(saved.serializeNBT());

        var session = new ClientRoamingSession(null, true, () -> {});
        var planner = new PlayerStateReportPlanner();
        planner.beginSession(PlayerStateReportPolicy.gameServer(true));
        try (var runtime = new EntityExpressionRuntime();
            var module = TestExpressionModules.create(Set.of("a", "b"))) {
            session.resetFromServer(runtime, key, networkValues(server.getMolangVars().orElseThrow()));
            assertTrue(planner.acceptAuthority(model, key));
            if (installFirst)
                install(session, runtime, module, key);

            var snapshot = session.snapshot(runtime, key);
            assertTrue(snapshot.available());
            var reportCapture = new AtomicReference<PlayerStateReportProto>();
            Runnable observe = () -> reportCapture.set(planner.observe(1, 3,
                new PlayerStateReportPlanner.RoamingSample(key,
                    RoamingPayloads.fromNetwork(snapshot.values()).values())).orElseThrow());
            if (snapshot.installation() == null)
                observe.run();
            else
                assertTrue(snapshot.installation().commit(observe));
            var report = reportCapture.get();
            assertTrue(PlayerStateValidator.validReport(report));
            assertEquals(StateWriteMode.FULL, report.mode());
            assertTrue(report.hasRoaming());
            var reported = new Object2FloatOpenHashMap<String>();
            report.roamingUnsafe().variables().forEach(variable -> reported.put(variable.name(), variable.value_()));
            server.applyClientRoaming(report.roamingUnsafe().modelKey(), reported, true);
            session.resetFromServer(runtime, key, networkValues(server.getMolangVars().orElseThrow()));
            if (!installFirst)
                install(session, runtime, module, key);

            var persisted = new ModelInfoCapability();
            persisted.deserializeNBT(server.serializeNBT());
            assertAll(
                () -> assertEquals(expected, reported),
                () -> assertEquals(expected, runtime.snapshot().orElseThrow().payload().values()),
                () -> assertEquals(expected, persisted.getMolangVars().orElseThrow()));
        }
    }

    private static void install(ClientRoamingSession session, EntityExpressionRuntime runtime,
        RenderTargetExpressionModule module, int key) {
        try (var prepared = runtime.prepare(module, Map.of(), TestComponentHolders.linkageScope(), RoamingRole.PRODUCER)) {
            runtime.install(prepared, session.prepare(key, module, prepared));
        }
    }

    private static Int2FloatOpenHashMap networkValues(Map<String, Float> values) {
        var result = new Int2FloatOpenHashMap();
        values.forEach((name, value) -> result.put(StringPool.computeIfAbsent(name), value.floatValue()));
        return result;
    }
}
