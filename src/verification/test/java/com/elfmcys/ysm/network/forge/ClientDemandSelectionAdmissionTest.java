package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.network.protocol.ProtocolVersion;
import com.elfmcys.ysm.network.session.SessionMode;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.elfmcys.ysm.proto.network.SelectModelRequestProto;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.Bootstrap;
import net.minecraftforge.common.util.LazyOptional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ClientDemandSelectionAdmissionTest {
    private static byte[] standaloneForgeType(String name, byte[] code) {
        boolean provider = name.equals("com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider");
        if (!provider && !name.startsWith("net.minecraftforge.network.NetworkEvent"))
            return code;
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(code).accept(node, 0);
        if (provider) {
            // Forge normally supplies the capability token through its transformer.
            var initialization = node.methods.stream().filter(method -> method.name.equals("<clinit>"))
                .findFirst().orElseThrow();
            initialization.instructions.clear();
            initialization.tryCatchBlocks.clear();
            if (initialization.localVariables != null)
                initialization.localVariables.clear();
            initialization.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
        } else if (node.superName.equals("net/minecraftforge/eventbus/api/Event")
            || node.superName.startsWith("net/minecraftforge/network/NetworkEvent")) {
            // Forge normally adds the constructor used by its listener registry.
            if (node.methods.stream().anyMatch(method -> method.name.equals("<init>") && method.desc.equals("()V")))
                return code;
            var empty = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V",
                null, null);
            empty.instructions.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
            empty.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESPECIAL,
                node.superName, "<init>", "()V", false));
            empty.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
            node.methods.add(empty);
        } else {
            return code;
        }
        var writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anAcceptedBusinessSessionAdmitsSelectionWithoutAnActiveCollection(boolean catalogFailed) throws Throwable {
        // Forge support types must not depend on which other tests loaded them first.
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                // Minecraft packets already implement the parent loader's transport interface.
                if (name.equals("net.minecraftforge.network.ICustomPacket"))
                    return super.loadClass(name, resolve);
                if (!name.startsWith("com.elfmcys.ysm.network.forge.")
                    && !name.equals("com.elfmcys.ysm.network.NetworkHandler")
                    && !name.equals("com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider")
                    && !name.startsWith("net.minecraftforge.network."))
                    return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    var loaded = findLoadedClass(name);
                    if (loaded != null)
                        return loaded;
                    try (var bytes = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        var code = standaloneForgeType(name, java.util.Objects.requireNonNull(bytes).readAllBytes());
                        var type = defineClass(name, code, 0, code.length);
                        if (resolve)
                            resolveClass(type);
                        return type;
                    } catch (java.io.IOException failure) {
                        throw new ClassNotFoundException(name, failure);
                    }
                }
            }
        };
        var isolated = Class.forName(getClass().getName(), true, loader);
        var provider = Class.forName("com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider", true, loader);
        var capability = net.minecraftforge.common.capabilities.Capability.class.getDeclaredConstructor(String.class);
        capability.setAccessible(true);
        provider.getField("CAP").set(null, capability.newInstance(PlayerAnimatableCapability.class.getName()));
        var exercise = isolated.getDeclaredMethod("exerciseBusinessSession", boolean.class);
        exercise.setAccessible(true);
        try {
            exercise.invoke(null, catalogFailed);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static void exerciseBusinessSession(boolean catalogFailed) throws Exception {
        SharedConstants.tryDetectVersion();
        var bootstrap = field(Bootstrap.class, "isBootstrapped");
        var wasBootstrapped = bootstrap.getBoolean(null);
        bootstrap.setBoolean(null, true);
        BuiltInRegistries.REGISTRY.size();
        var minecraftSlot = field(Minecraft.class, "instance");
        var previousMinecraft = minecraftSlot.get(null);
        var currentSlot = field(ClientSessionRuntime.class, "current");
        var previousSession = currentSlot.get(null);
        var connection = new AdmissionConnection();
        var channel = new EmbeddedChannel();
        field(Connection.class, "channel").set(connection, channel);
        var session = new ClientModelSession(SessionMode.AUTO, true, CatalogSnapshot.empty());
        var stateType = Class.forName(ClientSessionRuntime.class.getName() + "$ConnectionState");
        var constructor = stateType.getDeclaredConstructor(Connection.class, ClientModelSession.class,
            Consumer.class, Object.class);
        constructor.setAccessible(true);
        var state = (AutoCloseable)constructor.newInstance(connection, session, (Consumer<String>)ignored -> {},
            new Object());
        try {
            currentSlot.set(null, state);
            assertTrue(ClientSessionRuntime.onServerHello(connection, ProtocolVersion.TRANSPORT_VERSION, false)
                .orElseThrow().accepted());
            ClientSessionRuntime.commitAcceptedSession(connection, false);
            var business = ClientSessionRuntime.businessSession().orElseThrow();
            if (catalogFailed)
                session.failCompleteTransfer();
            assertSame(business, ClientSessionRuntime.businessSession().orElseThrow());
            assertEquals(catalogFailed ? ClientModelSession.State.INTRINSIC_DEFAULT_ONLY
                : ClientModelSession.State.NEGOTIATING, session.state());

            var minecraft = allocate(Minecraft.class);
            minecraft.level = allocate(ClientLevel.class);
            var player = allocate(AdmissionPlayer.class);
            player.owner = allocate(PlayerAnimatableCapability.class);
            minecraft.player = player;
            minecraftSlot.set(null, minecraft);
            var listener = allocate(ClientPacketListener.class);
            field(ClientPacketListener.class, "connection").set(listener, connection);
            field(LocalPlayer.class, "connection").set(player, listener);
            var dataConstructor = Class.forName("net.minecraftforge.network.ConnectionData")
                .getDeclaredConstructor(Map.class, Map.class);
            dataConstructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            var dataKey = (AttributeKey<Object>)field(Class.forName("net.minecraftforge.network.NetworkConstants"),
                "FML_CONNECTION_DATA").get(null);
            channel.attr(dataKey).set(dataConstructor.newInstance(Map.of(),
                Map.of(NetworkHandler.CHANNEL_NAME, NetworkHandler.VERSION)));
            assertTrue(NetworkHandler.isRemoteChannelPresent());

            var production = new ClientDemandSelections();
            assertTrue(production.context().remote());
            assertSame(connection, production.context().owner());
            var allowResourceObservation = new boolean[1];
            var resources = (ClientDemandCoordinator.Resources)Proxy.newProxyInstance(
                ClientDemandSelectionAdmissionTest.class.getClassLoader(),
                new Class<?>[] {ClientDemandCoordinator.Resources.class}, (proxy, method, arguments) -> {
                    if (allowResourceObservation[0]) {
                        if (method.getName().equals("playerRequest") || method.getName().equals("defaultResourceRequest"))
                            return new ResourceRequest(new Hash256(new byte[Hash256.SIZE]), "player", "",
                                new BakeProfile("admission"));
                        if (method.getName().equals("permitsAcquisition"))
                            return false;
                        if (method.getName().equals("content"))
                            return null;
                    }
                    throw new AssertionError("Input feedback must precede resource admission: " + method);
                });
            var selections = new ClientDemandCoordinator.Selections() {
                public ClientDemandCoordinator.SelectionContext context() {
                    return production.context();
                }

                public ClientDemandCoordinator.Target authority() {
                    return new ClientDemandCoordinator.Target(null, "");
                }

                public boolean installed(ClientDemandCoordinator.Target target) {
                    return false;
                }

                public void send(ClientDemandCoordinator.Target target, long intentId) {
                    production.send(target, intentId);
                }

                public void reportFailure(Throwable failure) {}
            };
            try (var coordinator = new ClientDemandCoordinator(() -> 0L, resources, selections)) {
                coordinator.select(new ClientDemandCoordinator.Target(null, ""));
                assertTrue(coordinator.feedback().waiting(),
                    "A live business session must accept the input before collection completion or after catalog failure");
                allowResourceObservation[0] = true;
                coordinator.tick();
                assertEquals(1, connection.packets.size());
                var packet = assertInstanceOf(ServerboundCustomPayloadPacket.class, connection.packets.get(0));
                var bytes = new byte[packet.getData().readableBytes()];
                packet.getData().getBytes(packet.getData().readerIndex(), bytes);
                try (var frame = FrameCodec.decode(bytes, id -> id == ProtocolMessages.SELECT_MODEL_REQUEST_ID)) {
                    var request = ProtocolBuffer.parse(frame.protobuf(), SelectModelRequestProto::parseFrom);
                    assertTrue(request.hasIntrinsicDefault());
                    assertTrue(request.intrinsicDefault());
                    assertEquals(1L, request.intentId());
                } finally {
                    packet.getData().release();
                }
                field(stateType, "businessSession").set(state, null);
                assertFalse(production.context().available());
                assertThrows(IllegalStateException.class,
                    () -> production.send(new ClientDemandCoordinator.Target(null, ""), 2));
                field(stateType, "businessSession").set(state, business);
                ClientSessionRuntime.failProtocolSession(connection);
                assertFalse(production.context().available());
                assertThrows(IllegalStateException.class,
                    () -> production.send(new ClientDemandCoordinator.Target(null, ""), 2));
                assertEquals(1, connection.packets.size());
            }
        } finally {
            currentSlot.set(null, previousSession);
            minecraftSlot.set(null, previousMinecraft);
            state.close();
            channel.finishAndReleaseAll();
            bootstrap.setBoolean(null, wasBootstrapped);
        }
    }

    private static <T> T allocate(Class<T> type) throws InstantiationException {
        return type.cast(UnsafeUtil.getUnsafe().allocateInstance(type));
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class AdmissionPlayer extends LocalPlayer {
        private PlayerAnimatableCapability owner;

        private AdmissionPlayer() {
            super(null, null, null, null, null, false, false);
        }

        @Override
        public <T> LazyOptional<T> getCapability(net.minecraftforge.common.capabilities.Capability<T> capability,
            net.minecraft.core.Direction side) {
            return LazyOptional.of(() -> owner).cast();
        }
    }

    private static final class AdmissionConnection extends Connection {
        final ArrayList<Packet<?>> packets = new ArrayList<>();

        AdmissionConnection() {
            super(PacketFlow.CLIENTBOUND);
        }

        @Override
        public void send(Packet<?> packet) {
            packets.add(packet);
        }
    }
}
