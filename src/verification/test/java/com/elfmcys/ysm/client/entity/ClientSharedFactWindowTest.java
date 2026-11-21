package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import com.elfmcys.ysm.network.protocol.ProtocolVersion;
import com.elfmcys.ysm.network.session.SessionMode;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientSharedFactWindowTest {
    @Test
    void sharedWritesFollowEveryOrdinaryAndGuiReaderAndAppearAtTheNextPublication() throws Exception {
        var entered = new CountDownLatch(2);
        var ordinaryRelease = new CountDownLatch(1);
        var guiRelease = new CountDownLatch(1);
        var ownerCallbacks = new ConcurrentLinkedQueue<Runnable>();
        var workers = Executors.newFixedThreadPool(3);
        var connection = new Connection(PacketFlow.CLIENTBOUND);
        var session = activeSession();
        replaceOwner(connection, session);
        try (var ordinaryRuntime = new EntityExpressionRuntime(); var guiRuntime = new EntityExpressionRuntime()) {
            var ordinary = new EntityExecutionLifecycle(ordinaryRuntime, () -> {
            }, failure -> fail(failure), EntityExecutionLifecycleTest.scheduler(workers));
            var gui = new EntityExecutionLifecycle(guiRuntime, () -> {
            }, failure -> fail(failure));
            ordinary.submit(() -> readAcrossPause(entered, ordinaryRelease));
            var guiTask = gui.reserveGuiTask(true, () -> readAcrossPause(entered, guiRelease));
            workers.execute(guiTask);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            // Resource completion can enqueue owner work without authorizing it to mutate this frame.
            workers.submit(() -> ownerCallbacks.add(() -> ClientSessionRuntime.failProtocolSession(connection))).get(5,
                TimeUnit.SECONDS);
            assertEquals(Optional.of(ClientModelSession.State.ACTIVE),
                workers.submit(ClientSessionRuntime::state).get(5, TimeUnit.SECONDS));
            ordinaryRelease.countDown();
            ordinary.awaitTask();
            assertTrue(gui.hasTask());
            assertEquals(ClientModelSession.State.ACTIVE, session.stateInReadWindow());
            assertEquals(1, ownerCallbacks.size());

            guiRelease.countDown();
            assertEquals(Optional.of(ClientModelSession.State.ACTIVE), guiTask.join());
            assertFalse(gui.hasTask());
            ownerCallbacks.remove().run();
            assertNextPublication(ordinary, gui, workers, ClientModelSession.State.INTRINSIC_DEFAULT_ONLY);

            var replacement = activeSession();
            replaceOwner(new Connection(PacketFlow.CLIENTBOUND), replacement);
            assertEquals(ClientModelSession.State.CLOSED, session.state());
            assertNextPublication(ordinary, gui, workers, ClientModelSession.State.ACTIVE);

            // Exercise the current/state retirement itself; host disconnect callbacks need a running client.
            replaceOwner(null, null);
            assertEquals(ClientModelSession.State.CLOSED, replacement.state());
            assertNextPublication(ordinary, gui, workers, null);
        } finally {
            ordinaryRelease.countDown();
            guiRelease.countDown();
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            replaceOwner(null, null);
        }
    }

    private static Optional<ClientModelSession.State> readAcrossPause(CountDownLatch entered, CountDownLatch release) {
        var before = ClientSessionRuntime.stateInReadWindow();
        assertEquals(Optional.of(ClientModelSession.State.ACTIVE), before);
        entered.countDown();
        try {
            assertTrue(release.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            throw new AssertionError(failure);
        }
        assertEquals(before, ClientSessionRuntime.stateInReadWindow());
        return before;
    }

    private static void assertNextPublication(EntityExecutionLifecycle ordinary, EntityExecutionLifecycle gui,
        java.util.concurrent.ExecutorService workers, ClientModelSession.State expected) {
        ordinary.submit(() -> {
            assertEquals(Optional.ofNullable(expected), ClientSessionRuntime.stateInReadWindow());
            return null;
        });
        var task = gui.reserveGuiTask(true, ClientSessionRuntime::stateInReadWindow);
        workers.execute(task);
        ordinary.awaitTask();
        assertEquals(Optional.ofNullable(expected), task.join());
    }

    private static ClientModelSession activeSession() {
        var session = new ClientModelSession(SessionMode.AUTO, true, CatalogSnapshot.empty());
        session.onServerHello(ProtocolVersion.CURRENT, false);
        session.publishFull(new RemotePublicationSnapshot(List.of(), Set.of(), List.of(), Map.of()));
        return session;
    }

    private static void replaceOwner(Connection connection, ClientModelSession session) throws Exception {
        synchronized (ClientSessionRuntime.class) {
            var field = currentField();
            var previous = field.get(null);
            field.set(null, null);
            if (previous instanceof AutoCloseable closeable)
                closeable.close();
            if (session != null) {
                var type = Class.forName(ClientSessionRuntime.class.getName() + "$ConnectionState");
                var constructor = type.getDeclaredConstructor(Connection.class, ClientModelSession.class,
                    Consumer.class, Object.class);
                constructor.setAccessible(true);
                field.set(null, constructor.newInstance(connection, session, (Consumer<String>)ignored -> {
                }, new Object()));
            }
        }
    }

    private static Field currentField() throws ReflectiveOperationException {
        var field = ClientSessionRuntime.class.getDeclaredField("current");
        field.setAccessible(true);
        return field;
    }
}
