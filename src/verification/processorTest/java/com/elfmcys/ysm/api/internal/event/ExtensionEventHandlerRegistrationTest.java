package com.elfmcys.ysm.api.internal.event;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionEventHandlerRegistrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void derivesTheProcessorCheckerNameForNestedHandlers() {
        assertEquals("sample.Outer_InnerCompatibilityChecker",
            ExtensionEventHandlerRegistration.checkerBinaryName("sample.Outer$Inner"));
    }

    @Test
    void doesNotLoadHandlerUntilCheckerAcceptsIt() throws Exception {
        compileFixtures();
        System.clearProperty("ysm.event.incompatible.loaded");
        System.clearProperty("ysm.event.compatible.loaded");

        try (var loader =
            new URLClassLoader(new URL[] {temporaryDirectory.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var incompatible = ExtensionEventHandlerRegistration.prepare("sample.IncompatibleHandler", loader);
            assertFalse(incompatible.shouldRegister());
            assertFalse(Boolean.getBoolean("ysm.event.incompatible.loaded"));

            var compatible = ExtensionEventHandlerRegistration.prepare("sample.CompatibleHandler", loader);
            assertTrue(compatible.shouldRegister());
            assertTrue(compatible.hasWarnings());
            assertTrue(Boolean.getBoolean("ysm.event.compatible.loaded"));
        } finally {
            System.clearProperty("ysm.event.incompatible.loaded");
            System.clearProperty("ysm.event.compatible.loaded");
        }
    }

    private void compileFixtures() throws Exception {
        Files.createDirectories(temporaryDirectory);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> options = List.of("--release", "17", "-proc:none", "-d", temporaryDirectory.toString());
            boolean success = Boolean.TRUE.equals(compiler
                .getTask(null, fileManager, null, options, null, List.of(source("sample.IncompatibleHandler", """
                    package sample;
                    public final class IncompatibleHandler {
                        static { System.setProperty(
                                "ysm.event.incompatible.loaded", "true"); }
                        public IncompatibleHandler() {}
                    }
                    """), source("sample.IncompatibleHandlerCompatibilityChecker", """
                    package sample;
                    public final class IncompatibleHandlerCompatibilityChecker {
                        public static Result check() { return new Result(); }
                        public static final class Result {
                            public String status() { return "INCOMPATIBLE"; }
                            public boolean isCompatible() { return false; }
                            public boolean coverageComplete() { return true; }
                            public java.util.List<String> issues() {
                                return java.util.List.of("missing method");
                            }
                        }
                    }
                    """), source("sample.CompatibleHandler", """
                    package sample;
                    public final class CompatibleHandler {
                        public CompatibleHandler() {
                            System.setProperty(
                                    "ysm.event.compatible.loaded", "true");
                        }
                    }
                    """), source("sample.CompatibleHandlerCompatibilityChecker", """
                    package sample;
                    public final class CompatibleHandlerCompatibilityChecker {
                        public static Result check() { return new Result(); }
                        public static final class Result {
                            public String status() {
                                return "COMPATIBLE_WITH_WARNINGS";
                            }
                            public boolean isCompatible() { return true; }
                            public boolean coverageComplete() { return false; }
                            public java.util.List<String> issues() {
                                return java.util.List.of("dynamic target");
                            }
                        }
                    }
                    """))).call());
            assertTrue(success);
        }
    }

    private static JavaFileObject source(String binaryName, String content) {
        return new SimpleJavaFileObject(
            URI.create("string:///" + binaryName.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
            JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }
}
