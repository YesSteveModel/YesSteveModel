package com.elfmcys.ysm.client.sound.instance;

import com.elfmcys.ysm.expression.host.ModelSoundId;

import java.lang.reflect.Modifier;
import java.nio.file.Path;

public final class PackagedSoundLinkageSmoke {
    private PackagedSoundLinkageSmoke() {}

    public static void main(String[] args) throws Exception {
        var expectedArchive = Path.of(args[0]).toRealPath();
        var actualArchive = Path.of(SoundInstanceManager.class.getProtectionDomain().getCodeSource().getLocation()
            .toURI()).toRealPath();
        if (!expectedArchive.equals(actualArchive)) {
            throw new AssertionError("Sound manager was not loaded from the packaged archive: " + actualArchive);
        }

        var manager = new SoundInstanceManager();
        // An empty scope still links the method reference used by trim.
        manager.trim();

        for (var implementation : new String[]{MinecraftSoundInstance.class.getName(),
            "com.elfmcys.ysm.model.resource.client.audio.ClientAudioRuntime$Playback"}) {
            var type = Class.forName(implementation, false, PackagedSoundLinkageSmoke.class.getClassLoader());
            var query = type.getDeclaredMethod("isPlaybackStopped");
            if (!ICanStopSound.class.isAssignableFrom(type) || Modifier.isAbstract(query.getModifiers())
                || query.getReturnType() != boolean.class) {
                throw new AssertionError("Packaged playback does not implement the sound contract: " + implementation);
            }
        }

        var namedId = ModelSoundId.numeric(1);
        var named = new Playback();
        var anonymous = new Playback();
        if (!manager.startResolved(namedId, false, () -> named)
            || !manager.startResolved(ModelSoundId.numeric(0), false, () -> anonymous)) {
            throw new AssertionError("Active playbacks were rejected");
        }

        manager.trim();
        if (named.queries != 2 || anonymous.queries != 2) {
            throw new AssertionError("Trim did not query both named and anonymous playbacks");
        }
        if (manager.startResolved(namedId, false, Playback::new)) {
            throw new AssertionError("Trim removed an active named playback");
        }
        named.stopped = true;
        anonymous.stopped = true;
        manager.trim();
        if (manager.stopPlayingSound(namedId)) {
            throw new AssertionError("Trim retained a stopped named playback");
        }
        manager.stopAllPlayingSounds();
        if (named.stops != 0 || anonymous.stops != 0) {
            throw new AssertionError("Trim retained a stopped playback");
        }
        System.out.println("PACKAGED_SOUND_LINKAGE_OK");
    }

    private static final class Playback implements ICanStopSound {
        private boolean stopped;
        private int queries;
        private int stops;

        @Override
        public void setStopped() {
            stopped = true;
            stops++;
        }

        @Override
        public boolean isPlaybackStopped() {
            queries++;
            return stopped;
        }
    }
}
