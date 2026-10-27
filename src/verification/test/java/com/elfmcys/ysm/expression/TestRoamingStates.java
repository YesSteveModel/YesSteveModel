package com.elfmcys.ysm.expression;

import dev.semaweave.CompiledRoamingState;
import dev.semaweave.RoamingPayload;
import dev.semaweave.RoamingRole;
import dev.semaweave.compilation.ResourceGenerationToken;

import java.util.TreeMap;
import java.util.Set;

public final class TestRoamingStates {
    private TestRoamingStates() {}

    public static CompiledRoamingState create(ResourceGenerationToken generation, RoamingRole role) {
        return new State(generation, role, null);
    }

    public static CompiledRoamingState create(ResourceGenerationToken generation, RoamingRole role,
        Set<String> allowedNames) {
        return new State(generation, role, Set.copyOf(allowedNames));
    }

    private static final class State implements CompiledRoamingState {
        private final ResourceGenerationToken generation;
        private final RoamingRole role;
        private final Set<String> allowedNames;
        private final TreeMap<String, Float> values = new TreeMap<>();
        private final TreeMap<String, Float> dirty = new TreeMap<>();

        private State(ResourceGenerationToken generation, RoamingRole role, Set<String> allowedNames) {
            this.generation = generation;
            this.role = role;
            this.allowedNames = allowedNames;
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
        public Set<String> fields() {
            return allowedNames == null ? Set.copyOf(values.keySet()) : allowedNames;
        }

        @Override
        public float read(String name) {
            return values.getOrDefault(name, 0.0f);
        }

        @Override
        public void write(String name, float value) {
            values.put(name, value);
            if (role == RoamingRole.PRODUCER)
                dirty.put(name, value);
        }

        @Override
        public boolean hasDelta() {
            requireProducer();
            return !dirty.isEmpty();
        }

        @Override
        public RoamingPayload full() {
            requireProducer();
            dirty.clear();
            return new RoamingPayload(values);
        }

        @Override
        public RoamingPayload snapshot() {
            requireProducer();
            return new RoamingPayload(values);
        }

        @Override
        public RoamingPayload delta() {
            requireProducer();
            var result = new RoamingPayload(dirty);
            dirty.clear();
            return result;
        }

        @Override
        public void validateFull(RoamingPayload payload) {
            validate(payload);
        }

        @Override
        public void validatePatch(RoamingPayload payload) {
            validate(payload);
        }

        @Override
        public void applyFull(RoamingPayload payload) {
            requireConsumer();
            validateFull(payload);
            values.clear();
            values.putAll(payload.values());
            dirty.clear();
        }

        @Override
        public void applyPatch(RoamingPayload payload) {
            requireConsumer();
            validatePatch(payload);
            values.putAll(payload.values());
            dirty.clear();
        }

        private void requireProducer() {
            if (role != RoamingRole.PRODUCER)
                throw new IllegalStateException();
        }

        private void requireConsumer() {
            if (role != RoamingRole.CONSUMER)
                throw new IllegalStateException();
        }

        private void validate(RoamingPayload payload) {
            payload.values().forEach((name, value) -> {
                if (allowedNames != null && !allowedNames.contains(name) || !Float.isFinite(value.floatValue())) {
                    throw new IllegalArgumentException();
                }
            });
        }
    }
}
