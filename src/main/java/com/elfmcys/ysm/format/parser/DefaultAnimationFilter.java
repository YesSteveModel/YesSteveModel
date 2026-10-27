package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;

import java.io.IOException;
import java.util.Collection;

/** Immutable conversion rule for removing animations supplied by the builtin contract. */
@FunctionalInterface
public interface DefaultAnimationFilter {
    AnimationFileProto apply(RenderTargetKind kind, Collection<String> matches, String animationSet,
        AnimationFileProto source) throws IOException;

    static DefaultAnimationFilter keepAll() {
        return (kind, matches, animationSet, source) -> source;
    }
}
