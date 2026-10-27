package com.elfmcys.ysm.api.internal.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.CLASS;

/**
 * 指示该方法可能在后台并行调用，值为并行粒度
 */
@Documented
@Retention(value = CLASS)
@Target(value = {METHOD})
public @interface ParallelInvoke {
    String value();
}
