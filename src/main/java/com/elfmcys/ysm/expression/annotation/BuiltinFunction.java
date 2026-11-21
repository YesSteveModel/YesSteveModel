package com.elfmcys.ysm.expression.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface BuiltinFunction {
    String value();

    String namespace() default "";

    boolean alwaysOrigin() default false;

    boolean action() default false;

    int minArgs() default -1;

    int maxArgs() default -1;
}
