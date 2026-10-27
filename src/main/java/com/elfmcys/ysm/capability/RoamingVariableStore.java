package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.Hash256;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Consumer;

/** Persistent roaming-variable storage plus deferred access before a model is selected. */
final class RoamingVariableStore {
    private Int2ReferenceOpenHashMap<Object2FloatOpenHashMap<String>> variables = new Int2ReferenceOpenHashMap<>();
    private final Queue<Consumer<Object2FloatOpenHashMap<String>>> pending = new ArrayDeque<>();

    Object2FloatOpenHashMap<String> variables(Hash256 modelHash) {
        var values = variables.computeIfAbsent(modelHash.roamingHash(), ignored -> new Object2FloatOpenHashMap<>(0));
        Consumer<Object2FloatOpenHashMap<String>> consumer;
        while ((consumer = pending.poll()) != null) {
            consumer.accept(values);
        }
        return values;
    }

    void execute(Hash256 modelHash, Consumer<Object2FloatOpenHashMap<String>> consumer) {
        if (modelHash == null) {
            pending.add(consumer);
        } else {
            consumer.accept(variables(modelHash));
        }
    }

    Optional<Object2FloatOpenHashMap<String>> get(Hash256 modelHash) {
        return modelHash == null ? Optional.empty() : Optional.of(variables(modelHash));
    }

    void update(int roamingHash, Object2FloatMap<String> changes) {
        variables.compute(roamingHash, (hash, values) -> {
            if (values == null) {
                return new Object2FloatOpenHashMap<>(changes);
            }
            values.putAll(changes);
            return values;
        });
    }

    void replace(int roamingHash, Object2FloatMap<String> values) {
        variables.put(roamingHash, new Object2FloatOpenHashMap<>(values));
    }

    void trim(IntSet retainedHashes) {
        var iterator = variables.int2ReferenceEntrySet().fastIterator();
        while (iterator.hasNext()) {
            if (!retainedHashes.contains(iterator.next().getIntKey())) {
                iterator.remove();
            }
        }
    }

    void moveFrom(RoamingVariableStore source) {
        variables = source.variables;
        pending.addAll(source.pending);
        source.variables = new Int2ReferenceOpenHashMap<>();
        source.pending.clear();
    }

    CompoundTag serialize() {
        var storageTag = new CompoundTag();
        variables.int2ReferenceEntrySet().fastForEach(storageEntry -> {
            var valuesTag = new CompoundTag();
            storageEntry.getValue().object2FloatEntrySet()
                .fastForEach(entry -> valuesTag.putFloat(entry.getKey(), entry.getFloatValue()));
            storageTag.put(String.valueOf(storageEntry.getIntKey()), valuesTag);
        });
        return storageTag;
    }

    void deserialize(CompoundTag storageTag) {
        variables.clear();
        for (var hashText : storageTag.getAllKeys()) {
            final int roamingHash;
            try {
                roamingHash = Integer.parseInt(hashText);
            } catch (NumberFormatException ignored) {
                continue;
            }
            var valuesTag = storageTag.getCompound(hashText);
            var values = new Object2FloatOpenHashMap<String>(valuesTag.size());
            for (var name : valuesTag.getAllKeys()) {
                values.put(name, valuesTag.getFloat(name));
            }
            variables.put(roamingHash, values);
        }
    }
}
