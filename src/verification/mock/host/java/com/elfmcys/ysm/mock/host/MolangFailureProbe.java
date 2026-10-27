package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.aot.TransientExpressionSession;
import com.elfmcys.ysm.expression.host.ModelSoundId;
import com.elfmcys.ysm.model.service.ClientModelService;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

import java.util.LinkedHashMap;
import java.util.Map;

/** Observes the real render-loop failure route, not a synthetic owner or lease. */
final class MolangFailureProbe {
    private final Minecraft minecraft;
    private final String kind;
    private AnimatableEntity<?> owner;
    private Object target;
    private Object requested;
    private long generation;
    private Map<String, Object> before;
    private volatile String result;
    private int settled;
    private int readinessTicks;

    MolangFailureProbe(Minecraft minecraft, String kind) {
        this.minecraft = minecraft;
        if (!kind.equals("record") && !kind.equals("array"))
            throw new IllegalArgumentException(kind);
        this.kind = kind;
    }

    Map<String, ?> poll() throws Exception {
        if (minecraft.player == null || minecraft.level == null)
            return null;
        if (owner == null) {
            var candidate = minecraft.player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
                .orElseThrow(() -> new IllegalStateException("Player capability unavailable"));
            var selected = ClientModelService.instance().resolvePath("molang/fault").orElse(null);
            if (selected == null || !selected.equals(candidate.getModelHash())
                || !candidate.hasMatchingInstallation()) {
                if (++readinessTicks > 400)
                    throw new IllegalStateException("World model not ready: expected=" + selected + ", actual="
                        + candidate.getModelHash() + ", matching=" + candidate.hasMatchingInstallation() + ", target="
                        + candidate.getModelRenderTarget());
                return null;
            }
            minecraft.options.pauseOnLostFocus = false;
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            minecraft.setScreen(null);
            owner = candidate;
            target = owner.getModelRenderTarget();
            requested = owner.getModelHash();
            generation = owner.getExpressionRuntime().generation();
            before = protectedState();
            var source = kind.equals("record") ? "ysm_mock_host.broken_record().x" : "ysm_mock_host.broken_array()[0]";
            owner.executeTransient(source, TransientExpressionSession.Privilege.LOCAL_EXECUTE, false,
                value -> result = value);
            return null;
        }
        if (result == null)
            return null;
        if (!result.startsWith("Error:"))
            throw new IllegalStateException("Fault was not reported: " + result);
        if (owner.getInstallationRelation() != AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE
            || owner.getModelRenderTarget() == target || owner.getModelRenderTarget() == null
            || owner.getExpressionRuntime().generation() <= generation) {
            throw new IllegalStateException("Severe generated fault did not install a new fallback root: " + result);
        }
        var named = (Map<?, ?>)MolangSoundProbe
            .field(MolangSoundProbe.field(owner.getAnimationProcessor(), "globalSoundManager"), "instanceMap");
        if (named.containsKey(ModelSoundId.string("recovery_discarded")))
            throw new IllegalStateException("Failed transaction submitted sound");
        if (!requested.equals(owner.getModelHash()) || !before.equals(protectedState()))
            throw new IllegalStateException("Local fallback mutated protected player state or authoritative selection");
        if (++settled < 60)
            return null;
        var calls = kind.equals("record") ? MolangFaultProvider.recordCalls : MolangFaultProvider.arrayCalls;
        if (calls != 1)
            throw new IllegalStateException("Unexpected provider call count: " + calls);
        var facts = new LinkedHashMap<String, Object>();
        facts.put("kind", kind);
        facts.put("result", result);
        facts.put("providerCalls", calls);
        facts.put("generationBefore", generation);
        facts.put("generationAfter", owner.getExpressionRuntime().generation());
        facts.put("requestedModel", requested.toString());
        facts.put("owner", Integer.toUnsignedString(System.identityHashCode(owner)));
        facts.put("fallbackInstalled", true);
        facts.put("failedTransactionSoundAbsent", true);
        facts.put("samePublicationNotRetriedFor60Ticks", true);
        facts.put("protectedStateBefore", before);
        facts.put("protectedStateAfter", protectedState());
        facts.put("boundary",
            "Actual shared-player world render owner and generated record/array non-null failure; no synthetic lease or failure handler invocation. Protected projection excludes natural position/time counters; not exhaustive entity/default-fault/reload/disk acceptance.");
        return facts;
    }

    private Map<String, Object> protectedState() {
        var player = minecraft.player;
        var tag = new CompoundTag();
        player.saveWithoutId(tag);
        return Map.of("health", player.getHealth(), "food", player.getFoodData().getFoodLevel(), "inventory",
            tag.get("Inventory").toString(), "selectedSlot", player.getInventory().selected, "abilities",
            tag.get("abilities").toString(), "score", player.getScore(), "effects",
            player.getActiveEffects().toString(), "world", minecraft.level.dimension().location().toString());
    }
}
