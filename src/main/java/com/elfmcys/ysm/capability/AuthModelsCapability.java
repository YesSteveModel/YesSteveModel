package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.ModelId;
import com.google.common.collect.Sets;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Set;

public class AuthModelsCapability {
    private Set<ModelId> authModels = Sets.newHashSet();

    public void addModel(ModelId modelHash) {
        authModels.add(modelHash);
    }

    public void copyFrom(AuthModelsCapability source) {
        this.authModels = source.authModels;
    }

    public void removeModel(ModelId modelHash) {
        authModels.remove(modelHash);
    }

    public boolean containModel(ModelId modelHash) {
        return authModels.contains(modelHash);
    }

    public Set<ModelId> getAuthModels() {
        return authModels;
    }

    public void setAuthModels(Set<ModelId> authModels) {
        this.authModels = Sets.newHashSet(authModels);
    }

    public void clear() {
        authModels.clear();
    }

    public ListTag serializeNBT() {
        ListTag listTag = new ListTag();
        for (ModelId modelHash : authModels) {
            listTag.add(StringTag.valueOf(modelHash.toString()));
        }
        return listTag;
    }

    public void deserializeNBT(ListTag nbt) {
        this.authModels.clear();
        for (Tag tag : nbt) {
            try {
                authModels.add(ModelId.parse(tag.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // Old path-based authorization entries are intentionally not migrated.
            }
        }
    }
}
