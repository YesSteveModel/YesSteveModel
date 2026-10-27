package com.elfmcys.ysm.api.internal.processor;

import com.elfmcys.ysm.api.internal.processor.asm.ClassReader;
import com.elfmcys.ysm.api.internal.processor.asm.Opcodes;
import com.elfmcys.ysm.api.internal.processor.asm.tree.ClassNode;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionCompatibilityProcessorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void emitsAbiVisibleBodyFingerprintWithoutAnnotatedRoots() throws Exception {
        Path output = temporaryDirectory.resolve("fingerprint-only");
        compile(output, List.of(), true, List.of(source("sample.OwnedHelper", """
            package sample;
            public final class OwnedHelper {
                public static int body() { return 42; }
            }
            """)));

        Path fingerprint = Files.walk(output)
            .filter(path -> path.toString().replace('\\', '/').contains("compatibility/fingerprint/Module_")
                && path.toString().endsWith(".class"))
            .findFirst().orElseThrow();
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(fingerprint)).accept(node, 0);
        assertTrue((node.access & Opcodes.ACC_PUBLIC) != 0);
        assertEquals(0, node.access & Opcodes.ACC_SYNTHETIC);
        assertTrue(
            node.fields.stream().anyMatch(field -> field.name.equals("BODY_HASH") && field.value instanceof String));
    }

    @Test
    void followsLambdaImplementationAndChecksYsmMethodDescriptor() throws Exception {
        Path compileApi = temporaryDirectory.resolve("compile-api");
        compile(compileApi, List.of(), false, apiSources(true));

        Path extension = temporaryDirectory.resolve("extension");
        compile(extension, List.of(compileApi), true, List.of(source("sample.Extension", """
            package sample;

            import com.elfmcys.ysm.api.Api;
            import com.elfmcys.ysm.api.annotation.YsmExtension;
            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.brigadier.CommandDispatcher;
            import net.minecraft.server.packs.resources.ResourceManager;

            public final class Extension {
                @YsmExtension
                public void feature(
                        ResourceManager manager,
                        PoseStack poseStack,
                        CommandDispatcher<Object> dispatcher) {
                    Runnable action = () -> Api.accept(manager, poseStack, dispatcher);
                    action.run();
                }

                @YsmExtension
                public void feature(int ignored) {}
            }
            """), source("sample.Bootstrap", """
            package sample;
            public final class Bootstrap {
                public static boolean enabled() {
                    return ExtensionCompatibilityChecker.checkFeature().isCompatible();
                }
            }
            """)));

        Path manifest = extension.resolve("META-INF/ysm-compat/sample/Extension.json");
        assertJsonManifest(manifest, "feature");
        assertFalse(Files.exists(extension.resolve("META-INF/ysm-compat/sample/Extension.ysmc")));
        assertTrue(Files.walk(extension).anyMatch(
            path -> path.getFileName().toString().contains("__TypeProbe_") && path.toString().endsWith(".class")));
        List<String> probeDescriptors = typeProbeDescriptors(extension);
        assertTrue(probeDescriptors.contains("Lcom/mojang/blaze3d/vertex/PoseStack;"));
        assertTrue(probeDescriptors.contains("Lnet/minecraft/server/packs/resources/ResourceManager;"));
        assertFalse(probeDescriptors.contains("Lcom/mojang/brigadier/CommandDispatcher;"));

        assertEquals("COMPATIBLE_WITH_WARNINGS", checkerStatus(extension, compileApi));

        Path noYsmRuntime = temporaryDirectory.resolve("no-ysm-runtime");
        Files.createDirectories(noYsmRuntime);
        assertEquals("YSM_NOT_LOADED", checkerStatus(extension, noYsmRuntime));

        Path runtimeApi = temporaryDirectory.resolve("runtime-api");
        compile(runtimeApi, List.of(), false, apiSources(false));
        assertEquals("INCOMPATIBLE", checkerStatus(extension, runtimeApi));
    }

    @Test
    void classCheckRequiresYsmOverrideDeclaration() throws Exception {
        Path compileApi = temporaryDirectory.resolve("override-compile-api");
        compile(compileApi, List.of(), false, overrideApiSources(true));

        Path extension = temporaryDirectory.resolve("override-extension");
        compile(extension, List.of(compileApi), true, List.of(source("sample.OverrideExtension", """
            package sample;

            import com.elfmcys.ysm.api.Base;
            import com.elfmcys.ysm.api.annotation.YsmExtension;

            @YsmExtension
            public final class OverrideExtension extends Base {
                @Override
                public void tick() {
                    super.tick();
                }
            }
            """)));

        assertTrue(Files.walk(extension).anyMatch(
            path -> path.getFileName().toString().contains("__MethodProbe_") && path.toString().endsWith(".class")));
        assertEquals("COMPATIBLE",
            checkerStatus(extension, compileApi, "sample.OverrideExtensionCompatibilityChecker", "check"));

        Path runtimeApi = temporaryDirectory.resolve("override-runtime-api");
        compile(runtimeApi, List.of(), false, overrideApiSources(false));
        assertEquals("INCOMPATIBLE",
            checkerStatus(extension, runtimeApi, "sample.OverrideExtensionCompatibilityChecker", "check"));
    }

    @Test
    void eventHandlerGeneratesClassCompatibilityCheck() throws Exception {
        Path api = temporaryDirectory.resolve("event-handler-api");
        compile(api, List.of(), false, apiSources(true));

        Path extension = temporaryDirectory.resolve("event-handler-extension");
        compile(extension, List.of(api), true, List.of(source("sample.EventHandler", """
            package sample;

            import com.elfmcys.ysm.api.annotation.YsmEventHandler;

            @YsmEventHandler
            public final class EventHandler {
                public void handle() {}
            }
            """)));

        Path manifest = extension.resolve("META-INF/ysm-compat/sample/EventHandler.json");
        assertJsonManifest(manifest, "$class");
        assertFalse(Files.exists(extension.resolve("META-INF/ysm-compat/sample/EventHandler.ysmc")));
        assertTrue(Files.isRegularFile(extension.resolve("sample/EventHandlerCompatibilityChecker.class")));
        assertEquals("COMPATIBLE", checkerStatus(extension, api, "sample.EventHandlerCompatibilityChecker", "check"));
    }

    @Test
    void rejectsEventHandlerWithoutPublicNoArgConstructor() throws Exception {
        Path extension = temporaryDirectory.resolve("invalid-event-handler");
        compileFailure(extension, List.of(), true, List.of(source("sample.EventHandler", """
            package sample;

            import com.elfmcys.ysm.api.annotation.YsmEventHandler;

            @YsmEventHandler
            public final class EventHandler {
                private EventHandler(String value) {}
            }
            """)));
    }

    @Test
    void rejectsRedundantEventHandlerAndClassExtensionAnnotations() throws Exception {
        Path extension = temporaryDirectory.resolve("duplicate-event-handler-marker");
        compileFailure(extension, List.of(), true, List.of(source("sample.EventHandler", """
            package sample;

            import com.elfmcys.ysm.api.annotation.YsmEventHandler;
            import com.elfmcys.ysm.api.annotation.YsmExtension;

            @YsmEventHandler
            @YsmExtension
            public final class EventHandler {}
            """)));
    }

    private String checkerStatus(Path extension, Path api) throws Exception {
        return checkerStatus(extension, api, "sample.ExtensionCompatibilityChecker", "checkFeature");
    }

    private String checkerStatus(Path extension, Path api, String checkerName, String methodName) throws Exception {
        URL gson = Class.forName("com.google.gson.Gson").getProtectionDomain().getCodeSource().getLocation();
        URL[] urls = {extension.toUri().toURL(), api.toUri().toURL(), gson};
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Class<?> checker = Class.forName(checkerName, true, loader);
            Object result = checker.getMethod(methodName).invoke(null);
            String status = result.getClass().getMethod("status").invoke(result).toString();
            if (status.equals("CHECK_FAILED")) {
                throw new AssertionError(result.toString());
            }
            return status;
        }
    }

    private List<JavaFileObject> overrideApiSources(boolean overrideTick) {
        List<JavaFileObject> sources = new ArrayList<>(apiSources(true));
        String method = overrideTick ? "@Override public void tick() {}" : "";
        sources.add(source("com.elfmcys.ysm.api.Base", """
            package com.elfmcys.ysm.api;
            public class Base extends net.minecraft.world.entity.Entity {
                %s
            }
            """.formatted(method)));
        return sources;
    }

    private List<JavaFileObject> apiSources(boolean includeMethod) {
        List<JavaFileObject> sources = new ArrayList<>();
        sources.add(source("com.mojang.blaze3d.vertex.PoseStack", """
            package com.mojang.blaze3d.vertex;
            public class PoseStack {}
            """));
        sources.add(source("com.mojang.brigadier.CommandDispatcher", """
            package com.mojang.brigadier;
            public class CommandDispatcher<S> {}
            """));
        sources.add(source("net.minecraft.world.entity.Entity", """
            package net.minecraft.world.entity;
            public class Entity {
                public void tick() {}
            }
            """));
        sources.add(source("net.minecraft.server.packs.resources.ResourceManager", """
            package net.minecraft.server.packs.resources;
            public interface ResourceManager {}
            """));
        sources.add(source("com.elfmcys.ysm.YesSteveModel", """
            package com.elfmcys.ysm;
            public final class YesSteveModel {
                public static boolean isAvailable() { return true; }
            }
            """));
        String method = includeMethod ? """
            public static void accept(
                    ResourceManager manager,
                    com.mojang.blaze3d.vertex.PoseStack poseStack,
                    com.mojang.brigadier.CommandDispatcher<Object> dispatcher) {}
            """ : "";
        sources.add(source("com.elfmcys.ysm.api.Api", """
            package com.elfmcys.ysm.api;
            import net.minecraft.server.packs.resources.ResourceManager;
            public final class Api {
                %s
            }
            """.formatted(method)));
        return sources;
    }

    private List<String> typeProbeDescriptors(Path extension) throws IOException {
        Path probe = Files.walk(extension)
            .filter(
                path -> path.getFileName().toString().contains("__TypeProbe_") && path.toString().endsWith(".class"))
            .findFirst().orElseThrow();
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(probe)).accept(node, 0);
        return node.fields.stream().map(field -> field.desc).toList();
    }

    private void assertJsonManifest(Path manifest, String groupKey) throws IOException {
        assertTrue(Files.isRegularFile(manifest));
        String json = Files.readString(manifest);
        assertTrue(json.startsWith("{\n"));
        assertTrue(json.endsWith("}\n"));
        var document = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(1, document.get("version").getAsInt());
        assertTrue(document.get("canaryProbe").isJsonPrimitive());
        assertTrue(document.getAsJsonObject("groups").has(groupKey));
    }

    private void compile(Path output, List<Path> extraClasspath, boolean runProcessor, List<JavaFileObject> sources)
        throws IOException {
        compile(output, extraClasspath, runProcessor, sources, true);
    }

    private void compileFailure(Path output, List<Path> extraClasspath, boolean runProcessor,
        List<JavaFileObject> sources) throws IOException {
        compile(output, extraClasspath, runProcessor, sources, false);
    }

    private void compile(Path output, List<Path> extraClasspath, boolean runProcessor, List<JavaFileObject> sources,
        boolean expectedSuccess) throws IOException {
        Files.createDirectories(output);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
            compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            String classpath = System.getProperty("java.class.path");
            for (Path path : extraClasspath) {
                classpath += File.pathSeparator + path;
            }
            List<String> options =
                new ArrayList<>(List.of("--release", "17", "-classpath", classpath, "-d", output.toString()));
            if (!runProcessor) {
                options.add("-proc:none");
            } else {
                options.add("-Aysm.compat.moduleId=test-fixture");
            }
            JavaCompiler.CompilationTask task =
                compiler.getTask(null, fileManager, diagnostics, options, null, sources);
            if (runProcessor) {
                task.setProcessors(List.of(new ExtensionCompatibilityProcessor()));
            }
            boolean success = Boolean.TRUE.equals(task.call());
            assertEquals(expectedSuccess, success, () -> diagnostics.getDiagnostics().toString());
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
