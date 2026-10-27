package com.elfmcys.ysm.api.internal;

import java.lang.reflect.*;
import java.util.*;

/** Shared checker protocol only; declaration publication and event construction have separate owners. */
public final class CompatibilityQualification {
    private CompatibilityQualification() {}

    public record Result(String status, boolean compatible, boolean coverageComplete, List<String> issues) {
        public Result {
            issues = List.copyOf(issues);
        }
    }

    public static String checkerName(String owner) {
        int separator = owner.lastIndexOf('.');
        String pkg = separator < 0 ? "" : owner.substring(0, separator + 1);
        return pkg + owner.substring(separator + 1).replace('$', '_') + "CompatibilityChecker";
    }

    public static Result check(String owner, ClassLoader loader) throws ReflectiveOperationException {
        String name = checkerName(owner);
        Class<?> checker = Class.forName(name, true, loader);
        return check(checker);
    }

    public static Result check(Class<?> checker) throws ReflectiveOperationException {
        Method method = checker.getMethod("check");
        if (!Modifier.isStatic(method.getModifiers()))
            throw new IllegalArgumentException("checker check() is not static");
        Object result = Objects.requireNonNull(method.invoke(null), "checker result");
        Class<?> type = result.getClass();
        Object compatible = type.getMethod("isCompatible").invoke(result);
        Object complete = type.getMethod("coverageComplete").invoke(result);
        Object raw = type.getMethod("issues").invoke(result);
        if (!(compatible instanceof Boolean accepted) || !(complete instanceof Boolean coverage)
            || !(raw instanceof Iterable<?> issues))
            throw new IllegalArgumentException("invalid checker Result");
        var messages = new ArrayList<String>();
        issues.forEach(issue -> messages.add(String.valueOf(issue)));
        return new Result(String.valueOf(type.getMethod("status").invoke(result)), accepted, coverage, messages);
    }
}
