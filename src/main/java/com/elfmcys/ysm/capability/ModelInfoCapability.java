package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** The player owner holds one selection independently of connection negotiation. */
public final class ModelInfoCapability {
    private CommittedSelection selection;
    private boolean mandatory;
    private boolean disabled;
    private boolean dirty;
    private boolean flagsDirty;
    private ServerDrivenPlayerPropertiesTracker propertiesTracker = new ServerDrivenPlayerPropertiesTracker();

    public CommittedSelection selection() {
        return selection;
    }

    public void commitSelection(CommittedSelection next) {
        selection = Objects.requireNonNull(next);
        markDirty();
    }

    public void retireProducer(Object producer) {
        if (selection != null && selection.accepts(producer, selection.intentId()))
            commitSelection(CommittedSelection.appearance(selection.model(), selection.texture()));
    }

    public void setModelAndTexture(ModelId modelId, String texture) {
        setModelSelection(modelId == null ? null : PublishedModelId.model(modelId), texture);
    }

    public void setModelSelection(PublishedModelId modelId, String texture) {
        if (Objects.equals(getModelId(), modelId) && getSelectTexture().equals(texture))
            return;
        selection = modelId == null ? null : CommittedSelection.appearance(modelId, texture);
        markDirty();
    }

    public void setCommandSelection(ModelId modelId, String texture) {
        setCommandSelection(PublishedModelId.model(modelId), texture);
    }

    public void setCommandSelection(PublishedModelId modelId, String texture) {
        commitSelection(CommittedSelection.appearance(modelId, texture));
        mandatory = true;
    }

    public void moveFrom(ModelInfoCapability source) {
        selection = source.selection;
        source.selection = null;
        mandatory = source.mandatory;
        disabled = source.disabled;
        propertiesTracker = source.propertiesTracker;
        markDirty();
        flagsDirty = true;
    }

    public PublishedModelId getModelId() {
        return selection == null ? null : selection.model();
    }

    public ModelId getContentModelId() {
        if (getModelId() instanceof PublishedModelId.Model model)
            return model.hash();
        return getModelId() == null ? null
            : com.elfmcys.ysm.model.service.ServerModelService.current()
                .flatMap(com.elfmcys.ysm.model.service.ServerModelService::catalog)
                .flatMap(catalog -> catalog.find(getModelId())).map(model -> model.modelId()).orElse(null);
    }

    public String getSelectTexture() {
        return selection == null ? "" : selection.texture();
    }

    public void setSelectTexture(String value) {
        if (selection != null)
            selection.texture(value);
    }

    public void setDisabled(boolean value) {
        if (disabled != value) {
            disabled = value;
            flagsDirty = true;
        }
    }

    public boolean consumeFlagsDirty() {
        var value = flagsDirty;
        flagsDirty = false;
        return value;
    }

    public void playAnimation(ServerPlayer player, String animation) {
        propertiesTracker.setExtraAnimation(player, true, animation);
    }

    public void stopAnimation(ServerPlayer player) {
        playAnimation(player, "");
    }

    public void applyClientAnimation(String animation) {
        if (selection != null)
            selection.animation(animation);
    }

    public void executeWithMolangVars(Consumer<Object2FloatOpenHashMap<String>> consumer) {
        getMolangVars().ifPresent(consumer);
    }

    public Optional<Object2FloatOpenHashMap<String>> getMolangVars() {
        if (selection == null)
            return Optional.empty();
        var values = selection.roaming().snapshot();
        var named = new Object2FloatOpenHashMap<String>(values.ids().length);
        for (int i = 0; i < values.ids().length; i++)
            named.put(selection.dictionary().names()[values.ids()[i]], values.values()[i]);
        return Optional.of(named);
    }

    public ServerDrivenPlayerPropertiesTracker getPropertiesTracker() {
        return propertiesTracker;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean consumeDirty() {
        var value = dirty;
        dirty = false;
        return value;
    }

    public void setMandatory(boolean value) {
        mandatory = value;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        tag.putString("model_id", getModelId() == null ? "" : getModelId().toString());
        tag.putString("select_texture", getSelectTexture());
        tag.putBoolean("mandatory", mandatory);
        tag.putBoolean("disabled", disabled);
        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        PublishedModelId id;
        try {
            var value = tag.getString("model_id");
            id = value.isEmpty() ? null : PublishedModelId.parse(value);
        } catch (IllegalArgumentException invalid) {
            id = null;
        }
        var texture = tag.getString("select_texture");
        if (texture.length() > 4 && texture.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
            texture = texture.substring(0, texture.length() - 4);
        selection = id == null ? null : CommittedSelection.appearance(id, texture);
        mandatory = tag.getBoolean("mandatory");
        disabled = tag.getBoolean("disabled");
    }
}
