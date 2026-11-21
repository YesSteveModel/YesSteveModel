package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.network.protocol.*;
import com.elfmcys.ysm.proto.network.*;
import com.elfmcys.ysm.util.StringPool;
import dev.semaweave.*;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.*;

/** Historical measurement wiring. Production classes and jars remain at the pinned baseline. */
public final class RoamingNetworkAdapter {
    private final CompiledRoamingState consumer;
    private final ModelInfoCapability accumulated = new ModelInfoCapability();

    public RoamingNetworkAdapter(String[] names, CompiledRoamingState consumer) {
        this.consumer = consumer;
    }

    public Object createRelation() {
        return consumer.fields();
    }

    public Object map(RoamingPayload payload) {
        return RoamingPayloads.toNetwork(payload);
    }

    public void apply(Object payload, boolean full) {
        var values = (Int2FloatMap)payload;
        var named = new Object2FloatOpenHashMap<String>(values.size());
        for (var item : values.int2FloatEntrySet())
            named.put(StringPool.getString(item.getIntKey()), item.getFloatValue());
        accumulated.applyClientRoaming(named, full);
        var batch = RoamingPayloads.fromNetwork(values);
        if (full)
            consumer.applyFull(batch);
        else
            consumer.applyPatch(batch);
    }

    private RoamingStateProto roaming(Object payload) {
        var roaming = RoamingStateProto.newBuilder().setModelKey("benchmark/model");
        for (var item : ((Int2FloatMap)payload).int2FloatEntrySet())
            roaming.addVariables(MolangVariableProto.newBuilder().setName(StringPool.getString(item.getIntKey()))
                .setValue(item.getFloatValue()).build());
        return roaming.build();
    }

    public byte[] encode(Object payload, String opportunity) {
        var full = opportunity.equals("full") || opportunity.equals("selection") || opportunity.equals("track");
        var packet = PlayerStateUpdateProto.newBuilder().setSubject(EntityRefProto.newBuilder().setEntityId(7).build())
            .setMode(full ? StateWriteMode.FULL : StateWriteMode.DELTA).setRoaming(roaming(payload));
        if (full)
            packet
                .setModel(ModelSelectionStateProto.newBuilder()
                    .setModel(ModelReferenceProto.newBuilder().setModelId("benchmark/model").build())
                    .setTextureId("skin").setSelectionWindow(129).setIntentId(129).build())
                .setAnimation(AnimationStateProto.newBuilder().setStopped(true).build())
                .setGameplay(GameplayStateProto.newBuilder().setHealth(20).setMaxHealth(20).build())
                .setEffects(EffectStateSetProto.newBuilder().build());
        return packet.build().toByteArray();
    }

    public void decodeApply(byte[] bytes, String opportunity) throws Exception {
        var packet = PlayerStateUpdateProto.parseFrom(bytes);
        if (!PlayerStateValidator.validUpdate(packet))
            throw new IllegalStateException("Invalid historical state");
        var values = new Int2FloatOpenHashMap(packet.roamingUnsafe().variables().size());
        for (var item : packet.roamingUnsafe().variables())
            values.put(StringPool.computeIfAbsent(item.name()), item.value_());
        apply(values, packet.mode() == StateWriteMode.FULL);
    }

    public int protocolId(String opportunity) {
        return ProtocolMessages.PLAYER_STATE_UPDATE_ID;
    }

    public int roamingBytes(Object payload) {
        return roaming(payload).getSerializedSize();
    }

    public Map<String, Float> named(RoamingPayload payload) {
        return new TreeMap<>(payload.values());
    }

    public void primeDiagnostics(byte[] bytes, String opportunity) {}

    public void diagnostics() {}
}
