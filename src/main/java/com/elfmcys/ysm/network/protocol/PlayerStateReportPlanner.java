package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.proto.network.SelectionStateProto;
import com.elfmcys.ysm.proto.network.SelectionStateSyncProto;
import dev.semaweave.RoamingPayload;
import java.util.Optional;

/** Consumes each changed observation before its one best-effort send. */
public final class PlayerStateReportPlanner {
    private PlayerStateReportPolicy policy;
    private RoamingDictionary dictionary;
    private long intentId;
    private long lastOpportunityNanos;
    private float[] observed = new float[0];
    private String animation = "";
    private String observedAnimation = "";

    public void beginSession(PlayerStateReportPolicy value) {
        endSession();
        policy = value;
    }

    public void endSession() {
        policy = null;
        dictionary = null;
        intentId = 0;
        lastOpportunityNanos = 0;
        observed = new float[0];
        animation = "";
        observedAnimation = "";
    }

    public void selectionCommitted(long intent, RoamingDictionary names, RoamingPayload initial) {
        if (intent <= 0)
            throw new IllegalArgumentException("Selection intent must be positive");
        var values = names.encode(initial);
        dictionary = names;
        intentId = intent;
        lastOpportunityNanos = 0;
        observed = new float[names.size()];
        remember(values);
        animation = "";
        observedAnimation = "";
    }

    public void setAnimation(String value) {
        if (value == null || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256)
            throw new IllegalArgumentException("Invalid animation name");
        animation = value;
    }

    public Optional<SelectionStateSyncProto> observe(long now, int entityId, RoamingPayload sample) {
        if (policy == null || dictionary == null || intentId == 0
            || lastOpportunityNanos != 0 && now - lastOpportunityNanos < policy.minDeltaIntervalMillis() * 1_000_000L)
            return Optional.empty();
        var state = SelectionStateProto.newBuilder();
        boolean changed = false;
        if (policy.requestedSections().contains(PlayerStateSection.ANIMATION) && !animation.equals(observedAnimation)) {
            state.setAnimation(SelectionCodec.animation(animation));
            changed = true;
        }
        if (sample != null && policy.requestedSections().contains(PlayerStateSection.ROAMING)) {
            var values = dictionary.encode(sample);
            int count = 0;
            for (int i = 0; i < values.ids().length; i++) {
                int id = values.ids()[i];
                if (Float.floatToRawIntBits(observed[id]) != Float.floatToRawIntBits(values.values()[i]))
                    count++;
            }
            if (count != 0) {
                var ids = new short[count];
                var floats = new float[count];
                int next = 0;
                for (int i = 0; i < values.ids().length; i++) {
                    int id = values.ids()[i];
                    if (Float.floatToRawIntBits(observed[id]) != Float.floatToRawIntBits(values.values()[i])) {
                        ids[next] = (short)id;
                        floats[next++] = values.values()[i];
                    }
                }
                state.setRoaming(SelectionCodec.write(new RoamingValues(ids, floats)));
                changed = true;
            }
            remember(values);
        }
        if (!changed)
            return Optional.empty();
        lastOpportunityNanos = now;
        observedAnimation = animation;
        return Optional.of(SelectionStateSyncProto.newBuilder().setEntityId(entityId).setIntentId(intentId)
            .setState(state.build()).build());
    }

    private void remember(RoamingValues values) {
        for (int i = 0; i < values.ids().length; i++) {
            int id = values.ids()[i];
            observed[id] = values.values()[i];
        }
    }
}
