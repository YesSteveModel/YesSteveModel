package com.elfmcys.ysm.expression.host;

import dev.semaweave.CompiledRoamingState;
import dev.semaweave.RoamingPayload;
import dev.semaweave.RoamingRole;
import dev.semaweave.interpreter.InterpreterRoamingState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Borrows one installation's mutable storage under its owner's execution window. Interpreted names are appended as
 * programs consume them; their field numbers are local projections, not wire identities. Either role permits local
 * writes; only producers export, and only consumers import payloads.
 */
public sealed interface RoamingState permits RoamingState.Aot, RoamingState.Interpreted {
    RoamingRole role();

    List<String> fieldNames();

    float read(short fieldId);

    void write(short fieldId, float value);

    boolean hasChanges();

    RoamingPayload snapshot();

    RoamingPayload full();

    RoamingPayload popChanges();

    void applyFull(RoamingPayload payload);

    void applyPatch(RoamingPayload payload);

    record Aot(CompiledRoamingState state) implements RoamingState {
        public Aot {
            Objects.requireNonNull(state, "state");
        }

        @Override
        public RoamingRole role() {
            return state.role();
        }

        @Override
        public List<String> fieldNames() {
            return state.fieldNames();
        }

        @Override
        public float read(short fieldId) {
            return state.read(fieldId);
        }

        @Override
        public void write(short fieldId, float value) {
            state.write(fieldId, value);
        }

        @Override
        public boolean hasChanges() {
            return state.hasChanges();
        }

        @Override
        public RoamingPayload snapshot() {
            return state.snapshot();
        }

        @Override
        public RoamingPayload full() {
            return state.full();
        }

        @Override
        public RoamingPayload popChanges() {
            return state.popChanges();
        }

        @Override
        public void applyFull(RoamingPayload payload) {
            state.applyFull(payload);
        }

        @Override
        public void applyPatch(RoamingPayload payload) {
            state.applyPatch(payload);
        }
    }

    final class Interpreted implements RoamingState {
        private final InterpreterRoamingState state;
        private final List<String> names = new ArrayList<>();
        private final Map<String, Short> fields = new HashMap<>();

        public Interpreted(InterpreterRoamingState state) {
            this.state = Objects.requireNonNull(state, "state");
        }

        @Override
        public RoamingRole role() {
            return state.role();
        }

        @Override
        public List<String> fieldNames() {
            synchronizeNames();
            return List.copyOf(names);
        }

        private void synchronizeNames() {
            // Existing numbers stay stable when a later expression first consumes another name.
            for (var name : state.fieldNames().stream().filter(name -> !fields.containsKey(name)).sorted().toList()) {
                if (names.size() > Short.MAX_VALUE)
                    throw new IllegalStateException("interpreted roaming layout exceeds payload capacity");
                fields.put(name, (short)names.size());
                names.add(name);
            }
        }

        @Override
        public float read(short fieldId) {
            synchronizeNames();
            return state.read(names.get(fieldId));
        }

        @Override
        public void write(short fieldId, float value) {
            synchronizeNames();
            state.write(names.get(fieldId), value);
        }

        @Override
        public boolean hasChanges() {
            if (role() != RoamingRole.PRODUCER)
                throw new IllegalStateException("consumer state cannot export");
            return state.hasChanges();
        }

        @Override
        public RoamingPayload snapshot() {
            return payload(state.snapshot());
        }

        @Override
        public RoamingPayload full() {
            return payload(state.full());
        }

        @Override
        public RoamingPayload popChanges() {
            return payload(state.popChanges());
        }

        private RoamingPayload payload(Map<String, Float> values) {
            synchronizeNames();
            var ids = new short[values.size()];
            var numbers = new float[values.size()];
            int index = 0;
            for (var entry : values.entrySet()) {
                ids[index] = fields.get(entry.getKey());
                numbers[index++] = entry.getValue();
            }
            return new RoamingPayload(ids, numbers);
        }

        @Override
        public void applyFull(RoamingPayload payload) {
            requireConsumer();
            state.applyFull(values(payload));
        }

        @Override
        public void applyPatch(RoamingPayload payload) {
            requireConsumer();
            state.applyPatch(values(payload));
        }

        private void requireConsumer() {
            if (role() != RoamingRole.CONSUMER)
                throw new IllegalStateException("producer state cannot import");
        }

        private Map<String, Float> values(RoamingPayload payload) {
            synchronizeNames();
            var values = new LinkedHashMap<String, Float>();
            for (int index = 0; index < payload.fields().length; index++)
                values.put(names.get(payload.fields()[index]), payload.values()[index]);
            return values;
        }
    }
}
