package com.elfmcys.ysm.format.schema.model.views;

import com.elfmcys.ysm.format.schema.file.AssetFileView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

public class CommonAssetView {
    private final AssetFileView view;
    private final Map<String, SoundMedia> sounds;
    private final int stringsBlobId;

    public CommonAssetView(CommonProto commonAsset, AssetFileView view) throws IOException {
        this.view = view;
        var byName = new LinkedHashMap<String, SoundMedia>(commonAsset.sounds().size());
        var byStorage = new LinkedHashMap<java.util.List<SoundMedia.ChunkLocator>, SoundMedia>();
        for (var sound : commonAsset.sounds()) {
            var media = SoundMedia.create(sound, view);
            if (byName.putIfAbsent(media.name(), media) != null) {
                throw new IOException("Duplicate sound name: " + media.name());
            }
            var previous = byStorage.putIfAbsent(media.locators(), media);
            if (previous != null && !previous.hasSameMedia(media)) {
                throw new IOException("Conflicting descriptors for sound media " + media.mediaIdentity());
            }
        }
        this.sounds = Map.copyOf(byName);
        this.stringsBlobId = commonAsset.stringsBlobId();
    }

    public Map<String, SoundMedia> sounds() {
        return sounds;
    }

    public void validateSoundContent(BooleanSupplier cancelled, ChunkDataSource source) throws IOException {
        var validated = new HashSet<com.elfmcys.ysm.model.domain.Hash256>();
        for (var sound : sounds.values()) {
            if (!validated.add(sound.mediaIdentity())) {
                continue;
            }
            sound.validateComplete(cancelled, source);
        }
    }

    public StringDataProto readStringData(BooleanSupplier cancelled, ChunkDataSource source) throws IOException {
        return view.readProtoBlob(cancelled, source, stringsBlobId, StringDataProto::parseFrom);
    }
}
