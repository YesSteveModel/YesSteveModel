package com.elfmcys.ysm.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;

/**
 * Marks an extension entry point whose linkage against YSM must be checked before use.
 *
 * <p>
 * When placed on a method, overloads with the same name form one compatibility group and generate
 * {@code check<MethodName>()}. When placed on a class, the whole owned class hierarchy forms one group and generates
 * {@code check()}.
 * </p>
 */
@Documented
@Retention(value = RetentionPolicy.SOURCE)
@Target(value = {METHOD, TYPE})
public @interface YsmExtension {
    /**
     * Additional package prefixes that belong to this extension and may be followed through the bytecode call graph.
     * Classes produced by the current javac invocation are always owned.
     */
    String[] value() default {};

    /** The physical side on which the generated check is intended to run. */
    Side side() default Side.COMMON;
}
