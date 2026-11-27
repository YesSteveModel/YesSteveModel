package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.animation.physics.PlayerTeleportState;
import com.elfmcys.ysm.client.command.sub.PhysicsCommand;
import com.elfmcys.ysm.client.physics.debug.PhysicsDebugController;
import com.elfmcys.ysm.expression.host.PlayerQueryAccess;
import com.elfmcys.ysm.expression.host.PlayerQueryProjection;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.natives.physics.NativePhysicsWorld;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import com.elfmcys.ysm.util.StringPool;
import com.elfmcys.ysm.util.UnsafeUtil;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class PlayerPhysicsHostFixture {
    private PlayerPhysicsHostFixture() {}

    public static void verifyTimeline(ResourceRequest request, ResourceLease lease, ModelRenderTarget target,
        ModelContent content) throws Exception {
        var body = RenderContext.levelImmutable();
        var shadow = new RenderContext(true, true, false, false, false, false, false);
        var firstPerson = new RenderContext(true, true, true, false, false, false, false);
        var subject = headlessPlayer();
        var owner = new HostOwner(subject, new EntityModelBinding(new FixtureModels(request, content)));
        try {
            owner.install(request, lease, target);
            PhysicsDebugController.enable(owner);
            verifySelectionBeforeSample(target);
            subject.tickCount = 20;
            var firstShadow = assertNotNullResult(owner.evaluate(0, shadow));
            assertNull(owner.getModelState());
            assertEquals(0, owner.mainTicks);
            assertEquals(1, owner.shadowPoses);
            assertFalse(owner.consumePrepared(firstShadow,
                data -> owner.visitPhysicsSample(data, (world, metadata) -> fail("shadow initialized physics"))));
            assertFalse(
                owner.consumePrepared(firstShadow, data -> PhysicsDebugController.capture(owner, data) != null));

            assertTrue(owner.animationOnly(0, body));
            assertEquals(1, owner.mainTicks);
            var firstBody = assertNotNullResult(owner.evaluate(0, body));
            assertSame(firstBody.modelState, owner.getModelState());
            assertTrue(owner.consumePrepared(firstBody, data -> owner.visitPhysicsSample(data,
                (world, metadata) -> assertSame(target.physicsMetadata(), metadata))));
            var initial = capture(owner, firstBody);
            assertFalse(initial.snapshot().steppedSinceReset());
            assertEquals(2, initial.snapshot().nodes().size());
            assertEquals(target.physicsMetadata().constraints().size(), initial.snapshot().joints().size());
            assertSame(initial.snapshot(), PhysicsDebugController.hudView().snapshot());
            var oldEpoch = firstBody.physicsSampleEpoch;

            PhysicsDebugController.disable();
            assertNull(PhysicsDebugController.hudView());
            subject.tickCount++;
            var beforeBody = assertNotNullResult(owner.evaluate(0, shadow));
            assertNotSame(firstBody, beforeBody);
            assertSame(firstBody.modelState, owner.getModelState());
            assertEquals(1, owner.mainTicks);
            assertEquals(0.25F, owner.bodyX());
            assertTrue(owner.consumePrepared(beforeBody, data -> owner.visitPhysicsSample(data,
                (world, metadata) -> assertEquals(oldEpoch, data.physicsSampleEpoch))));
            var nextBody = assertNotNullResult(owner.evaluate(0, body));
            assertSame(firstBody, nextBody);
            assertNotEquals(oldEpoch, nextBody.physicsSampleEpoch);
            assertEquals(2, owner.mainTicks);
            assertEquals(0.25F, owner.bodyX());
            PhysicsDebugController.enable(owner);
            PhysicsDebugController.beginWorldRender();
            assertNull(PhysicsDebugController.hudView().snapshot());
            var afterOpening = capture(owner, nextBody);
            assertTrue(afterOpening.snapshot().steppedSinceReset());
            assertFalse(afterOpening.snapshot().sampledLastStep());

            var localPass = assertNotNullResult(owner.evaluate(0, firstPerson));
            assertNotSame(nextBody, localPass);
            assertFalse(owner.consumePrepared(localPass,
                data -> owner.visitPhysicsSample(data, (world, metadata) -> fail("first person read world physics"))));
            assertFalse(owner.consumePrepared(localPass, data -> PhysicsDebugController.capture(owner, data) != null));
            subject.tickCount++;
            var sampledBody = assertNotNullResult(owner.evaluate(0, body));
            assertTrue(capture(owner, sampledBody).snapshot().sampledLastStep());
            subject.teleport = new PlayerTeleportState(1, false, 0);
            assertNull(PhysicsDebugController.hudView().snapshot());
            assertFalse(owner.consumePrepared(sampledBody, data -> fail("stale world sample was drawn")));
            assertNull(owner.getModelState());
            owner.failWorldCreation(target);
            assertEquals("WORLD_CREATE_FAILED", PhysicsDebugController.hudView().failure());
            assertTrue(owner.retryPhysics());
            assertNull(PhysicsDebugController.hudView().failure());
            subject.tickCount++;
            assertFalse(capture(owner, assertNotNullResult(owner.evaluate(0, body))).snapshot().steppedSinceReset());
            subject.tickCount++;
            assertTrue(capture(owner, assertNotNullResult(owner.evaluate(0, body))).snapshot().sampledLastStep());
        } finally {
            owner.retire();
        }
        assertNull(PhysicsDebugController.target());
        assertNull(PhysicsDebugController.hudView());
    }

    private static void verifySelectionBeforeSample(ModelRenderTarget target) throws Exception {
        var view = assertNotNullResult(PhysicsDebugController.hudView());
        assertSame(target.physicsMetadata(), view.metadata());
        assertNull(view.snapshot());
        assertEquals("hair_chain:strand", view.metadata().nodes().get(0).presetTag().orElseThrow());

        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(PhysicsCommand.get());
        var source = (CommandSourceStack)UnsafeUtil.getUnsafe().allocateInstance(CommandSourceStack.class);
        assertEquals(List.of("\"strand/body/0\""),
            dispatcher.getCompletionSuggestions(dispatcher.parse("physics select node st", source)).get().getList()
                .stream().map(Suggestion::getText).toList());
        assertEquals(List.of("\"strand/joint/0\""),
            dispatcher.getCompletionSuggestions(dispatcher.parse("physics select constraint st", source)).get()
                .getList().stream().map(Suggestion::getText).toList());
        assertEquals(1, dispatcher.execute("physics select pair \"strand/body/0\" anchor", source));
        assertEquals(new PhysicsDebugController.Selection(PhysicsDebugController.Kind.PAIR, 0, 1),
            PhysicsDebugController.hudView().selection());
    }

    private static PhysicsDebugController.View capture(HostOwner owner, GeoRenderData data) {
        var result = new PhysicsDebugController.View[1];
        assertTrue(owner.consumePrepared(data, borrowed -> {
            result[0] = PhysicsDebugController.capture(owner, borrowed);
            return result[0] != null;
        }));
        return result[0];
    }

    private static <T> T assertNotNullResult(T value) {
        assertNotNull(value);
        return value;
    }

    private record FixtureModels(ResourceRequest request,
        ModelContent content) implements EntityModelBinding.ModelAccess {
        @Override
        public ModelContent content(ModelId modelId) {
            return content.modelId().equals(modelId) ? content : null;
        }

        @Override
        public ResourceRequest resourceRequest(ModelId modelId, String targetId, String textureName) {
            return request;
        }

        @Override
        public ResourceRequest defaultResourceRequest(String targetId) {
            return null;
        }

        @Override
        public void reportActiveModelFailure(ModelContent content, boolean fallbackAvailable) {
            fail("The fixture model failed");
        }
    }

    private static HostPlayer headlessPlayer() throws Exception {
        SharedConstants.tryDetectVersion();
        var bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapped.setAccessible(true);
        var previous = bootstrapped.getBoolean(null);
        bootstrapped.setBoolean(null, true);
        try {
            BuiltInRegistries.REGISTRY.size();
            var player = (HostPlayer)UnsafeUtil.getUnsafe().allocateInstance(HostPlayer.class);
            var position = Entity.class.getDeclaredField("position");
            position.setAccessible(true);
            position.set(player, Vec3.ZERO);
            var uuid = Entity.class.getDeclaredField("uuid");
            uuid.setAccessible(true);
            uuid.set(player, UUID.fromString("00000000-0000-0000-0000-000000000001"));
            player.teleport = PlayerTeleportState.NONE;
            player.projection = PlayerQueryProjection.MISSING;
            return player;
        } finally {
            bootstrapped.setBoolean(null, previous);
        }
    }

    private static final class HostPlayer extends RemotePlayer
        implements PlayerTeleportState.Access, PlayerQueryAccess {
        private PlayerTeleportState teleport;
        private PlayerQueryProjection projection;

        private HostPlayer() {
            super(null, null);
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public boolean isDeadOrDying() {
            return true;
        }

        @Override
        public boolean isSleeping() {
            return false;
        }

        @Override
        public boolean isFullyFrozen() {
            return false;
        }

        @Override
        public boolean isCrouching() {
            return false;
        }

        @Override
        public boolean isSpectator() {
            return true;
        }

        @Override
        public boolean isBaby() {
            return false;
        }

        @Override
        public Pose getPose() {
            return Pose.STANDING;
        }

        @Override
        public net.minecraft.network.chat.Component getName() {
            return net.minecraft.network.chat.Component.literal("HostPhysicsFixture");
        }

        @Override
        public PlayerQueryProjection ysm$playerQueryProjection() {
            return projection;
        }

        @Override
        public void ysm$playerQueryProjection(PlayerQueryProjection projection) {
            this.projection = projection;
        }

        @Override
        public PlayerTeleportState ysm$teleportState() {
            return teleport;
        }

        @Override
        public void ysm$teleportState(PlayerTeleportState state) {
            teleport = state;
        }
    }

    private static final class HostOwner extends CustomHumanoidEntity<HostPlayer> {
        private int mainTicks;
        private int shadowPoses;

        HostOwner(HostPlayer player, EntityModelBinding binding) {
            super(player, false, binding);
        }

        void install(ResourceRequest request, ResourceLease lease, ModelRenderTarget target) throws Exception {
            installReadyForPreview(request, lease);
            var gate = new PlayerPhysicsSession.RecoveryGate(target.stateIdentity(), entity.level());
            replacePhysics(PlayerPhysicsSession.open("host integration fixture", gate,
                () -> NativePhysicsWorld.create(target.physicsAsset())));
        }

        void failWorldCreation(ModelRenderTarget target) throws Exception {
            var gate = new PlayerPhysicsSession.RecoveryGate(target.stateIdentity(), entity.level());
            replacePhysics(PlayerPhysicsSession.open("host integration fixture", gate, () -> null));
        }

        private void replacePhysics(PlayerPhysicsSession replacement) throws Exception {
            var session = AnimatableEntity.class.getDeclaredField("animationSession");
            session.setAccessible(true);
            var physics = session.getType().getDeclaredField("physics");
            physics.setAccessible(true);
            var runtime = session.get(this);
            if (physics.get(runtime) instanceof PlayerPhysicsSession previous)
                previous.close();
            physics.set(runtime, replacement);
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public int getFrameRateLimit() {
            return 60;
        }

        @Override
        public boolean determineImmutableContext(RenderContext context) {
            return !context.irisShadow() && super.determineImmutableContext(context);
        }

        @Override
        protected void prepareAnimationControllers(ModelRenderTarget target, AnimationData animations) {}

        @Override
        protected void postAnimationSetup(float seekTime, boolean shouldTick) {
            if (shouldTick)
                mainTicks++;
        }

        @Override
        protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<HostPlayer>> event, boolean update) {
            if (event.getRenderContext().irisShadow())
                shadowPoses++;
            getLoadedGeoModel().getBoneMap().get(StringPool.computeIfAbsent("body"))
                .setPosition(event.getRenderContext().irisShadow() ? 3.0F : 0.25F, 0, 0);
        }

        float bodyX() {
            return getLoadedGeoModel().getBoneMap().get(StringPool.computeIfAbsent("body")).getPositionX();
        }

        GeoRenderData evaluate(float partialTick, RenderContext context) {
            return update(partialTick, context);
        }

        boolean animationOnly(float partialTick, RenderContext context) {
            return updateAnimationOnly(partialTick, context);
        }
    }
}
