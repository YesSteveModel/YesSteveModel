package com.elfmcys.ysm.util;

import java.lang.ref.Cleaner;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class CleanerUtil {
    private static final Cleaner CLEANER = Cleaner.create();

    public static <T> Cleaner.Cleanable ref(Object obj, T arg, Consumer<T> cleanAction) {
        return CLEANER.register(obj, () -> cleanAction.accept(arg));
    }

    public static <T0, T1> Cleaner.Cleanable ref(Object obj, T0 arg0, T1 arg1, BiConsumer<T0, T1> cleanAction) {
        return CLEANER.register(obj, () -> cleanAction.accept(arg0, arg1));
    }
}
