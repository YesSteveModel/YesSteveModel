package com.elfmcys.ysm.api.annotation;

import com.elfmcys.ysm.api.molang.MolangEffect;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MolangFunction {
    String value() default "";

    String namespace() default "";

    MolangEffect effect() default MolangEffect.CONTEXT;

    /** Declares an exact same-class target; this hint does not authorize context-free execution. */
    String contextFreeTarget() default "";

    int minArgs() default -1;

    int maxArgs() default -1;
}
