package com.elfmcys.ysm.format.schema.model.views;

import com.elfmcys.ysm.format.schema.model.ModelOriginVersion;
import com.elfmcys.ysm.format.schema.file.AssetFileView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.proto.mixel.common.StringPairProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.AuthorProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationButtonProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationClassifyProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.MetadataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStatsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Immutable manifest metadata plus rebuildable lookup indexes. */
public final class ModelInfoView {
    private final AssetFileView view;
    private final InfoProto info;
    private final SettingsProto settings;
    private final RenderTargetProto player;
    private final Map<String, Map<String, String>> langs;
    private final HashMap<String, AuthorProto> authors;
    private final Map<String, ExtraAnimationButtonProto> extraAnimationButtons;
    private final Map<String, ExtraAnimationClassifyProto> extraAnimationClassifications;
    private final int originVer;

    public ModelInfoView(InfoProto info, RenderTargetProto player, AssetFileView view) {
        this.view = Objects.requireNonNull(view, "view");
        this.info = Objects.requireNonNull(info, "info");
        this.settings = info.settings();
        this.originVer = ModelOriginVersion.fromModVersion(info.properties().originVer());
        this.player = Objects.requireNonNull(player, "player");

        var languages = new HashMap<String, Map<String, String>>(info.languageFiles().size());
        if (!info.languageFiles().isEmpty()) {
            for (var langFile : info.languageFiles()) {
                var lang = new HashMap<String, String>(langFile.entries().size());
                if (!langFile.entries().isEmpty()) {
                    for (var item : langFile.entries().object2ObjectEntrySet()) {
                        lang.put(item.getKey(), item.getValue());
                    }
                }
                languages.put(langFile.locale(), Map.copyOf(lang));
            }
        }
        langs = Map.copyOf(languages);

        var metadata = info.metadataUnsafe();
        var hasAuthors = metadata != null && !metadata.authors().isEmpty();
        authors = new HashMap<>(hasAuthors ? metadata.authors().size() : 0);
        if (hasAuthors) {
            for (var author : metadata.authors()) {
                authors.put(author.name(), author);
            }
        }

        var buttons = new LinkedHashMap<String, ExtraAnimationButtonProto>();
        for (var button : settings.extraAnimationButtons()) {
            for (var form : button.configForms()) {
                if (!form.type().equals("checkbox") && !form.type().equals("radio") && !form.type().equals("range")) {
                    throw new IllegalArgumentException("Unknown extra-animation form type: " + form.type());
                }
            }
            buttons.put(button.id(), button);
        }
        extraAnimationButtons = Map.copyOf(buttons);

        var classifications = new LinkedHashMap<String, ExtraAnimationClassifyProto>();
        for (var classification : settings.extraAnimationClassify()) {
            classifications.put(classification.id(), classification);
        }
        extraAnimationClassifications = Map.copyOf(classifications);
    }

    @NotNull
    public String translateOr(String key, String locale, @NotNull String defaultValue) {
        return Objects.requireNonNullElse(translate(key, locale), defaultValue);
    }

    @Nullable
    public String translate(String key, String locale) {
        var value = translateInner(key, locale);
        if (value != null) {
            return value;
        }
        return translateInner(key, "en_us");
    }

    @Nullable
    private String translateInner(String key, String locale) {
        var lang = langs.get(locale);
        if (lang != null) {
            return lang.get(key);
        }
        return null;
    }

    public @Nullable MetadataProto getMetadata() {
        return info.metadataUnsafe();
    }

    public boolean hasMetadata() {
        return info.hasMetadata();
    }

    public int originVer() {
        return originVer;
    }

    public SettingsProto getSettings() {
        return settings;
    }

    public ModelSettingsProto getPlayerSettings() {
        return player.settings();
    }

    public ModelStatsProto getPlayerStats() {
        return player.stats();
    }

    public ObjectList<StringPairProto> getExtraAnimations() {
        return settings.extraAnimation();
    }

    public Map<String, ExtraAnimationButtonProto> getExtraAnimationButtons() {
        return extraAnimationButtons;
    }

    public Map<String, ExtraAnimationClassifyProto> getExtraAnimationClassifications() {
        return extraAnimationClassifications;
    }

    public @Nullable Image readAvatar(BooleanSupplier cancelled, ChunkDataSource source, String authorName)
        throws IOException {
        var author = authors.get(authorName);
        if (author == null || !author.hasAvatar()) {
            return null;
        }
        return view.readImageBlob(cancelled, source, author.avatarUnsafe());
    }
}
