package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Published only after a default target has fully bound its animations. */
public final class DefaultAnimationRuntime {
    private volatile Map<String, Source> sources = Map.of();
    private final CurrentAnimationValidator validator;

    public DefaultAnimationRuntime(BuiltinModelIndex contract) {
        this.validator = contract::requireCurrent;
    }

    DefaultAnimationRuntime() {
        validator = (target, animationSet, animation) -> {
        };
    }

    public synchronized void publishSources(Map<String, Source> additions) {
        var next = new LinkedHashMap<>(sources);
        additions.forEach((domain, source) -> {
            var previous = next.putIfAbsent(domain, source);
            if (previous != null && !previous.equals(source)) {
                throw new IllegalStateException("Conflicting default source domain: " + domain);
            }
        });
        sources = Map.copyOf(next);
    }

    public Source source(String domain) {
        return sources.get(domain);
    }

    public void requireCurrent(RenderTargetProto target, String animationSet, AnimationProto animation)
        throws IOException {
        validator.requireCurrent(target, animationSet, animation);
    }

    public synchronized void clear() {
        sources = Map.of();
    }

    public record Source(com.elfmcys.ysm.model.domain.Hash256 containerId, String targetId,
        com.elfmcys.ysm.expression.inventory.ExpressionSet set,
        com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto definition,
        com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto strings) {
        String prefix() {
            return "fallback/" + containerId + '/' + targetId + '/' + set.id() + '/';
        }
    }

    @FunctionalInterface
    private interface CurrentAnimationValidator {
        void requireCurrent(RenderTargetProto target, String animationSet, AnimationProto animation) throws IOException;
    }
}
