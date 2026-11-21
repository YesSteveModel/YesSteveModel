package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.network.NetworkDiagnostics;
import com.elfmcys.ysm.network.protocol.*;
import com.elfmcys.ysm.proto.network.*;
import dev.semaweave.*;
import java.util.*;
import us.hebi.quickbuf.ProtoMessage;

/** Candidate-only adapter; the historical adapter is compiled in its isolated source tree. */
public final class RoamingNetworkAdapter {
    private final String[] names;
    private final CompiledRoamingState consumer;
    private final RoamingDictionary dictionary;
    private final RoamingDictionary.Mapping mapping;
    private final RoamingProjection projection;
    private ProtoMessage<?> diagnostic;

    public RoamingNetworkAdapter(String[] names, CompiledRoamingState consumer) {
        this.names = names;
        this.consumer = consumer;
        dictionary = RoamingDictionary.declarations(Arrays.asList(names));
        mapping = dictionary.mapTo(consumer.fieldNames());
        projection = new RoamingProjection(dictionary, new RoamingValues(new short[0], new float[0]));
    }

    public Object createRelation() {
        var next = RoamingDictionary.declarations(Arrays.asList(names));
        return List.of(next, next.mapTo(consumer.fieldNames()));
    }

    public Object map(RoamingPayload payload) {
        return dictionary.encode(payload);
    }

    public void apply(Object payload, boolean full) {
        var values = (RoamingValues)payload;
        projection.apply(values, full);
        var local = mapping.decode(values);
        if (full)
            consumer.applyFull(local);
        else
            consumer.applyPatch(local);
    }

    public byte[] encode(Object payload, String opportunity) {
        var roaming = SelectionCodec.write((RoamingValues)payload);
        var state = SelectionStateProto.newBuilder().setRoaming(roaming);
        if (opportunity.equals("selection") || opportunity.equals("track")) {
            state.setTextureId("skin").setAnimation(SelectionCodec.animation(""));
            var selection = SelectionProto.newBuilder().setEntityId(7).setIntentId(129)
                .setModel(ModelReferenceProto.newBuilder().setModelId("benchmark/model").build())
                .addAllRoamingNames(Arrays.asList(dictionary.names())).setState(state.build()).build();
            if (opportunity.equals("track"))
                return EntityTrackProto.newBuilder().setSelection(selection)
                    .setState(EntityStateProto.newBuilder()
                        .setGameplay(GameplayStateProto.newBuilder().setHealth(20).setMaxHealth(20).build())
                        .setEffects(EffectStateSetProto.newBuilder().build()).build())
                    .setFlags(PlayerFlagsStateProto.newBuilder().setDisabled(false).build()).build().toByteArray();
            return SelectionChangeProto.newBuilder().setSelection(selection).build().toByteArray();
        }
        return SelectionStateSyncProto.newBuilder().setEntityId(7).setIntentId(129).setState(state.build()).build()
            .toByteArray();
    }

    public void decodeApply(byte[] bytes, String opportunity) throws Exception {
        if (opportunity.equals("selection") || opportunity.equals("track")) {
            var selection = opportunity.equals("selection") ? SelectionChangeProto.parseFrom(bytes).selectionUnsafe()
                : EntityTrackProto.parseFrom(bytes).selectionUnsafe();
            var received = SelectionCodec.read(selection, this);
            // A new selection establishes its own relation; stable deltas reuse the Trial relation.
            consumer
                .applyFull(received.dictionary().mapTo(consumer.fieldNames()).decode(received.roaming().snapshot()));
        } else {
            var packet = SelectionStateSyncProto.parseFrom(bytes);
            if (!PlayerStateValidator.validSelectionStateShape(packet.state(), false))
                throw new IllegalStateException("Invalid delta");
            apply(SelectionCodec.read(packet.state().roamingUnsafe(), dictionary), false);
        }
    }

    public int protocolId(String opportunity) {
        return opportunity.equals("selection") ? ProtocolMessages.SELECTION_CHANGE_ID
            : opportunity.equals("track") ? ProtocolMessages.ENTITY_TRACK_ID : ProtocolMessages.SELECTION_STATE_SYNC_ID;
    }

    public int roamingBytes(Object value) {
        return SelectionCodec.write((RoamingValues)value).getSerializedSize();
    }

    public Map<String, Float> named(RoamingPayload payload) {
        var result = new TreeMap<String, Float>();
        for (int i = 0; i < payload.fields().length; i++)
            result.put(names[payload.fields()[i]], payload.values()[i]);
        return result;
    }

    public void primeDiagnostics(byte[] bytes, String opportunity) throws Exception {
        diagnostic =
            opportunity.equals("selection") ? SelectionChangeProto.parseFrom(bytes) : opportunity.equals("track")
                ? EntityTrackProto.parseFrom(bytes) : SelectionStateSyncProto.parseFrom(bytes);
    }

    public void diagnostics() {
        NetworkDiagnostics.message("benchmark", MessageDirection.SERVER_TO_CLIENT, diagnostic, "none");
    }
}
