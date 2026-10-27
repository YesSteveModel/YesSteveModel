package com.elfmcys.ysm.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;

/**
 * Marks an event handler that YSM should compatibility-check and register automatically.
 *
 * <p>
 * The annotation has the same compatibility-checking semantics as a class-level {@link YsmExtension}. The handler is
 * discovered without loading its class, its generated {@code check()} method runs first, and the handler is
 * instantiated and registered only when that result is compatible.
 * </p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(TYPE)
public @interface YsmEventHandler {
    /**
     * Additional package prefixes that belong to this extension and may be followed through the bytecode call graph.
     * Classes produced by the current javac invocation are always owned.
     */
    String[] value() default {};

    /** The physical side on which the generated check and handler are intended to run. */
    Side side() default Side.COMMON;
}
