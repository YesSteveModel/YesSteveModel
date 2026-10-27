package com.elfmcys.ysm.api.internal.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.CLASS;

/**
 * 指示该方法在 MC 渲染线程调用
 */
@Retention(value = CLASS)
@Target(value = {METHOD, TYPE})
public @interface RenderThreadInvoke {}
