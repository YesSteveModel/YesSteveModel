package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.capability.CommittedSelection;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.proto.network.*;

/** Establishes the complete external selection shape before domain owners mutate state. */
public final class SelectionCodec {
    private SelectionCodec() {}

    public static RoamingValues read(RoamingStateProto state) {
        return read(state, ProtocolLimits.MAX_ROAMING_VARIABLES);
    }

    public static RoamingValues read(RoamingStateProto state, RoamingDictionary dictionary) {
        return read(state, dictionary.size());
    }

    private static RoamingValues read(RoamingStateProto state, int fieldCount) {
        if (state.ids().size() > ProtocolLimits.MAX_ROAMING_VARIABLES || state.ids().size() != state.values_().size())
            throw new IllegalArgumentException("Invalid roaming array shape");
        long seen = 0;
        for (int i = 0; i < state.ids().size(); i++) {
            int id = state.ids().getInt(i);
            if (id < 0 || id >= fieldCount)
                throw new IllegalArgumentException("Roaming uint32 ID is outside the wire range");
            if (!Float.isFinite(state.values_().getFloat(i)))
                throw new IllegalArgumentException("Non-finite roaming value");
            long bit = 1L << id;
            if ((seen & bit) != 0)
                throw new IllegalArgumentException("Duplicate roaming ID");
            seen |= bit;
        }
        var ids = new short[state.ids().size()];
        for (int i = 0; i < ids.length; i++)
            ids[i] = (short)state.ids().getInt(i);
        return new RoamingValues(ids, state.values_().toFloatArray());
    }

    public static RoamingStateProto write(RoamingValues value) {
        var result = RoamingStateProto.newBuilder();
        for (short id : value.ids())
            result.addIds(id);
        result.addValues(value.values());
        return result.build();
    }

    public static CommittedSelection read(SelectionProto value, Object producer) {
        if (value.entityId() < 0 || value.intentId() < 0 || !value.hasModel() || !value.hasState()
            || !ModelReferenceCodec.valid(value.modelUnsafe())
            || !PlayerStateValidator.validSelectionStateShape(value.stateUnsafe(), true))
            throw new IllegalArgumentException("Invalid complete selection");
        var dictionary = new RoamingDictionary(value.roamingNames().toArray(String[]::new));
        var id = ModelReferenceCodec.read(value.modelUnsafe());
        var state = value.stateUnsafe();
        return new CommittedSelection(id == null ? PublishedModelId.INTRINSIC_DEFAULT : PublishedModelId.model(id),
            value.intentId(), value.intentId() == 0 ? null : producer, dictionary,
            read(state.roamingUnsafe(), dictionary), state.textureIdUnsafe(),
            state.animationUnsafe().hasStopped() ? "" : state.animationUnsafe().animationId());
    }

    public static SelectionProto write(int entityId, CommittedSelection selection) {
        var state = SelectionStateProto.newBuilder().setTextureId(selection.texture())
            .setAnimation(animation(selection.animation())).setRoaming(write(selection.roaming().snapshot())).build();
        return SelectionProto.newBuilder().setEntityId(entityId).setModel(ModelReferenceCodec.create(selection.model()))
            .setIntentId(selection.intentId())
            .addAllRoamingNames(java.util.Arrays.asList(selection.dictionary().names())).setState(state).build();
    }

    public static AnimationStateProto animation(String value) {
        return (value.isEmpty() ? AnimationStateProto.newBuilder().setStopped(true)
            : AnimationStateProto.newBuilder().setAnimationId(value)).build();
    }

    /** The ingress validates metadata shape; roaming is decoded against the current owner before any mutation. */
    public static void apply(CommittedSelection selection, SelectionStateProto delta) {
        var roaming = delta.hasRoaming() ? read(delta.roamingUnsafe(), selection.dictionary()) : null;
        if (roaming != null)
            selection.roaming().apply(roaming, false);
        if (delta.hasTextureId())
            selection.texture(delta.textureIdUnsafe());
        if (delta.hasAnimation())
            selection.animation(delta.animationUnsafe().hasStopped() ? "" : delta.animationUnsafe().animationId());
    }
}
