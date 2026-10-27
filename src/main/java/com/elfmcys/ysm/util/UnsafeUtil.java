package com.elfmcys.ysm.util;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

public class UnsafeUtil {
    private static final Unsafe UNSAFE;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);

            UNSAFE = (Unsafe)field.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Couldn't obtain reference to sun.misc.Unsafe", e);
        }
    }

    public static Unsafe getUnsafe() {
        return UNSAFE;
    }
}
