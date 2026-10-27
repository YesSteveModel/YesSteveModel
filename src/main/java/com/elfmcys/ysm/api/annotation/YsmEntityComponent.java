package com.elfmcys.ysm.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface YsmEntityComponent {
    String value();

    String modId() default "";

    MolangDependency[] dependencies() default {};

    MolangAdmissionMode mode() default MolangAdmissionMode.AUTO;
}
