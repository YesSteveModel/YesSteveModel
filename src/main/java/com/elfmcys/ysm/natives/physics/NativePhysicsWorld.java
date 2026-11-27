package com.elfmcys.ysm.natives.physics;

import com.elfmcys.ysm.natives.NativeObject;
import java.lang.ref.Reference;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

/** One entity's serially owned simulation and last accepted pose sample. */
public final class NativePhysicsWorld extends NativeObject {
    private static final Function<State, NativePhysicsWorld> ADOPT = NativePhysicsWorld::new;

    private NativePhysicsWorld(State state) {
        super(state);
    }

    /** Returns null if the native world could not be created. */
    public static @Nullable NativePhysicsWorld create(NativePhysicsAsset asset) {
        try {
            long ptr = nCreate(asset.get());
            return ptr == 0 ? null : adopt(ptr, ADOPT);
        } finally {
            Reference.reachabilityFence(asset);
        }
    }

    public void setDebugContactCapture(boolean enabled) {
        try {
            nSetDebugContactCapture(get(), enabled);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** The caller holds the current installation's draw borrow. Capturing does not advance world state. */
    public @Nullable PhysicsDebugSnapshot readDebugSnapshot(int nodeCount, int jointCount,
        IntFunction<String> sortedBoneName, IntFunction<String> jointId) {
        var floats = new float[PhysicsDebugSnapshot.floatCount(nodeCount, jointCount)];
        var ints = new int[PhysicsDebugSnapshot.intCount(nodeCount, jointCount)];
        int flags;
        try {
            flags = nReadDebugSnapshot(get(), floats, ints);
        } finally {
            Reference.reachabilityFence(this);
        }
        return PhysicsDebugSnapshot.decode(nodeCount, jointCount, flags, floats, ints, sortedBoneName, jointId);
    }

    private static native long nCreate(long asset);

    private static native void nSetDebugContactCapture(long world, boolean enabled);

    private static native int nReadDebugSnapshot(long world, float[] floats, int[] ints);
}
