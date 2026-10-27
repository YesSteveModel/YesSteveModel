package com.elfmcys.ysm.expression.oracle;

import dev.semaweave.RuntimeValue;
import dev.semaweave.RuntimeValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Controlled entity-local state owner used by the T01 oracles.
 *
 * <p>
 * It owns entity-local random state, physics state and ordinary instance state and exposes an explicit generation.
 * Expectations are computed from the seeded state before execution, so two interleaved entities must reproduce their
 * own independent sequences (Frozen O-06/O-07). It is a deterministic surrogate for the existing entity owner, not a
 * production owner and not a measure of real physics values.
 */
public final class OracleEntityState {
    private final String entityId;
    private final Random random;
    private final Map<String, Float> physics = new LinkedHashMap<>();
    private final Map<String, RuntimeValue> instance = new LinkedHashMap<>();
    private final ArrayList<String> log = new ArrayList<>();
    private long generation = 1;
    private int randomDraws;
    private int physicsSteps;

    public OracleEntityState(String entityId, long seed) {
        this.entityId = Objects.requireNonNull(entityId, "entityId");
        this.random = new Random(seed);
    }

    public String entityId() {
        return entityId;
    }

    public long generation() {
        return generation;
    }

    public int randomDraws() {
        return randomDraws;
    }

    public int physicsSteps() {
        return physicsSteps;
    }

    public List<String> log() {
        return List.copyOf(log);
    }

    public boolean isCurrent(long candidate) {
        return candidate == generation;
    }

    /** Invalidates this entity's state; prior generations must not be reused. */
    public long replaceGeneration() {
        generation++;
        physics.clear();
        log.add("generation:" + generation);
        return generation;
    }

    public float nextRandomFloat(float minimum, float maximum) {
        randomDraws++;
        var low = Math.min(minimum, maximum);
        var high = Math.max(minimum, maximum);
        var value = low + random.nextFloat() * (high - low);
        log.add("random:" + value);
        return value;
    }

    public int nextRandomInt(int minimum, int maximum) {
        randomDraws++;
        var low = Math.min(minimum, maximum);
        var high = Math.max(minimum, maximum);
        var value = low + random.nextInt(high - low);
        log.add("randomInt:" + value);
        return value;
    }

    /** Controlled first-order step from a zero start: state += (input - state) * coefficient. */
    public float firstOrder(String key, float input, float coefficient) {
        physicsSteps++;
        var current = physics.getOrDefault(key, 0.0f);
        var next = current + (input - current) * coefficient;
        physics.put(key, next);
        log.add("firstOrder:" + key + ':' + next);
        return next;
    }

    public float physicsState(String key) {
        return physics.getOrDefault(key, 0.0f);
    }

    public void writeInstance(String name, RuntimeValue value) {
        instance.put(name, Objects.requireNonNull(value, "value"));
    }

    public RuntimeValue readInstance(String name) {
        return instance.getOrDefault(name, RuntimeValues.unset());
    }

    public Map<String, Float> physicsSnapshot() {
        return Map.copyOf(physics);
    }
}
