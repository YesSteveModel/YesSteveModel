package com.elfmcys.ysm.expression.oracle;

import com.elfmcys.ysm.api.internal.processor.ExtensionCompatibilityProcessor;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.host.*;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent release-fixture oracle for the public extension boundary (Frozen O-04, O-05; A-03).
 *
 * <p>
 * Extensions are compiled by a separate javac invocation against a JAR that contains only the public API, then
 * discovered from the real class bytes of real files whose issuing mod id is decided by the file, not by the
 * declaration. Expected outcomes come from the frozen dataset's {@code extension_cases}.
 */
public final class PublicExtensionAdmissionOracleTest {
    @TempDir
    Path temporary;

    @Test
    void publicExtensionBoundaryMatchesTheFrozenExtensionCases() throws Exception {
        var api = apiJar();
        var valid = extension(api, "Valid", "oracle_valid", """
            @MolangFunction(effect=MolangEffect.CONTEXT)
            public static float ping(MolangContextView<net.minecraft.world.entity.Entity> c){return 1f;}
            """);
        var control = extension(api, "Control", "oracle_control", """
            @MolangFunction(effect=MolangEffect.CONTEXT)
            public static float pong(MolangContextView<net.minecraft.world.entity.Entity> c){return 2f;}
            """);
        var spoof = extension(api, "Spoof", "oracle_valid", """
            @MolangFunction(effect=MolangEffect.CONTEXT)
            public static float spoofed(MolangContextView<net.minecraft.world.entity.Entity> c){return 0f;}
            """);
        var brokenA = extension(api, "BrokenA", "oracle_broken", """
            @MolangFunction(effect=MolangEffect.CONTEXT)
            public static float duplicated(MolangContextView<net.minecraft.world.entity.Entity> c){return 1f;}
            """);
        var brokenB = extension(api, "BrokenB", "oracle_broken", """
            @MolangFunction(effect=MolangEffect.CONTEXT)
            public static float duplicated(MolangContextView<net.minecraft.world.entity.Entity> c){return 2f;}
            """);
        runInSharedLoader("verifyExtensionBoundary", List.of(valid, control, spoof, brokenA, brokenB),
            List.of("oracle_valid", "oracle_control", "oracle_evil", "oracle_broken", "oracle_broken"));
    }

    @Test
    void reservedRootIsRefusedAtTheCompileBoundary() throws Exception {
        var api = apiJar();
        var compiled = compile(api, temporary.resolve("reserved"), "Reserved", "ysm", """
            @MolangFunction(effect=MolangEffect.CONTEXT)
            public static float reserved_claim(MolangContextView<net.minecraft.world.entity.Entity> c){return 0f;}
            """);
        assertFalse(compiled.ok, "claiming the reserved ysm root must be refused: " + compiled.diagnostics);
        assertTrue(compiled.diagnostics.toLowerCase(Locale.ROOT).contains("reserved"), compiled.diagnostics);
    }

    @Test
    void internalCarrierAndHostCapabilityAreNotReachableFromThePublicApi() throws Exception {
        var api = apiJar();
        for (var signature : List.of(
            "public static dev.semaweave.RuntimeValue leak(MolangContextView<net.minecraft.world.entity.Entity> c){return null;}",
            "public static float leak(com.elfmcys.ysm.expression.host.MolangContext c){return 1f;}",
            "public static float leak(MolangContextView<net.minecraft.world.entity.Entity> c){c.reset();return 1f;}",
            "public static float leak(MolangContextView<net.minecraft.world.entity.Entity> c){c.batch();return 1f;}",
            "public static float leak(MolangContextView<net.minecraft.world.entity.Entity> c){c.execute();return 1f;}")) {
            var compiled = compile(api, temporary.resolve("leak" + Math.abs(signature.hashCode())), "Leak",
                "oracle_valid", "@MolangFunction(effect=MolangEffect.CONTEXT) " + signature);
            assertFalse(compiled.ok, "the public API must not expose engine carriers or host contexts: " + signature
                + " / " + compiled.diagnostics);
        }
    }

