package com.elfmcys.ysm.expression;

import dev.semaweave.CompiledRoamingState;
import dev.semaweave.RoamingPayload;
import dev.semaweave.RoamingRole;
import dev.semaweave.compilation.ResourceGenerationToken;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

public final class TestRoamingStates {
    private TestRoamingStates() {}

    public static CompiledRoamingState create(ResourceGenerationToken generation, RoamingRole role) {
        return new State(generation, role, List.of());
    }

    public static CompiledRoamingState create(ResourceGenerationToken generation, RoamingRole role,
        List<String> fieldNames) {
        return new State(generation, role, List.copyOf(fieldNames));
    }

    private static final class State implements CompiledRoamingState {
        private final ResourceGenerationToken generation;
        private final RoamingRole role;
        private final List<String> fields;
        private final float[] values;
        private final TreeSet<Short> dirty;

        private State(ResourceGenerationToken generation, RoamingRole role, List<String> fields) {
            this.generation = generation;
            this.role = role;
            this.fields = fields;
            values = new float[fields.size()];
            dirty = role == RoamingRole.PRODUCER ? new TreeSet<>() : null;
        }

        @Override
        public ResourceGenerationToken generation() {
            return generation;
        }

        @Override
        public RoamingRole role() {
            return role;
        }

        @Override
        public List<String> fieldNames() {
            return fields;
        }

        @Override
        public float read(short field) {
            return values[field];
        }

        @Override
        public void write(short field, float value) {
            values[field] = value;
            if (dirty != null)
                dirty.add(field);
        }

        @Override
        public boolean hasChanges() {
            requireProducer();
            return !dirty.isEmpty();
        }

        @Override
        public RoamingPayload full() {
            var result = snapshot();
            dirty.clear();
            return result;
        }

        @Override
        public RoamingPayload snapshot() {
            requireProducer();
            var ids = new short[values.length];
            var exported = new float[values.length];
            for (int index = 0; index < values.length; index++) {
                ids[index] = (short)index;
                exported[index] = Float.isFinite(values[index]) ? values[index] : 0.0f;
            }
            return new RoamingPayload(ids, exported);
        }

        @Override
        public RoamingPayload popChanges() {
            requireProducer();
            var ids = new short[dirty.size()];
            var exported = new float[dirty.size()];
            int index = 0;
            for (var field : dirty) {
                ids[index] = field;
                exported[index++] = Float.isFinite(values[field]) ? values[field] : 0.0f;
            }
            dirty.clear();
            return new RoamingPayload(ids, exported);
        }

        @Override
        public void applyFull(RoamingPayload payload) {
            requireConsumer();
            Arrays.fill(values, 0.0f);
            applyPatch(payload);
        }

        @Override
        public void applyPatch(RoamingPayload payload) {
            requireConsumer();
            for (int index = 0; index < payload.fields().length; index++)
                values[payload.fields()[index]] = payload.values()[index];
        }

        private void requireProducer() {
            if (role != RoamingRole.PRODUCER)
                throw new IllegalStateException();
        }

        private void requireConsumer() {
            if (role != RoamingRole.CONSUMER)
                throw new IllegalStateException();
        }
    }
}
