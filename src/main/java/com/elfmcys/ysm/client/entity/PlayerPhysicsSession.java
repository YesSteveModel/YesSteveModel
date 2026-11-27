package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.physics.PlayerTeleportState;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.natives.physics.NativePhysicsWorld;
import com.elfmcys.ysm.natives.render.NativeModelState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/** Owned by one animation installation. The entity execution window serializes every operation. */
final class PlayerPhysicsSession implements AutoCloseable {
    static final int ANIMATION_ONLY = -1;
    static final long NO_SAMPLE_EPOCH = -1;

    private final String owner;
    private final RecoveryGate recovery;
    private @Nullable NativePhysicsWorld world;
    private boolean hasSample;
    private long sampleEpoch;
    private float acceptedFrame = Float.NEGATIVE_INFINITY;
    private double acceptedTime;
    private Vec3 acceptedPosition;
    private Object acceptedLevel;
    private long acceptedTeleport;
    private boolean landingResetPending;

    PlayerPhysicsSession(String owner) {
        this(owner, new RecoveryGate(null, null));
    }

    PlayerPhysicsSession(String owner, RecoveryGate recovery) {
        this.owner = owner;
        this.recovery = recovery;
    }

    static PlayerPhysicsSession open(String owner, RecoveryGate recovery, Supplier<NativePhysicsWorld> factory) {
        var session = new PlayerPhysicsSession(owner, recovery);
        if (!session.isDegraded())
            session.createWorld(factory);
        return session;
    }

    private void createWorld(Supplier<NativePhysicsWorld> factory) {
        world = factory.get();
        if (world == null)
            degrade(Failure.WORLD_CREATE_FAILED, 0);
    }

    void retry(Supplier<NativePhysicsWorld> factory) {
        if (!isDegraded())
            return;
        recovery.failure = null;
        if (world == null)
            createWorld(factory);
    }

    RecoveryGate recoveryGate() {
        return recovery;
    }

    boolean isDegraded() {
        return recovery.failure != null;
    }

    @Nullable
    NativePhysicsWorld world() {
        return world;
    }

    long sampleEpoch() {
        return sampleEpoch;
    }

    boolean hasSample() {
        return hasSample;
    }

    boolean hasCurrentSample(Object level, long teleport) {
        return hasSample && acceptedLevel == level && acceptedTeleport == teleport;
    }

    @Nullable
    String debugFailure() {
        return recovery.failure == null ? null : recovery.failure.name();
    }

    void invalidateIngress(Object level, long teleport) {
        if (hasSample && (acceptedLevel != level || acceptedTeleport != teleport))
            revokeSample();
    }

    void invalidate(Object level, PlayerTeleportState teleport, int entityTick, Vec3 position, double time,
        float frame) {
        invalidateIngress(level, teleport.epoch());
        var suppressing = teleport.suppresses(entityTick);
        var dt = secondsSinceSample(time);
        if (hasSample && (dt < 0
            || dt >= 0.25 || PlayerTeleportState.isLongJump(position.x - acceptedPosition.x,
                position.y - acceptedPosition.y, position.z - acceptedPosition.z)
            || landingResetPending && (!suppressing || frame > acceptedFrame)))
            revokeSample();
    }

    int select(double time, float frame, boolean canonicalTick) {
        if (isDegraded())
            return ANIMATION_ONLY;
        if (!canonicalTick || frame <= acceptedFrame)
            return hasSample ? NativeModelState.OBSERVE : ANIMATION_ONLY;
        if (!hasSample)
            return NativeModelState.RESET;
        return secondsSinceSample(time) > 0 ? NativeModelState.STEP : NativeModelState.OBSERVE;
    }

    double secondsSinceSample(double time) {
        return time - acceptedTime;
    }

    void accept(int mode, int flags, int rawJoltError, Object level, PlayerTeleportState teleport, int entityTick,
        Vec3 position, double time, float frame) {
        if (mode == NativeModelState.OBSERVE)
            return;
        if ((flags & NativeModelState.PHYSICS_ACCEPTED) == 0) {
            degrade(Failure.STEP_REJECTED, rawJoltError);
            return;
        }
        var outcome = flags >>> NativeModelState.OUTCOME_SHIFT & NativeModelState.OUTCOME_MASK;
        if (outcome != NativeModelState.OK) {
            degrade(switch (outcome) {
                case NativeModelState.CAPACITY_EXCEEDED -> Failure.CAPACITY_EXCEEDED;
                case NativeModelState.SCRATCH_EXHAUSTED -> Failure.SCRATCH_EXHAUSTED;
                case NativeModelState.NON_FINITE_STATE -> Failure.NON_FINITE_STATE;
                default -> throw new IllegalStateException("Unknown native physics outcome: " + outcome);
            }, rawJoltError);
            return;
        }

        acceptedLevel = level;
        acceptedTeleport = teleport.epoch();
        acceptedTime = time;
        acceptedPosition = position;
        acceptedFrame = frame;
        landingResetPending = teleport.suppresses(entityTick);
        hasSample = true;
        sampleEpoch++;
    }

    private void revokeSample() {
        if (hasSample) {
            hasSample = false;
            sampleEpoch++;
        }
    }

    private void degrade(Failure cause, int rawJoltError) {
        recovery.failure = cause;
        revokeSample();
        YesSteveModel.LOGGER.warn("Player physics degraded for {}: {} (Jolt error {})", owner, cause, rawJoltError);
    }

    @Override
    public void close() {
        var retired = world;
        world = null;
        if (retired != null)
            retired.close();
    }

    private enum Failure {
        WORLD_CREATE_FAILED, STEP_REJECTED, CAPACITY_EXCEEDED, SCRATCH_EXHAUSTED, NON_FINITE_STATE
    }

    /** Carries only failure eligibility across an administrative teardown of the same world's model input. */
    static final class RecoveryGate {
        private final @Nullable ModelRenderTarget.StateIdentity inputs;
        private final Object level;
        private @Nullable Failure failure;

        RecoveryGate(@Nullable ModelRenderTarget.StateIdentity inputs, Object level) {
            this.inputs = inputs;
            this.level = level;
        }

        boolean blocks(@Nullable ModelRenderTarget.StateIdentity next, Object nextLevel) {
            return failure != null && level == nextLevel && inputs != null && inputs.hasSameInputs(next);
        }
    }
}
