package com.elfmcys.ysm.verification.physicsframe;

import com.elfmcys.ysm.capability.ClientLazyCapabilityProvider;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleAnimatableCapability;
import com.elfmcys.ysm.client.animation.AnimationParallelTicker;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.physics.debug.PhysicsDebugController;
import com.elfmcys.ysm.client.renderer.CustomPlayerRenderer;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.natives.physics.PhysicsDebugSnapshot;
import com.elfmcys.ysm.natives.physics.PhysicsDebugSnapshot.Contact;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollisionShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeKind;
import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.LongAdder;

/** Drives production client entities in a disposable Forge world. */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class PhysicsFrameHost {
    private static final int FIRST_ID = 1_000_000;
    private static final int VISIBILITY_SETTLE_TICKS = 25;
    private static final Path OUTPUT = Path.of(System.getenv("YSM_FRAME_OUTPUT"));
    private static final Plan PLAN = readPlan();
    private static final List<Visit> VISITS = visits();
    private static final List<RemotePlayer> PLAYERS = new ArrayList<>();
    private static final List<PlayerAnimatableCapability> OWNERS = new ArrayList<>();
    private static final List<VehicleAnimatableCapability> VEHICLES = new ArrayList<>();
    private static final List<Frame> FRAMES = new ArrayList<>();
    private static final List<Asset> ASSETS = new ArrayList<>();
    private static final List<ContactSample> CONTACTS = new ArrayList<>();
    private static final List<ClockSample> CLOCK_SAMPLES = new ArrayList<>();
    private static final HashMap<String, Checkpoint> CHECKPOINTS = new HashMap<>();
    private static final HashSet<String> LOGGED_ASSETS = new HashSet<>();
    private static final LongAdder STEPS = new LongAdder();
    private static final LongAdder RESETS = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();

    private static boolean requestedWorld;
    private static boolean finished;
    private static int visitIndex = -1;
    private static volatile int age;
    private static int collecting;
    private static int frameVisit;
    private static int frameView;
    private static int worlds;
    private static double originX;
    private static double originY;
    private static double originZ;
    private static boolean sampling;
    private static boolean rendering;
    private static long start;
    private static long renderNanos;
    private static long claimedNanos;
    private static long demandJoinNanos;
    private static long drainJoinNanos;
    private static int claimedTasks;
    private static int demandJoins;
    private static int drainJoins;
    private static int visible;
    private static int replacements;
    private static int witnessedRidersVisit = -1;
    private static long passengerSamplingStart;
    private static long passengerSamplingNanos;
    private static long lastSpawnTick;
    private static volatile WitnessPlayback witness;
    private static final ThreadLocal<WitnessPlayback> ACTIVE_WITNESS = new ThreadLocal<>();

    private static Plan readPlan() {
        try (var reader = Files.newBufferedReader(OUTPUT.resolve("plan.json"))) {
            return new Gson().fromJson(reader, Plan.class);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static List<Visit> visits() {
        var result = new ArrayList<Visit>();
        if (PLAN.pairingVisits != null) {
            for (var requested : PLAN.pairingVisits) {
                var test = PLAN.cases.get(requested.caseIndex);
                result.add(new Visit(test, requested.repeat, requested.mode, test.models.get(requested.mode), ""));
            }
            return List.copyOf(result);
        }
        if (PLAN.witness) {
            for (var test : PLAN.cases) {
                for (String sequence : List.of("near", "long_step", "reset_025", "natural_10fps"))
                    result.add(new Visit(test, 0, 2, test.models.get(2), sequence));
                result.add(new Visit(test, 0, 1, test.models.get(1), "near"));
                result.add(new Visit(test, 0, 0, test.models.get(0), "near"));
            }
            return List.copyOf(result);
        }
        for (int repeat = 0; repeat < PLAN.repetitions; repeat++) {
            for (int index = 0; index < PLAN.cases.size(); index++) {
                int scenario = (index + repeat) % PLAN.cases.size();
                var test = PLAN.cases.get(scenario);
                for (int offset = 0; offset < 3; offset++) {
                    int mode = (offset + repeat + scenario) % 3;
                    result.add(new Visit(test, repeat, mode, test.models.get(mode), ""));
                }
            }
        }
        return List.copyOf(result);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END)
            return;
        var minecraft = Minecraft.getInstance();
        try {
            if (!requestedWorld && minecraft.getOverlay() == null && minecraft.screen != null) {
                requestedWorld = true;
                var rules = new GameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
                minecraft.createWorldOpenFlows().createFreshLevel("physics-frame-host",
                    new LevelSettings("Physics frame host", GameType.CREATIVE, false, Difficulty.PEACEFUL, false, rules,
                        WorldDataConfiguration.DEFAULT),
                    new WorldOptions(42L, false, false), access -> access.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            }
            if (minecraft.level == null || minecraft.player == null || minecraft.getOverlay() != null)
                return;
            if (visitIndex < 0) {
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.hideGui = true;
                minecraft.options.pauseOnLostFocus = false;
                minecraft.setScreen(null);
                if (!variantsReady())
                    return;
                originX = minecraft.player.getX();
                originY = minecraft.player.getY();
                originZ = minecraft.player.getZ();
                nextVisit(minecraft);
                return;
            }
            if (age < 0) {
                if (!OWNERS.stream().allMatch(PlayerAnimatableCapability::hasMatchingInstallation)
                    || !VEHICLES.stream().allMatch(VehicleAnimatableCapability::hasMatchingInstallation)) {
                    require(minecraft.level.getGameTime() - lastSpawnTick < 400, "model installations timed out");
                    return;
                }
                inspectAsset();
                age = 0;
                if (PLAN.witness)
                    witness.begin(minecraft, OWNERS.get(0));
            }
            if (PLAN.witness) {
                if (witness.finished || witness.natural && age == 80) {
                    nextVisit(minecraft);
                } else {
                    require(age < 200, "witness frame was not drawn: " + VISITS.get(visitIndex));
                    if (witness.natural && age == 30)
                        minecraft.options.framerateLimit().set(260);
                    aimCamera(minecraft, true);
                    age++;
                }
                return;
            }
            boolean pairing = PLAN.pairingVisits != null;
            if (pairing && PLAN.pairingVisits.get(visitIndex).warmup) {
                if (age == PLAN.warmupTicks)
                    nextVisit(minecraft);
                else {
                    aimCamera(minecraft, true);
                    replay(age++);
                }
                return;
            }
            int settle = pairing ? PLAN.settleTicks : VISIBILITY_SETTLE_TICKS;
            int window = settle + PLAN.sampleTicks;
            int view = age / window;
            if (view == (pairing ? 1 : 3)) {
                nextVisit(minecraft);
                return;
            }
            // The second sweep returns to the starting view. Analyze the three windows independently.
            boolean onScreen = pairing || (view + VISITS.get(visitIndex).repeat) % 2 == 0;
            aimCamera(minecraft, onScreen);
            replay(age % window);
            int nextCollection = age % window >= settle ? 1 + view : 0;
            if (PLAN.contactCheck) {
                if (onScreen && VISITS.get(visitIndex).mode != 0) {
                    PhysicsDebugController.enable(OWNERS.get(0));
                    var debug = PhysicsDebugController.hudView();
                    if (debug != null && debug.snapshot() != null && debug.snapshot().sampledLastStep())
                        captureContacts(debug, -1);
                } else {
                    PhysicsDebugController.disable();
                }
            } else if (nextCollection != 0 && collecting == 0) {
                inspectWorlds();
            }
            collecting = PLAN.contactCheck ? 0 : nextCollection;
            age++;
        } catch (Throwable failure) {
            finish(minecraft, failure);
        }
    }

    private static void aimCamera(Minecraft minecraft, boolean onScreen) {
        var observer = minecraft.player;
        observer.setYRot(onScreen ? 0.0f : 180.0f);
        observer.yRotO = observer.getYRot();
        observer.setXRot(PLAN.witness ? 12 : -6);
        observer.xRotO = observer.getXRot();
        observer.setDeltaMovement(0, observer.getDeltaMovement().y, 0);
    }

    private static boolean variantsReady() {
        var service = ClientModelService.current().orElse(null);
        return service != null && VISITS.stream()
            .allMatch(visit -> service.resolvePath(visit.model).orElse(null) instanceof PublishedModelId.Model);
    }

    private static void nextVisit(Minecraft minecraft) {
        collecting = 0;
        PhysicsDebugController.disable();
        for (var player : PLAYERS)
            minecraft.level.removeEntity(player.getId(), Entity.RemovalReason.DISCARDED);
        for (var vehicle : VEHICLES)
            minecraft.level.removeEntity(vehicle.getEntity().getId(), Entity.RemovalReason.DISCARDED);
        PLAYERS.clear();
        OWNERS.clear();
        VEHICLES.clear();
        witness = null;
        if (PLAN.witness)
            minecraft.options.framerateLimit().set(260);
        if (++visitIndex == VISITS.size()) {
            finish(minecraft, null);
            return;
        }
        var visit = VISITS.get(visitIndex);
        int columns = (int)Math.ceil(Math.sqrt(visit.test.entities));
        for (int i = 0; i < visit.test.entities; i++) {
            var player = new RemotePlayer(minecraft.level,
                new GameProfile(new UUID(0x506879734672616dL, i + 1), "FrameActor" + i));
            player.setId(FIRST_ID + i);
            // The angular grid keeps every subject in the same near-distance tier and render frustum.
            double spread = Math.max(1, columns - 1);
            double azimuth = Math.toRadians((i % columns / spread - 0.5) * 50);
            double elevation = Math.toRadians(2 + i / columns / spread * 20);
            double groundDistance = PLAN.witness ? 3.6 : 18 * Math.cos(elevation);
            double x = originX + groundDistance * Math.sin(azimuth);
            double z = originZ + groundDistance * Math.cos(azimuth);
            player.setNoGravity(true);
            if (PLAN.witness)
                player.absMoveTo(originX, originY, originZ + groundDistance, 180.0f, 0.0f);
            else
                player.absMoveTo(x, originY + 18 * Math.sin(elevation), z, 180.0f, 0.0f);
            minecraft.level.addPlayer(player.getId(), player);
            var owner = player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
                .orElseThrow(() -> new IllegalStateException("Real player capability was not attached"));
            owner.updateModelAndTexture(visit.model, visit.test.texture);
            PLAYERS.add(player);
            OWNERS.add(owner);
            if (PLAN.riding)
                mount(minecraft, player, i);
        }
        if (PLAN.witness)
            witness = new WitnessPlayback(PLAYERS.get(0), visit, CHECKPOINTS.get(visit.test.name));
        System.out.println("[physics-frame] " + visit);
        lastSpawnTick = minecraft.level.getGameTime();
        age = -1;
    }

    private static void mount(Minecraft minecraft, RemotePlayer player, int index) {
        var boat = new Boat(minecraft.level, player.getX(), player.getY() - 0.25, player.getZ());
        boat.setId(FIRST_ID + 100 + index);
        boat.setNoGravity(true);
        boat.setYRot(180);
        minecraft.level.putNonPlayerEntity(boat.getId(), boat);
        require(player.startRiding(boat, true), "could not mount the test boat");
        var owner = boat.getCapability(ClientLazyCapabilityProvider.CAP).orElseThrow(IllegalStateException::new)
            .getVehicleAnimatableCapabilityProvider().initialize();
        owner.init(null);
        VEHICLES.add(owner);
    }

    private static void replay(int tick) {
        var scenario = VISITS.get(visitIndex).test;
        float yaw = 180f + 80f * (float)Math.sin(tick * (Math.PI / 12));
        String animation = scenario.animation;
        for (int i = 0; i < PLAYERS.size(); i++) {
            var player = PLAYERS.get(i);
            player.yBodyRotO = player.yBodyRot;
            player.yHeadRotO = player.yHeadRot;
            player.yRotO = player.getYRot();
            player.yBodyRot = yaw;
            player.yHeadRot = yaw;
            player.setYRot(yaw);
            if (!animation.isEmpty() && tick % 48 == 0) {
                require(OWNERS.get(i).getAnimation(animation) != null, "missing extra animation: " + animation);
                OWNERS.get(i).playExtraAnimation(animation);
            }
        }
    }

    private static ContactSample captureContacts(PhysicsDebugController.View view, int sample) {
        int interpart = 0;
        int body = 0;
        var pairs = new ArrayList<ContactPair>();
        var nodes = view.metadata().nodes();
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (view.snapshot().contact(i, j) != Contact.TOUCHING)
                    continue;
                if (PLAN.witness)
                    pairs.add(new ContactPair(nodes.get(j).id(), nodes.get(i).id()));
                if (nodes.get(i).kind() == PhysicsNodeKind.DYNAMIC && nodes.get(j).kind() == PhysicsNodeKind.DYNAMIC)
                    interpart++;
                else
                    body++;
            }
        }
        var snapshot = view.snapshot();
        var result = new ContactSample(visitIndex, age, sample, interpart, body, pairs,
            PLAN.witness ? snapshot.nodes() : List.of(), snapshot.sampledLastStep(), snapshot.steppedSinceReset());
        CONTACTS.add(result);
        return result;
    }

    private static void inspectAsset() {
        var visit = VISITS.get(visitIndex);
        var target = OWNERS.get(0).getModelRenderTarget();
        var metadata = target.physicsMetadata();
        require((metadata == null) == (visit.mode == 0), "wrong physics mode for " + visit.model);
        if (!LOGGED_ASSETS.add(visit.model))
            return;
        var nodes = new ArrayList<Node>();
        if (metadata != null) {
            for (var item : metadata.nodes()) {
                var boxes = new ArrayList<Box>();
                if (PLAN.witness) {
                    for (var shape : item.shapes()) {
                        if (shape.shapeCase() == CollisionShapeProto.ShapeCase.BOX) {
                            var box = shape.box();
                            boxes.add(new Box(box.center(), box.size_(), box.rotation()));
                        }
                    }
                }
                nodes.add(new Node(item.id(), item.kind().toString(), item.shapes().size(),
                    item.kind() == PhysicsNodeKind.ANCHOR ? 0 : item.collectionMask().orElse(1), boxes));
            }
        }
        ASSETS.add(new Asset(visit.model, target.modelHash().toString(), nodes));
    }

    private static void inspectWorlds() {
        worlds = 0;
        for (var owner : OWNERS) {
            var state = owner.physicsDebugStatus();
            if (state != null) {
                require(state.failure() == null, "degraded physics: " + state.failure());
                if (state.sampleEpoch() >= 0)
                    worlds++;
            }
        }
        var visit = VISITS.get(visitIndex);
        require(worlds == (visit.mode == 0 ? 0 : visit.test.entities), "missing physics samples: " + worlds);
        if (PLAN.riding)
            require(witnessedRidersVisit == visitIndex, "missing production rider samples");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onPlayerRender(RenderPlayerEvent.Pre event) {
        int id = event.getEntity().getId();
        if (rendering && id >= FIRST_ID && id < FIRST_ID + PLAYERS.size()) {
            visible++;
            if (event.isCanceled())
                replacements++;
        }
    }

    @SubscribeEvent
    public static void render(TickEvent.RenderTickEvent event) {
        if (finished)
            return;
        if (PLAN.witness) {
            if (witness == null)
                return;
            try {
                if (event.phase == TickEvent.Phase.START) {
                    beginFrame(System.nanoTime(), witness.started && !witness.finished);
                    witness.applyPose(event.renderTickTime);
                } else {
                    long elapsed = System.nanoTime() - start;
                    rendering = false;
                    witness.finishDraw(elapsed);
                }
            } catch (Exception failure) {
                finish(Minecraft.getInstance(), failure);
            }
            return;
        }
        if (event.phase == TickEvent.Phase.END) {
            if (rendering)
                renderNanos = System.nanoTime() - start;
            rendering = false;
            return;
        }
        long now = System.nanoTime();
        boolean wanted = collecting > 0;
        if (sampling && wanted && frameVisit == visitIndex && frameView == collecting) {
            FRAMES.add(new Frame(frameVisit, frameView, worlds, now - start, renderNanos, claimedNanos, demandJoinNanos,
                drainJoinNanos, claimedTasks, demandJoins, drainJoins, visible, replacements, STEPS.sum(), RESETS.sum(),
                REJECTED.sum(), passengerSamplingNanos, start));
        }
        frameVisit = visitIndex;
        frameView = collecting;
        sampling = wanted;
        beginFrame(now, wanted);
    }

    private static void beginFrame(long now, boolean wanted) {
        rendering = wanted;
        start = now;
        renderNanos = claimedNanos = demandJoinNanos = drainJoinNanos = passengerSamplingNanos = 0;
        claimedTasks = demandJoins = drainJoins = visible = replacements = 0;
        STEPS.reset();
        RESETS.reset();
        REJECTED.reset();
    }

    public static void beginPassengerSampling() {
        if (rendering)
            passengerSamplingStart = System.nanoTime();
    }

    public static void endPassengerSampling() {
        if (rendering)
            passengerSamplingNanos += System.nanoTime() - passengerSamplingStart;
        if (age < 0 || visitIndex < 0 || witnessedRidersVisit == visitIndex)
            return;
        if (PLAYERS.stream().allMatch(player -> AnimationParallelTicker.passengerTransform(player) != null))
            witnessedRidersVisit = visitIndex;
    }

    public static void runClaimed(FutureTask<?> task) {
        if (!rendering) {
            task.run();
            return;
        }
        long now = System.nanoTime();
        try {
            task.run();
        } finally {
            claimedNanos += System.nanoTime() - now;
            claimedTasks++;
        }
    }

    public static Object joinTask(FutureTask<?> task, boolean forRender)
        throws ExecutionException, InterruptedException {
        if (!rendering || !AnimationParallelTicker.isInvocationActive())
            return task.get();
        long now = System.nanoTime();
        try {
            return task.get();
        } finally {
            long elapsed = System.nanoTime() - now;
            if (forRender) {
                demandJoinNanos += elapsed;
                demandJoins++;
            } else {
                drainJoinNanos += elapsed;
                drainJoins++;
            }
        }
    }

    public static void physicsResult(int mode, int flags) {
        if (mode == NativeModelState.OBSERVE)
            return;
        if ((flags & NativeModelState.PHYSICS_ACCEPTED) == 0
            || (flags >>> NativeModelState.OUTCOME_SHIFT & NativeModelState.OUTCOME_MASK) != NativeModelState.OK) {
            REJECTED.increment();
        } else if (mode == NativeModelState.STEP) {
            STEPS.increment();
        } else if (mode == NativeModelState.RESET) {
            RESETS.increment();
        }
    }

    public static void witnessAnimationEvent(AnimationEvent<?> event) {
        var active = witness;
        if (active == null || active.actor != event.getAnimatableEntity().getEntity()) {
            ACTIVE_WITNESS.remove();
            return;
        }
        if (!active.natural)
            event.renderTicks = (float)(20 * active.time);
        active.sampledYaw = event.getExtraData().lerpBodyRot;
        ACTIVE_WITNESS.set(active);
    }

    public static int witnessFrameRate(int observed, AnimatableEntity<?> owner) {
        var active = witness;
        // A controlled timeline has no real-time headroom to wait for the host's lower visibility tiers.
        return active != null && !active.natural && active.actor == owner.getEntity() ? 120 : observed;
    }

    public static void witnessResult(int mode, int flags, double time) {
        var active = ACTIVE_WITNESS.get();
        if (active == witness && active != null && active.started && mode != NativeModelState.OBSERVE)
            active.accept(mode, flags, time);
    }

    public static void witnessDraw(PhysicsDebugController.View view) {
        var active = witness;
        if (active != null && view != null && view.snapshot() != null && active.pending != null) {
            var sample = active.pending;
            active.pending = null;
            require(active.drawn == null, "multiple accepted samples in one render: " + sample);
            active.drawn = sample;
            var snapshot = captureContacts(view, sample.sample);
            active.completeDraw(snapshot.bodyPairs);
        }
    }

    @SubscribeEvent
    public static void onBodyDraw(RenderLivingEvent.Post<?, ?> event) {
        var active = witness;
        if (active != null && active.started && active.visit.mode == 0 && active.actor == event.getEntity()
            && event.getRenderer() instanceof CustomPlayerRenderer && !active.drew) {
            active.completeDraw(0);
        }
    }

    private record Checkpoint(int sample, double time) {
    }

    private static final class WitnessPlayback {
        private static final double LONG_SPAN = 1.249;
        private final RemotePlayer actor;
        private final Visit visit;
        private final boolean natural;
        private Checkpoint pre;
        private boolean started;
        private boolean finished;
        private boolean drew;
        private int samples;
        private int lastNaturalShot;
        private double time;
        private double previous;
        private float sampledYaw;
        private ClockSample pending;
        private ClockSample drawn;
        private String shot;

        private WitnessPlayback(RemotePlayer actor, Visit visit, Checkpoint pre) {
            this.visit = visit;
            this.actor = actor;
            this.pre = pre;
            this.natural = visit.sequence.equals("natural_10fps");
        }

        private void begin(Minecraft minecraft, PlayerAnimatableCapability owner) {
            if (!natural) {
                // Asset installation can complete between renders. Establish the same zero-time main pose either way.
                applyPose(0);
                owner.beginAsyncUpdate(0);
            }
            if (!visit.test.animation.isEmpty()) {
                require(owner.getAnimation(visit.test.animation) != null,
                    "missing extra animation: " + visit.test.animation);
                owner.playExtraAnimation(visit.test.animation);
            }
            if (visit.mode != 0)
                PhysicsDebugController.enable(owner);
            if (natural)
                minecraft.options.framerateLimit().set(10);
            started = true;
        }

        private void applyPose(float partialTick) {
            drew = false;
            if (started && !finished) {
                time = natural ? (age - 1 + partialTick) / 20.0 : scheduledTime();
            }
            // Equal interpolation endpoints give drawing and solve the same root at this action time.
            float yaw = visit.test.witnessAngle + 180 + 80 * (float)Math.sin(Math.min(time, 2.4) * 2 * Math.PI / 1.2);
            actor.yBodyRotO = actor.yBodyRot = yaw;
            actor.yHeadRotO = actor.yHeadRot = yaw;
            actor.yRotO = yaw;
            actor.setYRot(yaw);
        }

        private double scheduledTime() {
            if (pre == null || samples <= pre.sample)
                return (samples + 1) / 60.0;
            int step = samples - pre.sample;
            double elapsed = switch (visit.sequence) {
                case "near" -> {
                    if (step <= 15)
                        yield step / 60.0;
                    if (step <= 75)
                        yield 0.25 + (LONG_SPAN - 0.25) * (step - 15) / 60;
                    yield LONG_SPAN + (step - 75) / 60.0;
                }
                case "long_step" -> step <= 10 ? step * 0.1 : LONG_SPAN + (step - 11) / 60.0;
                case "reset_025" -> 0.25 + (step - 1) / 60.0;
                default -> throw new IllegalStateException("unknown witness sequence " + visit.sequence);
            };
            return pre.time + elapsed;
        }

        private void accept(int mode, int flags, double acceptedTime) {
            require(pending == null, "multiple accepted main samples before a body draw");
            pending = new ClockSample(visitIndex, age, samples, mode, flags, acceptedTime,
                samples == 0 ? 0 : acceptedTime - previous, natural ? null : time, sampledYaw, null);
            previous = acceptedTime;
        }

        private void completeDraw(int bodyPairs) {
            drew = true;
            if (natural) {
                int slot = age >= 75 ? 3 : age >= 45 ? 2 : age >= 24 ? 1 : 0;
                if (slot > lastNaturalShot) {
                    lastNaturalShot = slot;
                    shot = "natural_" + slot;
                }
            } else {
                if (pre == null && time >= 0.25) {
                    double clockTime = (float)(20 * time) / 20.0;
                    double resetTime = (float)(20 * (time + 0.25)) / 20.0;
                    // Choose a loaded near sample whose float animation clock can represent the exact reset boundary.
                    if ((bodyPairs > 0 || time >= 1.25) && resetTime - clockTime == 0.25) {
                        pre = new Checkpoint(samples, time);
                        CHECKPOINTS.put(visit.test.name, pre);
                    }
                }
                if (pre != null)
                    markCheckpoint(samples - pre.sample);
            }
            samples++;
        }

        private void markCheckpoint(int step) {
            if (step == 0) {
                shot = "pre";
                return;
            }
            if (visit.sequence.equals("reset_025")) {
                if (step == 1)
                    shot = "reset_target";
                if (step == 2) {
                    shot = "reset_resume";
                    finished = true;
                }
            } else {
                boolean near = visit.sequence.equals("near");
                int post = near ? 75 : 11;
                if (near && step == 15)
                    shot = "reset_target";
                if (step == post)
                    shot = "post";
                if (step == post + 15)
                    shot = "recover";
                if (step == post + 90) {
                    shot = "rest";
                    finished = true;
                }
            }
        }

        private void finishDraw(long elapsed) throws IOException {
            require(pending == null, "accepted main sample was not captured after body draw: " + pending);
            long screenshotNanos = 0;
            if (shot != null) {
                long beforeScreenshot = System.nanoTime();
                String name = "visit-%02d-%s.png".formatted(visitIndex, shot);
                try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                    image.writeToFile(OUTPUT.resolve(name));
                }
                screenshotNanos = System.nanoTime() - beforeScreenshot;
                shot = null;
            }
            if (drawn != null) {
                var timing = new RenderTiming(start, elapsed, claimedNanos, demandJoinNanos, drainJoinNanos, drainJoins,
                    screenshotNanos);
                CLOCK_SAMPLES.add(new ClockSample(drawn.visit, drawn.tick, drawn.sample, drawn.mode, drawn.flags,
                    drawn.time, drawn.interval, drawn.actionTime, drawn.yaw, timing));
                drawn = null;
            }
        }
    }

    private static void finish(Minecraft minecraft, Throwable failure) {
        finished = true;
        if (failure != null)
            failure.printStackTrace();
        var gson = new Gson();
        var window = minecraft.getWindow();
        var result = new Result(failure == null, failure == null ? "" : failure.toString(), window.getWidth(),
            window.getHeight(), window.getRefreshRate(), VISITS, ASSETS, FRAMES, CONTACTS, CLOCK_SAMPLES);
        try (var writer = Files.newBufferedWriter(OUTPUT.resolve("samples.json"))) {
            gson.toJson(result, writer);
        } catch (IOException writeFailure) {
            writeFailure.printStackTrace();
        } finally {
            minecraft.stop();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalStateException(message);
    }

    private record Plan(int repetitions, int sampleTicks, List<Scenario> cases, boolean contactCheck, boolean riding,
        boolean witness, List<PairingVisit> pairingVisits, int settleTicks, int warmupTicks) {
    }

    private record PairingVisit(int caseIndex, int mode, int repeat, boolean warmup) {
    }

    private record Scenario(String name, int entities, List<String> models, String texture, String animation,
        float witnessAngle) {
    }

    private record Visit(Scenario test, int repeat, int mode, String model, String sequence) {
    }

    private record Node(String id, String kind, int primitives, int collectionMask, List<Box> boxes) {
    }

    private record Box(List<Float> center, List<Float> size, List<Float> rotation) {
    }

    private record Asset(String model, String modelId, List<Node> nodes) {
    }

    private record Frame(int visit, int view, int worlds, long fullNs, long renderNs, long claimedNs, long demandJoinNs,
        long drainJoinNs, int claimedTasks, int demandJoins, int drainJoins, int visible, int replacements, long steps,
        long resets, long rejected, long passengerSamplingNs, long frameStartNs) {
    }

    private record ContactPair(String first, String second) {
    }

    private record ContactSample(int visit, int tick, int sample, int interpartPairs, int bodyPairs,
        List<ContactPair> pairs, List<PhysicsDebugSnapshot.Node> nodes, boolean sampled, boolean stepped) {
    }

    private record ClockSample(int visit, int tick, int sample, int mode, int flags, double time, double interval,
        Double actionTime, float yaw, RenderTiming timing) {
    }

    private record RenderTiming(long frameStartNs, long renderNs, long claimedNs, long demandJoinNs, long drainJoinNs,
        int drainJoins, long screenshotNs) {
    }

    private record Result(boolean completed, String failure, int width, int height, int refreshRate, List<Visit> visits,
        List<Asset> assets, List<Frame> frames, List<ContactSample> contacts, List<ClockSample> clockSamples) {
    }
}
