package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.resource.client.render.AnimationInputStore;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;
import static com.elfmcys.ysm.expression.TestRoamingPayloads.named;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.aot.*;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.BuiltinDeclarations;
import com.elfmcys.ysm.expression.host.ComponentScope;
import com.elfmcys.ysm.expression.host.EventArguments;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.InvocationInputs;
import com.elfmcys.ysm.expression.host.TestComponentHolders;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.network.protocol.RoamingDictionary;
import com.elfmcys.ysm.network.protocol.RoamingValues;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.UnsafeUtil;
import dev.semaweave.RoamingRole;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ClientRoamingReplayRuntimeTest {
    @BeforeAll
    static void initializeMinecraftSubject() throws ReflectiveOperationException {
        net.minecraft.SharedConstants.tryDetectVersion();
        var gate = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        gate.setAccessible(true);
        boolean previous = gate.getBoolean(null);
        gate.setBoolean(null, true);
        try {
            net.minecraft.core.registries.BuiltInRegistries.REGISTRY.size();
            Class.forName(ArmorStand.class.getName(), true, ClientRoamingReplayRuntimeTest.class.getClassLoader());
        } finally {
            gate.setBoolean(null, previous);
        }
    }

    @Test
    void interleavedStatePreservesEarlierSyncWritesUntilTheFollowingNetworkWrite(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root, "r.calls=r.calls+1;r.seen_x=r.x;r.seen_y=r.y;r.y=args[0]==1?9:r.y;",
            "r.deferred=args[1];")) {
            fixture.full(0F, 0F);
            fixture.delta(new short[] {0}, 1F);
            fixture.delta(new short[] {1}, 2F);
            fixture.sync(1F);
            fixture.delta(new short[] {0}, 3F);
            fixture.delta(new short[] {0}, 4F);
            fixture.sync(2F);
            fixture.delta(new short[] {1}, 5F);

            fixture.installAndDrain();

            assertEquals(2, fixture.events.size());
            assertEquals(1F, fixture.events.get(0).get("seen_x"));
            assertEquals(2F, fixture.events.get(0).get("seen_y"));
            assertEquals(4F, fixture.events.get(1).get("seen_x"));
            assertEquals(9F, fixture.events.get(1).get("seen_y"));
            assertEquals(4F, fixture.read("x"));
            assertEquals(5F, fixture.read("y"));
            assertEquals(2F, fixture.read("calls"));
            assertEquals(0, fixture.budget.pendingSyncs());
        }
    }

    @Test
    void sameValueDeltaStillOverwritesThePreviousSyncSideEffect(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root, "r.seen_x=r.x;r.x=args[0]==1?9:r.x;", "r.deferred=args[1];")) {
            fixture.full(0F, 0F);
            fixture.sync(1F);
            fixture.delta(new short[] {0}, 0F);
            fixture.sync(2F);

            fixture.installAndDrain();

            assertEquals(9F, fixture.events.get(0).get("x"));
            assertEquals(0F, fixture.events.get(1).get("seen_x"));
            assertEquals(0F, fixture.read("x"));
        }
    }

    @Test
    void fullAfterSyncReplacesTheWholeDeclaredDomainBeforeTheNextEvent(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root,
            "r.seen_x=r.x;r.seen_y=r.y;r.seen_script=r.script_only;r.script_only=args[0]==1?13:r.script_only;"
                + "r.y=args[0]==1?9:r.y;",
            "r.deferred=args[1];")) {
            fixture.full(1F, 2F);
            fixture.sync(1F);
            fixture.full(new RoamingValues(new short[] {0}, new float[] {7F}));
            fixture.delta(new short[] {0}, 8F);
            fixture.sync(2F);

            fixture.installAndDrain();

            assertEquals(2, fixture.events.size());
            assertEquals(13F, fixture.events.get(0).get("script_only"));
            assertEquals(9F, fixture.events.get(0).get("y"));
            assertEquals(0F, fixture.events.get(1).get("seen_y"));
            assertEquals(0F, fixture.events.get(1).get("seen_script"));
            assertEquals(8F, fixture.read("x"));
            assertEquals(0F, fixture.read("y"));
            assertEquals(0F, fixture.read("script_only"));
        }
    }

    @Test
    void repeatedEventsRemainDistinctAndDeferredActionsFinishBeforeTheNextSegment(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root, "r.calls=r.calls+1;r.seen_x=r.x;ysm.defer(0,args[0]);",
            "r.x=args[1];r.deferred=r.deferred+1;")) {
            fixture.full(0F, 0F);
            fixture.sync(5F);
            fixture.sync(5F);
            fixture.delta(new short[] {0}, 1F);
            fixture.sync(7F);
            fixture.delta(new short[] {0}, 2F);

            fixture.installAndDrain();

            assertEquals(3, fixture.events.size());
            assertEquals(List.of(0F, 5F, 1F), fixture.events.stream().map(event -> event.get("seen_x")).toList(),
                fixture.events.toString());
            assertEquals(List.of(5F, 5F, 7F), fixture.events.stream().map(event -> event.get("x")).toList());
            assertEquals(List.of(1F, 2F, 3F), fixture.events.stream().map(event -> event.get("deferred")).toList());
            assertEquals(2F, fixture.read("x"));
            assertEquals(3F, fixture.read("calls"));
            assertEquals(0, fixture.budget.pendingSyncs());
        }
    }

    private static UserFunctionProto eventFunction(String name, String source) throws Exception {
        var encoded = EncodedProgramMapper.toProto(new MolangFrontend()
            .compile(source, MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("replay/" + name))
            .program());
        return UserFunctionProto.newBuilder().setName(name).setBody(encoded).build();
    }

    private static EntityExpressionRuntime.InvocationInputs inputs(Entity subject) {
        return new EntityExpressionRuntime.InvocationInputs(new InvocationInputs<Entity>() {
            @Override
            public Entity entity() {
                return subject;
            }
        });
    }

    private static final class Fixture implements AutoCloseable {
        private final Object operation = new Object();
        private final RemoteSyncBudget budget = new RemoteSyncBudget();
        private final EntityExpressionRuntime runtime = new EntityExpressionRuntime();
        private final ClientRoamingSession session = new ClientRoamingSession(null, false, () -> {
        }, () -> operation);
        private final RoamingDictionary dictionary = new RoamingDictionary(new String[] {"x", "y"});
        private final List<Map<String, Float>> events = new ArrayList<>();
        private final RenderTargetExpressionModule module;
        private final com.elfmcys.ysm.model.resource.client.AnimationStore animationStore;
        private final Entity subject;
        private final ComponentScope components;
        private final Map<String, List<EventProgram>> handlers;
        private final ModelId model;

        Fixture(Path root, String syncSource, String deferSource) throws Exception {
            var host = ExpressionHost.create(HostAvailability.none());
            subject = (Entity)UnsafeUtil.getUnsafe().allocateInstance(ArmorStand.class);
            components = BuiltinDeclarations.createComponents(TestComponentHolders.owner(subject), host);
            var expressionCache = track(new AtomicSharedCache(root));
            var factory =
                new RenderTargetExpressionModuleFactory(track(new ExpressionArtifactStore(root, expressionCache)),
                    new AnimationInputStore(root, expressionCache), host, new ExpressionEngineMetrics());
            var bytes = new byte[Hash256.SIZE];
            bytes[0] = 1;
            var containerId = new Hash256(bytes);
            model = ModelId.fromHash(containerId);
            var strings = StringDataProto.newBuilder().addUserFunctions(eventFunction("@sync", syncSource))
                .addUserFunctions(eventFunction("@defer", deferSource)).build();
            module = factory.createSet(() -> false, containerId, "player", ExpressionSet.MAIN,
                factory.expressionBinding(), ModelDataProto.newBuilder().build(), strings,
                SettingsProto.newBuilder().build(), new byte[0], false);
            handlers = Map.of("sync", List.of(module.requireEventProgram(location("sync", ProgramRole.EVENT_SYNC))),
                "defer", List.of(module.requireEventProgram(location("defer", ProgramRole.EVENT_DEFER))));
            assertEquals(0, module.compilationReport().replacements().size());
            assertTrue(module.compilationReport().localRecoveries().isEmpty());
            animationStore = com.elfmcys.ysm.expression.aot.TestExpressionModules.store(module, handlers);
        }

        private static ModelProgramInventory.SemanticLocation location(String event, ProgramRole role) {
            return new ModelProgramInventory.SemanticLocation("player", ExpressionSet.MAIN, "function/@" + event, role,
                event + ":0");
        }

        void full(float x, float y) {
            full(new RoamingValues(new short[] {0, 1}, new float[] {x, y}));
        }

        void full(RoamingValues values) {
            session.acceptSelection(runtime,
                new CommittedSelection(PublishedModelId.model(model), 1, null, dictionary, values, "", ""), operation,
                budget);
        }

        void delta(short[] ids, float... values) {
            session.updateRemote(runtime, new RoamingValues(ids, values));
        }

        void sync(float... arguments) {
            session.acceptSync(runtime, EventArguments.sync(arguments), this::dispatch);
        }

        void installAndDrain() {
            try (var prepared = runtime.prepare(animationStore, components, RoamingRole.CONSUMER)) {
                runtime.install(prepared, session.prepare(model, prepared, PublishedModelId.model(model)));
            }
            session.drain(runtime, this::dispatch);
        }

        private void dispatch(EventArguments arguments) {
            runtime.action(inputs(subject), evaluation -> {
                evaluation.dispatchSync(arguments);
                return null;
            });
            events.add(named(runtime.roamingState().orElseThrow()));
        }

        float read(String field) {
            return runtime.roamingState().orElseThrow().read((short)module.roamingFields().indexOf(field));
        }

        @Override
        public void close() {
            session.clearRemote();
            runtime.close();
            components.invalidate();
            animationStore.close();
        }
    }
}
