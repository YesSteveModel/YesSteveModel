package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.network.AnimationStateProto;
import com.elfmcys.ysm.proto.network.MolangVariableProto;
import com.elfmcys.ysm.proto.network.PlayerStateReportProto;
import com.elfmcys.ysm.proto.network.RoamingStateProto;
import com.elfmcys.ysm.proto.network.StateWriteMode;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns client observations and consumes each eligible best-effort report opportunity before sending.
 *
 * <p><b>Execution:</b> The client report owner serializes session, authority, animation and observe calls.
 * The caller admits server authority only after applying its self FULL roaming baseline.
 *
 * <p><b>Invariant:</b> A produced report advances local observations even if a later send fails.
 * Transport cannot restore or replay that consumed opportunity. The server separately accepts a real FULL
 * baseline only after successful fact application; this planner does not acknowledge that server transition.
 *
 * <p><b>Seam:</b> [best-effort report observations](docs/architecture/network/player-state.md#表现报告与真实基线).
 */
public final class PlayerStateReportPlanner {
    private static final int MAX_ANIMATION_ID_BYTES = 256;

    /** An unavailable sample cannot consume an opportunity that requires roaming; absent intentionally omits it. */
    public record RoamingSample(Integer modelKey, Map<String, Float> values, boolean available) {

        private static final RoamingSample ABSENT = new RoamingSample(null, Map.of());
        private static final RoamingSample UNAVAILABLE = new RoamingSample(null, Map.of(), false);

        public RoamingSample(Integer modelKey, Map<String, Float> values) {
            this(modelKey, values, true);
        }

        public RoamingSample {
            values = Map.copyOf(values);
        }

        public static RoamingSample absent() {
            return ABSENT;
        }

        public static RoamingSample unavailable() {
            return UNAVAILABLE;
        }

        public static RoamingSample unavailable(int modelKey) {
            return new RoamingSample(modelKey, Map.of(), false);
        }

        public boolean present() {
            return modelKey != null;
        }
    }

    private PlayerStateReportPolicy policy;
    private boolean authorityKnown;
    private Hash256 authoritativeModelHash;
    private Integer authoritativeRoamingKey;
    private boolean needsFull;
    private long lastOpportunityNanos;
    private long lastFullOpportunityNanos;
    private String animationId = "";
    private String observedAnimationId;
    private int observedRoamingKey;
    private Map<String, Float> observedRoaming = Map.of();

    public void beginSession(PlayerStateReportPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        authorityKnown = false;
        authoritativeModelHash = null;
        authoritativeRoamingKey = null;
        needsFull = false;
        animationId = "";
        resetObservations();
    }

    public void endSession() {
        policy = null;
        authorityKnown = false;
        authoritativeModelHash = null;
        authoritativeRoamingKey = null;
        needsFull = false;
        animationId = "";
        resetObservations();
    }

    public void restartReportLifetime() {
        animationId = "";
        if (authorityKnown) {
            needsFull = true;
            resetObservations();
        }
    }

    public void setAnimation(String animationId) {
        this.animationId = animationId == null || animationId.isBlank()
            || animationId.getBytes(StandardCharsets.UTF_8).length > MAX_ANIMATION_ID_BYTES ? "" : animationId;
    }

    /** Accepts self FULL authority after its roaming baseline has been applied. */
    public boolean acceptAuthority(Hash256 modelHash, Integer roamingKey) {
        if (policy == null) {
            return false;
        }
        var changed = authorityKnown && (!Objects.equals(authoritativeModelHash, modelHash)
            || policy.requestedSections().contains(PlayerStateSection.ROAMING)
                && !Objects.equals(authoritativeRoamingKey, roamingKey));
        if (changed) {
            animationId = "";
        }
        authoritativeModelHash = modelHash;
        authoritativeRoamingKey = roamingKey;
        if (!authorityKnown || changed) {
            authorityKnown = true;
            needsFull = true;
            resetObservations();
        }
        return true;
    }

    public Integer authoritativeRoamingKey() {
        return authoritativeRoamingKey;
    }

    /**
     * Samples one report opportunity and commits its observations before returning a report.
     *
     * @param nowNanos monotonic time in nanoseconds for report intervals
     * @param entityId the local player's entity ID for the current report lifetime
     * @param roaming the non-null sample; unavailable defers a required roaming opportunity, absent intentionally omits it
     * @return empty when ineligible, rate-limited or unchanged; otherwise a caller-owned report for one best-effort send
     */
    public Optional<PlayerStateReportProto> observe(long nowNanos, int entityId, RoamingSample roaming) {
        if (policy == null || !authorityKnown
            || policy.requestedSections().contains(PlayerStateSection.ROAMING) && !roaming.available()) {
            return Optional.empty();
        }
        var minInterval = policy.minDeltaIntervalMillis() * 1_000_000L;
        if (lastOpportunityNanos != 0 && nowNanos - lastOpportunityNanos < minInterval) {
            return Optional.empty();
        }
        var fullInterval = policy.fullSnapshotIntervalMillis() * 1_000_000L;
        var full = needsFull || fullInterval != 0 && nowNanos - lastFullOpportunityNanos >= fullInterval;
        var animationChanged = policy.requestedSections().contains(PlayerStateSection.ANIMATION)
            && (full || !animationId.equals(observedAnimationId));
        var sampled =
            policy.requestedSections().contains(PlayerStateSection.ROAMING) && roaming.present() ? roaming : null;
        var currentRoaming = sampled == null ? Map.<String, Float>of() : sampled.values();
        var roamingKeyChanged = sampled != null && sampled.modelKey() != observedRoamingKey;
        var roamingSection = sampled == null ? null
            : roamingDelta(sampled.modelKey(), currentRoaming, full || roamingKeyChanged ? Map.of() : observedRoaming);
        if (!full && !animationChanged
            && (roamingSection == null || !roamingKeyChanged && roamingSection.variables().isEmpty())) {
            return Optional.empty();
        }

        var report = PlayerStateReportProto.newBuilder().setSubject(EntityRefEncoder.encode(entityId))
            .setMode(full ? StateWriteMode.FULL : StateWriteMode.DELTA);
        if (animationChanged) {
            report.setAnimation(animationId.isEmpty() ? AnimationStateProto.newBuilder().setStopped(true).build()
                : AnimationStateProto.newBuilder().setAnimationId(animationId).build());
        }
        if (roamingSection != null && (full || roamingKeyChanged || !roamingSection.variables().isEmpty())) {
            report.setRoaming(roamingSection);
        }

        needsFull = false;
        lastOpportunityNanos = nowNanos;
        observedAnimationId = animationId;
        observedRoamingKey = sampled == null ? 0 : sampled.modelKey();
        observedRoaming = currentRoaming;
        if (full) {
            lastFullOpportunityNanos = nowNanos;
        }
        return Optional.of(report.build());
    }

    private void resetObservations() {
        lastOpportunityNanos = 0;
        lastFullOpportunityNanos = 0;
        observedAnimationId = null;
        observedRoamingKey = 0;
        observedRoaming = Map.of();
    }

    private static RoamingStateProto roamingDelta(int modelKey, Map<String, Float> current,
        Map<String, Float> previous) {
        var result = RoamingStateProto.newBuilder().setModelKey(modelKey);
        current.forEach((name, value) -> {
            if (!value.equals(previous.get(name))) {
                result.addVariables(MolangVariableProto.newBuilder().setName(name).setValue(value).build());
            }
        });
        return result.build();
    }
}
