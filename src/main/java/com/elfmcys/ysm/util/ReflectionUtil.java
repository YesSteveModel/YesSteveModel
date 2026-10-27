package com.elfmcys.ysm.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Optional;

public class ReflectionUtil {
    public static Optional<VarHandle> getField(Class<?> clazz, String name, Class<?> type) {
        try {
            return Optional
                .of(MethodHandles.privateLookupIn(clazz, MethodHandles.lookup()).findVarHandle(clazz, name, type));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }
}