    @Test
    void inaccessibleRecordRejectsTheWholeDeclarationAtTheCommonBoundary() throws Exception {
        var api = apiJar();
        var compiled = compile(api, temporary.resolve("hidden"), "Hidden", "oracle_fixture",
            """
                private record HiddenRecord(float value) {}
                @MolangFunction(effect=MolangEffect.CONTEXT)
                public static float plain(MolangContextView<net.minecraft.world.entity.Entity> c,float value){return value;}
                @MolangFunction(effect=MolangEffect.CONTEXT)
                public static HiddenRecord leak(MolangContextView<net.minecraft.world.entity.Entity> c){return new HiddenRecord(1);}
                """);
        assertFalse(compiled.ok, compiled.diagnostics);
        assertTrue(compiled.diagnostics.contains("publicly visible"), compiled.diagnostics);
    }

    // ---------------------------------------------------------------- shared-loader verifiers

    /** Runs inside a loader that can resolve both the test classes and the extension files. */
    public static void verifyExtensionBoundary(String[] paths, String[] owners) {
        var loader = PublicExtensionAdmissionOracleTest.class.getClassLoader();
        var files = new ArrayList<IModFileInfo>();
        for (var index = 0; index < paths.length; index++) {
            files.add(scan(Path.of(paths[index]), owners[index]));
        }
        var sources = MolangExtensionLoader.discover(files, loader);
        assertEquals(paths.length, sources.size(), sources.toString());
        var admission = MolangExtensions.admit(HostAvailability.none(), sources);
        var functions = admission.host().registry().functions().keySet().stream()
            .map(dev.semaweave.SymbolKey::canonical).collect(java.util.stream.Collectors.toSet());

        assertTrue(functions.contains("oracle_valid:ping"),
            "a valid public extension stays admitted: " + admission.diagnostics());
        assertTrue(functions.contains("oracle_control:pong"),
            "an unrelated valid extension coexists with rejected ones: " + admission.diagnostics());
        assertFalse(functions.contains("oracle_valid:spoofed"),
            "a self-reported mod id from a foreign issuing file must not be admitted");
        assertTrue(
            admission.host().declarations().stream().filter(d -> !d.schema().builtin())
                .flatMap(d -> d.schema().members().stream()).noneMatch(m -> m.namespace().equals("ysm")),
            "no extension may register inside the ysm namespace");
        assertTrue(admission.rejectedMods().contains("oracle_broken"),
            "a duplicate declaration must reject its mod atomically: " + admission.diagnostics());
        assertFalse(functions.contains("oracle_broken:duplicated"), admission.diagnostics().toString());
        assertTrue(admission.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains("duplicate")),
            admission.diagnostics().toString());
        assertTrue(admission.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains("explicit mod id")),
            admission.diagnostics().toString());
        assertTrue(functions.contains("ysm:mod_version"),
            "the built-in capability set must not depend on extension admission");
        assertEquals(2.0f, executeControl(admission.host()), "an admitted unrelated extension stays callable");
    }

    private static float executeControl(ExpressionHost host) {
        var descriptor =
            host.registry().function(new dev.semaweave.SymbolKey("oracle_control:pong")).orElseThrow().methods().get(0);
        var member = descriptor.method();
        assertEquals(dev.semaweave.registry.InvocationKind.STATIC, member.invocationKind(),
            "an admitted extension must bind to its own static member");
        try {
            var method = member.owner().getMethod(member.methodName(), member.methodType().parameterArray());
            var state = MolangContext.observation(new EntityView() {}, new RenderView() {},
                new com.elfmcys.ysm.expression.host.ControlledHostFixture(),
                com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.PRODUCER),
                new InvocationProvenance("oracle", "control", 0, 1, 1), () -> false, () -> true);
            return (float)method.invoke(null, state);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("admitted extension is not callable", failure);
        }
    }

    private void runInSharedLoader(String method, List<Path> jars, List<String> owners) throws Exception {
        var urls = new ArrayList<URL>();
        urls.add(com.elfmcys.ysm.expression.host.AdmissionRuntimeFixture.classes(temporary.resolve("runtime")).toUri()
            .toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        for (Path jar : jars) {
            urls.add(jar.toUri().toURL());
        }
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            loader.loadClass(getClass().getName()).getMethod(method, String[].class, String[].class).invoke(null,
                jars.stream().map(Path::toString).toArray(String[]::new), owners.toArray(String[]::new));
        }
    }

    // ---------------------------------------------------------------- fixture machinery

    private Path apiJar() throws Exception {
        var path = temporary.resolve("ysm-public-api.jar");
        var root = Path.of(MolangContextView.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var jar = new JarOutputStream(Files.newOutputStream(path));
            var files = Files.walk(root.resolve("com/elfmcys/ysm/api"))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var name = root.relativize(file).toString().replace('\\', '/');
                if (!(name.contains("/annotation/") || name.contains("/molang/"))
                    || name.contains("MolangContextLinkage")) {
                    continue;
                }
                jar.putNextEntry(new JarEntry(name));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
        return path;
    }

    private static final class CompileResult {
        private final boolean ok;
        private final String diagnostics;

        private CompileResult(boolean ok, String diagnostics) {
            this.ok = ok;
            this.diagnostics = diagnostics;
        }
    }

    private Path extension(Path api, String name, String mod, String body) throws Exception {
        var output = Files.createDirectory(temporary.resolve(name));
        var compiled = compile(api, output, name, mod, body);
        assertTrue(compiled.ok, "extension source must compile against the public API only: " + compiled.diagnostics);
        var path = temporary.resolve(name + ".jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(path)); var files = Files.walk(output)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var bytes = Files.readAllBytes(file);
                assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("semaweave"),
                    "public extension bytecode must not reference engine internals");
                jar.putNextEntry(new JarEntry(output.relativize(file).toString().replace('\\', '/')));
                jar.write(bytes);
                jar.closeEntry();
            }
        }
        return path;
    }

    private CompileResult compile(Path api, Path output, String name, String mod, String body) throws Exception {
        Files.createDirectories(output);
        var source = "package independent;import com.elfmcys.ysm.api.annotation.*;"
            + "import com.elfmcys.ysm.api.molang.*;" + "@YsmEntityComponent(value=\"" + mod + "\",modId=\"" + mod
            + "\") public class " + name + "{" + body + "}";
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var unit = new SimpleJavaFileObject(URI.create("string:///independent/" + name + ".java"),
                JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            var task = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath",
                    api + File.pathSeparator + System.getProperty("java.class.path"), "-d", output.toString()),
                null, List.of(unit));
            task.setProcessors(List.of(new com.elfmcys.ysm.api.internal.processor.UnifiedDeclarationProcessor(),
                new ExtensionCompatibilityProcessor()));
            var ok = task.call();
            return new CompileResult(ok, diagnostics.getDiagnostics().toString());
        }
    }

    private static IModFileInfo scan(Path path, String owner) {
        var scan = new net.minecraftforge.forgespi.language.ModFileScanData();
        try (var jar = new JarFile(path.toFile())) {
            for (var entry : Collections.list(jar.entries())) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (var input = jar.getInputStream(entry)) {
                    new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                        String name;

                        @Override
                        public void visit(int version, int access, String internalName, String signature, String parent,
                            String[] interfaces) {
                            name = internalName;
                        }

                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (!descriptor.equals("Lcom/elfmcys/ysm/api/annotation/YsmEntityComponent;")) {
                                return null;
                            }
                            Map<String, Object> values = new HashMap<>();
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String key, Object value) {
                                    values.put(key, value);
                                }

                                @Override
                                public void visitEnd() {
                                    scan.getAnnotations()
                                        .add(new net.minecraftforge.forgespi.language.ModFileScanData.AnnotationData(
                                            Type.getType(descriptor), ElementType.TYPE, Type.getObjectType(name), name,
                                            values));
                                }
                            };
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot scan " + path, failure);
        }
        IModInfo mod = proxy(IModInfo.class, Map.of("getModId", owner));
        IModFile file = proxy(IModFile.class, Map.of("getScanResult", scan, "getFilePath", path));
        return proxy(IModFileInfo.class, Map.of("getMods", List.of(mod), "getFile", file));
    }

    private static <T> T proxy(Class<T> type, Map<String, Object> fields) {
        return type
            .cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, arguments) -> {
                if (fields.containsKey(method.getName())) {
                    return fields.get(method.getName());
                }
                throw new UnsupportedOperationException(method.toString());
            }));
    }
}
