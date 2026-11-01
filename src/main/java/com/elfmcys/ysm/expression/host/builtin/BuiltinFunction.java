package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangEffect;
import java.lang.annotation.*;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface BuiltinFunction {
    String value() default "";

    String namespace() default "";

    MolangEffect effect() default MolangEffect.CONTEXT;

    String contextFreeTarget() default "";

    int minArgs() default -1;

    int maxArgs() default -1;
}
