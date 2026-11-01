package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.EffectRequest;
import java.util.Objects;

/** Internal scheduler work, distinct from an external prepared effect. */
public record DeferredRequest(EventArguments arguments) implements EffectRequest {
    public DeferredRequest { Objects.requireNonNull(arguments, "arguments"); }
}
