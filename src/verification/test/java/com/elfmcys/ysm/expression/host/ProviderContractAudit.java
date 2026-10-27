package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.annotation.MolangNullable;
import it.unimi.dsi.fastutil.floats.FloatList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Opt-in development observations; never linked into a generated production call. */
final class ProviderContractAudit {
    private ProviderContractAudit() {}

    static Published publish(Method method, Object value) throws ReflectiveOperationException {
        var label = method.getDeclaringClass().getName() + "#" + method.getName();
        boolean nullable = method.isAnnotationPresent(MolangNullable.class) || method.getReturnType() == Float.class
            || method.getReturnType() == Integer.class || method.getReturnType() == Boolean.class
            || method.getReturnType() == String.class;
        if (value == null && !nullable)
            throw violation(label, "$", "forbidden null");
        return new Published(label, value, scalarMissing(method, value) ? value : observe(label, "$", value),
            scalarMissing(method, value));
    }

    private static Object observe(String label, String path, Object value) throws ReflectiveOperationException {
        if (value instanceof Float number && !Float.isFinite(number)) {
            throw violation(label, path, "non-finite value");
        }
        if (value instanceof FloatList numbers) {
            var copy = new ArrayList<Object>(numbers.size());
            for (int i = 0; i < numbers.size(); i++) {
                copy.add(observe(label, path + "[" + i + "]", numbers.getFloat(i)));
            }
            return copy;
        }
        if (value instanceof List<?> values) {
            var copy = new ArrayList<Object>(values.size());
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i) == null)
                    throw violation(label, path + "[" + i + "]", "forbidden null");
                copy.add(observe(label, path + "[" + i + "]", values.get(i)));
            }
            return copy;
        }
        if (value != null && value.getClass().isRecord()) {
            var copy = new ArrayList<Object>();
            for (var field : value.getClass().getRecordComponents()) {
                var fieldPath = path + "." + field.getName();
                var content = field.getAccessor().invoke(value);
                if (content == null)
                    throw violation(label, fieldPath, "forbidden null");
                copy.add(observe(label, fieldPath, content));
            }
            return copy;
        }
        return value;
    }

    private static boolean scalarMissing(Method method, Object value) {
        return (method.getReturnType() == float.class || method.getReturnType() == Float.class)
            && value instanceof Float number && !Float.isFinite(number);
    }

    record Published(String label, Object value, Object snapshot, boolean scalarMissing) {
        void verifyUnchanged() throws ReflectiveOperationException {
            var current = scalarMissing ? value : observe(label, "$", value);
            if (!java.util.Objects.equals(snapshot, current)) {
                throw violation(label, "$", "modified after publication");
            }
        }
    }

    static Borrow borrow(String label, MolangContext<?> context) {
        return new Borrow(label, context, Thread.currentThread());
    }

    record Borrow(String label, MolangContext<?> context, Thread thread) {
        void verify() {
            if (thread != Thread.currentThread())
                throw violation(label, "context", "cross-thread borrow");
            try {
                if (!context.isActive())
                    throw new IllegalStateException("expired borrow");
                context.verifyEligible();
            } catch (IllegalStateException failure) {
                throw violation(label, "context", "expired borrow");
            }
        }
    }

    private static IllegalStateException violation(String label, String path, String reason) {
        return new IllegalStateException(label + " " + path + ": " + reason);
    }
}
