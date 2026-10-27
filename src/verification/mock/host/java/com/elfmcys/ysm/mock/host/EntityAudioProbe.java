package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.sound.instance.AudioHost;
import com.elfmcys.ysm.client.sound.instance.ICanStopSound;
import com.elfmcys.ysm.client.sound.instance.SoundControl;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Independent model audio must outlive a consumer pause and honor its own author stop. */
final class EntityAudioProbe {
    private final Minecraft minecraft;
    private PlayerAnimatableCapability owner;
    private ICanStopSound playback;
    private AudioHost.HostPlayback host;
    private EntityUseProbe use;
    private int ticks;
    private int step;

    EntityAudioProbe(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    Map<String, ?> poll() throws Exception {
        if (step == 0) {
            var player = minecraft.level.players().stream().filter(p -> p.getScoreboardName().equals("YsmHostB"))
                .findFirst().orElseThrow();
            owner =
                player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElseThrow(IllegalStateException::new);
            var control = new SoundControl(player);
            control.setLooping(true);
            control.setConfiguredVolume(0.02f);
            playback = owner.getSoundPlayback("minuet").orElseThrow().play(control);
            if (playback == null)
                throw new IllegalStateException("Audio admission rejected the fixture");
            step = 1;
            return null;
        }
        if (step == 1) {
            var field = playback.getClass().getDeclaredField("hostPlayback");
            field.setAccessible(true);
            host = (AudioHost.HostPlayback)field.get(playback);
            if (host == null || !host.active()) {
                if (playback.isPlaybackStopped())
                    throw new IllegalStateException("Audio ended before host acceptance");
                return null;
            }
            use = EntityUseProbe.watch(owner);
            owner.setDisabled(true);
            step = 2;
            return null;
        }
        if (step == 2) {
            if (++ticks < 10)
                return null;
            if (!host.active() || playback.isPlaybackStopped() || owner.hasInstalledModel() || owner.hasUnfinishedWork()) {
                throw new IllegalStateException("Consumer pause changed the independently accepted audio");
            }
            playback.setStopped();
            step = 3;
            return null;
        }
        if (!playback.isPlaybackStopped() || host.active())
            return null;
        owner.setDisabled(false);
        return Map.of("backend", "Minecraft SoundManager", "acceptedBeforePause", true, "activeAfterConsumerRelease",
            true, "stoppedByAuthor", true, "consumerLease", use.snapshot(), "pauseObservationTicks", ticks);
    }
}
