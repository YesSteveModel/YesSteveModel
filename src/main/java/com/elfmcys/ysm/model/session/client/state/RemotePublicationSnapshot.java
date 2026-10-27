package com.elfmcys.ysm.model.session.client.state;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable remote collection authority; entries do not imply locally available content. */
public record RemotePublicationSnapshot(Map<Hash256, PublicationEntry> entries, Set<Hash256> grants,
    List<ModelPackDescriptor> packs, Map<String, Hash256> defaultAnimations) {
    public RemotePublicationSnapshot(List<PublicationEntry> entries, Set<Hash256> grants,
        List<ModelPackDescriptor> packs, Map<String, Hash256> defaultAnimations) {
        this(index(entries), grants, packs, defaultAnimations);
    }

    public RemotePublicationSnapshot {
        entries = validateEntries(entries);
        grants = Set.copyOf(grants);
        packs = validatePacks(packs);
        defaultAnimations = Map.copyOf(defaultAnimations);
        if (!entries.keySet().containsAll(grants)) {
            throw new IllegalArgumentException("Grant references a missing publication entry");
        }
    }

    public RemotePublicationSnapshot withCollections(Set<Hash256> nextGrants, List<ModelPackDescriptor> nextPacks,
        Map<String, Hash256> nextAnimations) {
        return new RemotePublicationSnapshot(entries, nextGrants, nextPacks, nextAnimations);
    }

    private static Map<Hash256, PublicationEntry> index(List<PublicationEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        var result = new LinkedHashMap<Hash256, PublicationEntry>();
        for (var entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (result.putIfAbsent(entry.modelId(), entry) != null) {
                throw new IllegalArgumentException("Duplicate publication model id");
            }
        }
        return result;
    }

    private static Map<Hash256, PublicationEntry> validateEntries(Map<Hash256, PublicationEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        var result = new LinkedHashMap<Hash256, PublicationEntry>();
        var paths = new LinkedHashSet<HierarchyPath>();
        entries.forEach((modelId, entry) -> {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(entry, "entry");
            if (!modelId.equals(entry.modelId())) {
                throw new IllegalArgumentException("Publication key does not match its entry");
            }
            if (!paths.add(entry.path())) {
                throw new IllegalArgumentException("Duplicate publication hierarchy path: " + entry.path());
            }
            result.put(modelId, entry);
        });
        return Collections.unmodifiableMap(result);
    }

    private static List<ModelPackDescriptor> validatePacks(List<ModelPackDescriptor> packs) {
        Objects.requireNonNull(packs, "packs");
        var hierarchies = new LinkedHashSet<String>();
        for (var pack : packs) {
            Objects.requireNonNull(pack, "pack");
            if (!hierarchies.add(pack.hierarchy())) {
                throw new IllegalArgumentException("Duplicate pack hierarchy: " + pack.hierarchy());
            }
        }
        return List.copyOf(packs);
    }
}
