package com.elfmcys.ysm.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface MolangDependency {
    String modId();

    String version() default "";
}
