package com.elfmcys.ysm.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MolangFunction {
    String value() default "";

    String namespace() default "";

    /** Uses the origin subject, context, and any component receiver. Arguments still use the current environment. */
    boolean alwaysOrigin() default false;

    /** Requires ACTION permission before evaluating arguments. */
    boolean action() default false;

    int minArgs() default -1;

    int maxArgs() default -1;
}
