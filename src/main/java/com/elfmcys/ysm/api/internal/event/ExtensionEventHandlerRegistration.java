package com.elfmcys.ysm.api.internal.event;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.List;

final class ExtensionEventHandlerRegistration {

    private ExtensionEventHandlerRegistration() {}

    static Outcome prepare(String handlerBinaryName, ClassLoader loader) throws Failure {
        String checkerBinaryName = checkerBinaryName(handlerBinaryName);
        Class<?> checker;
        try {
            checker = Class.forName(checkerBinaryName, true, loader);
        } catch (ClassNotFoundException exception) {
            throw new Failure(Stage.CHECKER_MISSING, checkerBinaryName, "Generated compatibility checker is missing.",
                exception);
        } catch (LinkageError | RuntimeException exception) {
            throw new Failure(Stage.CHECKER_LOAD, checkerBinaryName, "Cannot load the generated compatibility checker.",
                exception);
        }

        CheckResult checkResult = invokeCheck(checker, checkerBinaryName);
        if (!checkResult.compatible) {
            return new Outcome(null, checkerBinaryName, checkResult.status, checkResult.coverageComplete,
                checkResult.issues);
        }

        Object handler = instantiateHandler(handlerBinaryName, loader);
        return new Outcome(handler, checkerBinaryName, checkResult.status, checkResult.coverageComplete,
            checkResult.issues);
    }

    static String checkerBinaryName(String handlerBinaryName) {
        return com.elfmcys.ysm.api.internal.CompatibilityQualification.checkerName(handlerBinaryName);
    }

    private static CheckResult invokeCheck(Class<?> checker, String checkerBinaryName) throws Failure {
        try {
            var result = com.elfmcys.ysm.api.internal.CompatibilityQualification.check(checker);
            return new CheckResult(result.status(), result.compatible(), result.coverageComplete(), result.issues());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            throw new Failure(Stage.CHECK, checkerBinaryName, "Compatibility check failed.", unwrap(exception));
        }
    }

    private static Object instantiateHandler(String handlerBinaryName, ClassLoader loader) throws Failure {
        try {
            Class<?> handlerType = Class.forName(handlerBinaryName, true, loader);
            int modifiers = handlerType.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers) || handlerType.isInterface()) {
                throw new IllegalStateException("Handler must be a public concrete class.");
            }
            return handlerType.getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            throw new Failure(Stage.HANDLER_LOAD, handlerBinaryName, "Cannot load or instantiate the event handler.",
                unwrap(exception));
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }

    record Outcome(Object handler, String checkerBinaryName, String status, boolean coverageComplete,
        List<String> issues) {
        boolean shouldRegister() {
            return handler != null;
        }

        boolean hasWarnings() {
            return !coverageComplete || !issues.isEmpty() || "COMPATIBLE_WITH_WARNINGS".equals(status);
        }
    }

    private record CheckResult(String status, boolean compatible, boolean coverageComplete, List<String> issues) {
    }

    enum Stage {
        CHECKER_MISSING, CHECKER_LOAD, CHECK, HANDLER_LOAD
    }

    static final class Failure extends Exception {
        private final Stage stage;
        private final String subject;

        Failure(Stage stage, String subject, String message, Throwable cause) {
            super(message, cause);
            this.stage = stage;
            this.subject = subject;
        }

        Stage stage() {
            return stage;
        }

        String subject() {
            return subject;
        }
    }
}
