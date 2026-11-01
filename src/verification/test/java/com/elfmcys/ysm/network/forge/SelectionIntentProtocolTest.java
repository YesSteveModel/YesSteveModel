package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.session.SessionMode;
import com.elfmcys.ysm.proto.network.*;
import com.elfmcys.ysm.util.UnsafeUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class SelectionIntentProtocolTest {
    @Test
    void uint64RoundTripsAndEveryServerDispositionEchoesExactBits() throws Exception {
        var method = SessionProtocolHandler.class.getDeclaredMethod("selectionResult", long.class,
            ServerModelSession.SelectionResult.class);
        method.setAccessible(true);
        for (long id : new long[] {1L, Long.MIN_VALUE, -1L}) {
            var request = SelectModelRequestProto.newBuilder().setIntrinsicDefault(true).setIntentId(id).build();
            try (var bytes = ProtocolBuffer.serialize(request)) {
                assertEquals(id, ProtocolBuffer.parse(bytes, SelectModelRequestProto::parseFrom).intentId());
                assertEquals(0x20, Byte.toUnsignedInt(bytes.array()[bytes.arrayOffset()]), "ID 9 field 4 tag");
            }
            for (var status : ServerModelSession.SelectionResult.values()) {
                var result = (SelectModelResultProto)method.invoke(null, id, status);
                try (var bytes = ProtocolBuffer.serialize(result)) {
                    var decoded = ProtocolBuffer.parse(bytes, SelectModelResultProto::parseFrom);
                    assertEquals(id, decoded.intentId());
                    assertEquals(status.name(), decoded.status().name());
                }
            }
        }
    }

    @Test
    void zeroRequestReturnsBeforeAnyAuthorityOrNetworkEffect() throws Exception {
        var method = SessionProtocolHandler.class.getDeclaredMethod("select",
            net.minecraft.server.level.ServerPlayer.class, com.elfmcys.ysm.model.service.ServerModelService.class,
            ServerModelSession.class, SelectModelRequestProto.class);
        method.setAccessible(true);
        assertDoesNotThrow(() -> method.invoke(null, null, null, null,
            SelectModelRequestProto.newBuilder().setIntrinsicDefault(true).setIntentId(0).build()));
    }

    @Test
    void productionResultBoundaryRequiresCurrentConnectionAndIdAndNeverTreatsAcceptanceAsInstallation()
        throws Exception {
        var connection = new Connection(PacketFlow.CLIENTBOUND);
        var other = new Connection(PacketFlow.CLIENTBOUND);
        var session = new ClientModelSession(SessionMode.AUTO, true, CatalogSnapshot.empty());
        var stateType = Class.forName(ClientSessionRuntime.class.getName() + "$ConnectionState");
        var constructor = stateType.getDeclaredConstructor(Connection.class, ClientModelSession.class,
            java.util.function.Consumer.class, Object.class);
        constructor.setAccessible(true);
        var state = constructor.newInstance(connection, session, (java.util.function.Consumer<String>)ignored -> {
        }, new Object());
        var current = ClientSessionRuntime.class.getDeclaredField("current");
        current.setAccessible(true);
        var priorState = current.get(null);
        current.set(null, state);
        var instance = ClientModelService.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        var priorService = instance.get(null);
        var service = (ClientModelService)UnsafeUtil.getUnsafe().allocateInstance(ClientModelService.class);
        var errors = new ArrayList<Throwable>();
        var coordinator = new ClientDemandCoordinator(() -> 0L, resources(), new ClientDemandCoordinator.Selections() {
            public ClientDemandCoordinator.SelectionContext context() {
                return new ClientDemandCoordinator.SelectionContext(connection, true, true);
            }

            public ClientDemandCoordinator.Target authority() {
                return new ClientDemandCoordinator.Target(null, "");
            }

            public boolean installed(ClientDemandCoordinator.Target target) {
                return false;
            }

            public void send(ClientDemandCoordinator.Target target, long id) {}

            public void reportFailure(Throwable failure) {
                errors.add(failure);
            }
        });
        var demand = ClientModelService.class.getDeclaredField("demands");
        demand.setAccessible(true);
        demand.set(service, coordinator);
        instance.set(null, service);
        try {
            coordinator.select(new ClientDemandCoordinator.Target(new Hash256(new byte[32]), "main"));
            SessionProtocolHandler.acceptSelectModelResult(other, result(1, SelectionStatus.INVALID_REQUEST));
            SessionProtocolHandler.acceptSelectModelResult(connection, result(0, SelectionStatus.INVALID_REQUEST));
            SessionProtocolHandler.acceptSelectModelResult(connection, result(2, SelectionStatus.INVALID_REQUEST));
            SessionProtocolHandler.acceptSelectModelResult(connection, result(1, SelectionStatus.ACCEPTED));
            try (var bytes = ArrayBuffer.move(new byte[] {8, 99, 16, 1})) {
                SessionProtocolHandler.acceptSelectModelResult(connection,
                    ProtocolBuffer.parse(bytes, SelectModelResultProto::parseFrom));
            }
            assertTrue(coordinator.feedback().waiting());
            assertTrue(errors.isEmpty());
            SessionProtocolHandler.acceptSelectModelResult(connection, result(1, SelectionStatus.UNAUTHORIZED));
            assertFalse(coordinator.feedback().waiting());
            assertEquals(1, errors.size());
            SessionProtocolHandler.acceptSelectModelResult(connection, result(1, SelectionStatus.NOT_FOUND));
            assertEquals(1, errors.size());
        } finally {
            coordinator.close();
            current.set(null, priorState);
            instance.set(null, priorService);
            ((AutoCloseable)state).close();
        }
    }

    private static SelectModelResultProto result(long id, SelectionStatus status) {
        return SelectModelResultProto.newBuilder().setIntentId(id).setStatus(status).build();
    }

    private static ClientDemandCoordinator.Resources resources() {
        return (ClientDemandCoordinator.Resources)java.lang.reflect.Proxy.newProxyInstance(
            SelectionIntentProtocolTest.class.getClassLoader(),
            new Class<?>[] {ClientDemandCoordinator.Resources.class}, (proxy, method, args) -> {
                throw new AssertionError("Result observation must not acquire resources: " + method);
            });
    }
}
