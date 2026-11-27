package com.elfmcys.ysm.natives.physics;

import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.natives.NativeObject;
import com.elfmcys.ysm.natives.buffer.BufferArgument;
import java.io.IOException;
import java.lang.ref.Reference;
import java.util.function.Function;

/** Immutable compiled physics for one admitted geometry and definition. Each world retains its own native reference. */
public final class NativePhysicsAsset extends NativeObject {
    private static final Function<State, NativePhysicsAsset> ADOPT = NativePhysicsAsset::new;

    private NativePhysicsAsset(State state) {
        super(state);
    }

    public static NativePhysicsAsset compile(UniBuffer geometry, UniBuffer definition) throws IOException {
        var model = BufferArgument.packInput(geometry);
        var physics = BufferArgument.packInput(definition);
        try {
            long ptr = nCompile(model.obj(), model.flags(), physics.obj(), physics.flags());
            if (ptr == 0) {
                throw new IOException("Failed to compile physics asset");
            }
            return adopt(ptr, ADOPT);
        } finally {
            Reference.reachabilityFence(geometry);
            Reference.reachabilityFence(definition);
        }
    }

    private static native long nCompile(Object geometry, long geometryFlags, Object definition, long definitionFlags);
}
