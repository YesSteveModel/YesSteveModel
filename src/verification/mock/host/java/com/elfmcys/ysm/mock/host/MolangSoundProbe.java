package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.sound.instance.ICanStopSound;
import com.elfmcys.ysm.expression.aot.TransientExpressionSession;
import com.elfmcys.ysm.expression.host.ModelSoundId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only table observation after real generated code and host commit. */
final class MolangSoundProbe {
    private final AnimatableEntity<?> owner;
    private final Map<ModelSoundId, ICanStopSound> named;
    private final List<ICanStopSound> anonymous;
    private final Map<String, Object> facts = new LinkedHashMap<>();
    private ICanStopSound firstNamed;
    private ICanStopSound anonymousHandle;
    private ICanStopSound replacement;
    private volatile String completed;
    private int step;

    @SuppressWarnings("unchecked")
    MolangSoundProbe(AnimatableEntity<?> owner) throws Exception {
        this.owner = owner;
        var manager = field(owner.getAnimationProcessor(), "globalSoundManager");
        named = (Map<ModelSoundId, ICanStopSound>)field(manager, "instanceMap");
        anonymous = (List<ICanStopSound>)field(manager, "unnamedInstanceList");
    }

    Map<String, Object> poll() {
        if (named.containsKey(ModelSoundId.string("recovery_forbidden"))) {
            throw new IllegalStateException("Repeated watch observation submitted an ACTION sound");
        }
        if (step == 0) {
            submit(
                "ysm.play_sound(-1,'minecraft:block.note_block.harp');ysm.play_sound('recovery_invalid','bad:id:syntax');ysm.play_sound(0,'minecraft:block.note_block.harp',6);ysm.play_sound('0','minecraft:block.note_block.harp',6)");
            step = 1;
            return null;
        }
        if (completed == null)
            return null;
        if (!completed.equals("0.0"))
            throw new IllegalStateException("Sound expression failed: " + completed);
        if (step == 1) {
            firstNamed = named.get(ModelSoundId.string("0"));
            if (firstNamed == null || anonymous.size() != 1)
                throw new IllegalStateException("Sound ID namespaces did not publish exactly two handles");
            if (named.size() != 1)
                throw new IllegalStateException("Invalid sound request entered the playback table");
            anonymousHandle = anonymous.get(0);
            if (!active(firstNamed) || !active(anonymousHandle))
                return null;
            facts.put("namedAndAnonymousActive", true);
            facts.put("exactAnonymousCount", 1);
            submit("ysm.play_sound('0','minecraft:block.note_block.harp',7)");
            step = 2;
        } else if (step == 2) {
            replacement = named.get(ModelSoundId.string("0"));
            if (replacement == firstNamed || !firstNamed.isPlaybackStopped())
                throw new IllegalStateException("Force did not retire the exact prior sound");
            if (!active(replacement))
                return null;
            facts.put("forceReplacedExactHandle", true);
            submit("ysm.stop_sound('0',true)");
            step = 3;
        } else if (step == 3) {
            if (!replacement.isPlaybackStopped() || !active(anonymousHandle))
                throw new IllegalStateException("Named stop affected anonymous playback");
            facts.put("namedStopPreservedAnonymousBackend", true);
            submit("ysm.play_sound('recovery_default','minecraft:block.note_block.harp')");
            step = 4;
        } else if (step == 4) {
            var defaultHandle = named.get(ModelSoundId.string("recovery_default"));
            if (defaultHandle == null || !active(defaultHandle))
                return null;
            facts.put("defaultParametersSubmittedToBackend", true);
            submit("ysm.stop_all_sounds(true)");
            step = 5;
        } else {
            if (!anonymousHandle.isPlaybackStopped() || !named.isEmpty() || !anonymous.isEmpty())
                throw new IllegalStateException("Global stop left a scope handle");
            facts.put("globalStopEmptiedOwnerScope", true);
            facts.put("repeatedWatchActionSuppressed", true);
            facts.put("invalidIdsProducedNoHandle", true);
            facts.put("boundary",
                "Actual Minecraft SoundManager.isActive on returned MinecraftSoundInstance handles; no listening, multi-owner backend or controller-local scope proof.");
            return Map.copyOf(facts);
        }
        return null;
    }

    private void submit(String source) {
        completed = null;
        owner.executeTransient(source + ";0", TransientExpressionSession.Privilege.LOCAL_EXECUTE, false,
            result -> completed = result);
    }

    private static boolean active(ICanStopSound handle) {
        return handle instanceof SoundInstance sound && Minecraft.getInstance().getSoundManager().isActive(sound);
    }

    static Object field(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
