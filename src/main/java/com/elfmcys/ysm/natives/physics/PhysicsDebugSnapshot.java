package com.elfmcys.ysm.natives.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

/** An owned copy of one accepted sample. Node and constraint indices follow the published declarations. */
public final class PhysicsDebugSnapshot {
    private static final int VALID = 1;
    private static final int STEPPED = 2;
    private static final int CONTACT_SAMPLED = 4;

    private static final int NODE_FLOATS = 8;
    private static final int NODE_INTS = 3;
    private static final int JOINT_FLOATS = 33;

    private static final int NODE_POSE = 1;
    private static final int NODE_ACTIVE = 2;
    private static final int FRAME_A = 1;
    private static final int FRAME_B = 2;
    private static final int CURRENT = 4;
    private static final int ANIMATION = 8;
    private static final int CURRENT_SINGULAR = 16;
    private static final int REST_SINGULAR = 32;
    private static final int ANIMATION_SINGULAR = 64;

    private static final Deactivation[] DEACTIVATIONS = Deactivation.values();
    private static final Contact[] CONTACTS = Contact.values();

    private final List<Node> nodes;
    private final List<Joint> joints;
    private final int[] contacts;
    private final int contactStart;
    private final int flags;

    private PhysicsDebugSnapshot(List<Node> nodes, List<Joint> joints, int[] contacts, int flags) {
        this.nodes = List.copyOf(nodes);
        this.joints = List.copyOf(joints);
        this.contacts = contacts;
        this.contactStart = NODE_INTS * nodes.size() + joints.size();
        this.flags = flags;
    }

    public enum Deactivation {
        NONE, INVALID_SCALE, INVALID_POSE, INVALID_INERTIA, CUBES_HIDDEN, CHILDREN_HIDDEN, LOST_ANCHOR, INACTIVE_INPUT
    }

    public enum Contact {
        NO_SAMPLE, INELIGIBLE, NO_STEP, NOT_SAMPLED, NO_MANIFOLD, SPECULATIVE, TOUCHING
    }

    public record Vec3(float x, float y, float z) {
    }

    public record Rotation(float x, float y, float z, float w) {
    }

    /** Position is in model-space metres. */
    public record Pose(Vec3 position, Rotation rotation) {
    }

    /** Linear values are in metres; angular values are right-handed radians. */
    public record Reading(Vec3 linear, Vec3 angular, boolean rotationSingular) {
    }

    public record Node(@Nullable Pose pose, float scale, boolean active, Deactivation deactivation, int culprit,
        @Nullable String culpritName) {
    }

    /** A zero scale indicates that the reference end has no valid solver scale. Rest is unscaled. */
    public record Joint(@Nullable Pose frameA, @Nullable Pose frameB, float scaleA, @Nullable Reading current,
        Reading rest, @Nullable Reading animation) {
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Joint> joints() {
        return joints;
    }

    public boolean steppedSinceReset() {
        return (flags & STEPPED) != 0;
    }

    public boolean sampledLastStep() {
        return (flags & CONTACT_SAMPLED) != 0;
    }

    public Contact contact(int first, int second) {
        if (first == second)
            return Contact.INELIGIBLE;
        return CONTACTS[contacts[contactStart + pairIndex(Math.max(first, second), Math.min(first, second))]];
    }

    static int floatCount(int nodes, int joints) {
        return NODE_FLOATS * nodes + JOINT_FLOATS * joints;
    }

    static int intCount(int nodes, int joints) {
        return NODE_INTS * nodes + joints + pairIndex(nodes, 0);
    }

    private static int pairIndex(int high, int low) {
        return high * (high - 1) / 2 + low;
    }

    /** Takes ownership of the fresh integer output array. The wire is private to the Java and JNI adapters. */
    static @Nullable PhysicsDebugSnapshot decode(int nodeCount, int jointCount, int flags, float[] floats, int[] ints,
        IntFunction<String> sortedBoneName, IntFunction<String> jointId) {
        if ((flags & VALID) == 0)
            return null;
        var nodes = new ArrayList<Node>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            int f = i * NODE_FLOATS;
            int j = i * NODE_INTS;
            int nodeFlags = ints[j];
            var deactivation = DEACTIVATIONS[ints[j + 1]];
            int culprit = ints[j + 2];
            String culpritName = culprit < 0 ? null
                : deactivation == Deactivation.LOST_ANCHOR ? jointId.apply(culprit) : sortedBoneName.apply(culprit);
            nodes.add(new Node((nodeFlags & NODE_POSE) != 0 ? pose(floats, f) : null, floats[f + 7],
                (nodeFlags & NODE_ACTIVE) != 0, deactivation, culprit, culpritName));
        }
        var joints = new ArrayList<Joint>(jointCount);
        for (int i = 0; i < jointCount; i++) {
            int f = nodeCount * NODE_FLOATS + i * JOINT_FLOATS;
            int jointFlags = ints[nodeCount * NODE_INTS + i];
            joints.add(new Joint((jointFlags & FRAME_A) != 0 ? pose(floats, f) : null,
                (jointFlags & FRAME_B) != 0 ? pose(floats, f + 7) : null, floats[f + 14],
                (jointFlags & CURRENT) != 0 ? reading(floats, f + 15, (jointFlags & CURRENT_SINGULAR) != 0) : null,
                reading(floats, f + 21, (jointFlags & REST_SINGULAR) != 0), (jointFlags & ANIMATION) != 0
                    ? reading(floats, f + 27, (jointFlags & ANIMATION_SINGULAR) != 0) : null));
        }
        return new PhysicsDebugSnapshot(nodes, joints, ints, flags);
    }

    private static Pose pose(float[] values, int offset) {
        return new Pose(vector(values, offset),
            new Rotation(values[offset + 3], values[offset + 4], values[offset + 5], values[offset + 6]));
    }

    private static Reading reading(float[] values, int offset, boolean singular) {
        return new Reading(vector(values, offset), vector(values, offset + 3), singular);
    }

    private static Vec3 vector(float[] values, int offset) {
        return new Vec3(values[offset], values[offset + 1], values[offset + 2]);
    }
}
