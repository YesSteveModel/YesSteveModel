package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.annotation.MolangDependency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Build-time declaration of one owner-local built-in component. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface BuiltinComponent {
    String value();

    MolangDependency[] dependencies() default {};
}
