package com.elfmcys.ysm.client.sound.instance;

import com.elfmcys.ysm.expression.host.ModelSoundId;
import com.elfmcys.ysm.api.client.audio.AudioBackend;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Exact author-scope registry for pending and submitted sounds. */
public class SoundInstanceManager {
    private final Object2ReferenceOpenHashMap<ModelSoundId, ICanStopSound> instanceMap =
        new Object2ReferenceOpenHashMap<>(0);
    private final ReferenceArrayList<ICanStopSound> unnamedInstanceList = new ReferenceArrayList<>(0);

    public boolean playSound(AnimatableEntity<?> animatable, ModelSoundId id, String soundName, boolean force,
        @Nullable Consumer<SoundControl> configure) {
        if (!id.playable()) {
            return false;
        }

        final SoundControl control = new SoundControl(animatable.getEntity());
        if (configure != null) {
            configure.accept(control);
        }

        final AudioPlaybackFactory modelFactory;
        final MinecraftSoundInstance namespaced;
        if (soundName.contains(":")) {
            var soundId = ResourceLocation.tryParse(soundName);
            if (soundId == null) {
                return false;
            }
            modelFactory = null;
            namespaced =
                new MinecraftSoundInstance(SoundEvent.createVariableRangeEvent(soundId), animatable.getEntity());
            control.apply(namespaced);
        } else {
            modelFactory = animatable.getSoundPlayback(soundName).orElse(null);
            namespaced = null;
            if (modelFactory == null) {
                return false;
            }
        }

        return startResolved(id, force, () -> {
            if (modelFactory != null) {
                return modelFactory.play(control);
            }
            Minecraft.getInstance().execute(() -> {
                var sounds = Minecraft.getInstance().getSoundManager();
                sounds.play(namespaced);
                if (!sounds.isActive(namespaced)) {
                    namespaced.setStopped();
                }
            });
            return namespaced;
        });
    }

    boolean startResolved(ModelSoundId id, boolean force, Supplier<@Nullable ICanStopSound> starter) {
        if (!isUnnamed(id)) {
            var old = instanceMap.get(id);
            if (old != null && !old.isPlaybackStopped()) {
                if (!force) {
                    return false;
                }
                instanceMap.remove(id, old);
                old.setStopped(AudioBackend.CancelReason.FORCE_REPLACED);
            }
        }

        var instance = starter.get();
        if (instance == null || instance.isPlaybackStopped()) {
            return false;
        }

        if (isUnnamed(id)) {
            unnamedInstanceList.add(instance);
        } else {
            instanceMap.put(id, instance);
        }
        return true;
    }

    public boolean stopPlayingSound(ModelSoundId id) {
        if (!id.playable() || isUnnamed(id)) {
            return false;
        }
        var instance = instanceMap.remove(id);
        if (instance == null) {
            return false;
        }
        instance.setStopped(AudioBackend.CancelReason.AUTHOR_STOP);
        return true;
    }

    public void stopAllPlayingSounds() {
        instanceMap.values().forEach(instance -> instance.setStopped(AudioBackend.CancelReason.SCOPE_RETIRED));
        unnamedInstanceList.forEach(instance -> instance.setStopped(AudioBackend.CancelReason.SCOPE_RETIRED));
        instanceMap.clear();
        unnamedInstanceList.clear();
    }

    public void trim() {
        var iter = instanceMap.object2ReferenceEntrySet().fastIterator();
        while (iter.hasNext()) {
            if (iter.next().getValue().isPlaybackStopped()) {
                iter.remove();
            }
        }
        unnamedInstanceList.removeIf(ICanStopSound::isPlaybackStopped);
    }

    private static boolean isUnnamed(ModelSoundId id) {
        return id instanceof ModelSoundId.Numeric numeric && numeric.value() == 0;
    }
}
