package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.client.compat.backpack.sophisticated.SophisticatedBuiltinComponent;
import com.elfmcys.ysm.util.UnsafeUtil;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OptionalCapabilityAdmissionTest {
    @TempDir
    Path temporary;

    @BeforeAll
    static void initializeMinecraftEntityClasses() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        var gate = Bootstrap.class.getDeclaredField("isBootstrapped");
        gate.setAccessible(true);
        boolean previous = gate.getBoolean(null);
        gate.setBoolean(null, true);
        try {
            BuiltInRegistries.REGISTRY.size();
            Class.forName(ArmorStand.class.getName(), true, OptionalCapabilityAdmissionTest.class.getClassLoader());
        } finally {
            gate.setBoolean(null, previous);
        }
    }

    @Test
    void loadedButIncompatibleCapabilityKeepsTypedMissingAndCreatesNoComponent() throws InstantiationException {
        var admission = new OptionalCapabilityAdmission("sophisticatedbackpacks"::equals,
            Map.of("sophisticatedbackpacks", "3.20.0"), getClass().getClassLoader());
        var initialized = new AtomicBoolean();
        admission.initialize(OptionalCapability.SOPHISTICATED_BACKPACKS, () -> initialized.set(true), () -> true);

        var result = admission.finish();
        var host = ExpressionHost.create(result.availability());
        assertTrue(host.registry()
            .variable(new dev.semaweave.SymbolKey(HostSymbol.CTRL_HAS_SOPHISTICATED_BACKPACK.stableId())).isEmpty());
        var subject = (ArmorStand)UnsafeUtil.getUnsafe().allocateInstance(ArmorStand.class);
        var holder = BuiltinDeclarations.createComponents(TestComponentHolders.owner(subject), host);

        assertTrue(result.availability().capabilities().isEmpty());
        assertEquals("3.20.0", result.availability().versions().get("sophisticatedbackpacks"));
        assertFalse(initialized.get());
        assertTrue(holder.exact(SophisticatedBuiltinComponent.class).isEmpty());
    }

    @Test
    void numericBuildVersionRemainsNewerThanTheSupportedBaseRelease() {
        assertTrue(OptionalCapability.SOPHISTICATED_BACKPACKS.supportsVersion("3.24.25.1566-forge", false));
        assertFalse(OptionalCapability.SOPHISTICATED_BACKPACKS.supportsVersion("3.24.25-rc1", false));
    }

    @Test
    void isolatedRuntimeJarWithMissingReferencedMethodFailsQualification() throws Exception {
        var compileApi = Files.createDirectories(temporary.resolve("compile-api"));
        compile(compileApi, "fixture.api.Dependency", """
            package fixture.api;
            public final class Dependency {
                public static int value() { return 7; }
            }
            """, List.of());
        var component = Files.createDirectories(temporary.resolve("component"));
        compile(component, "fixture.Component", """
            package fixture;
            public final class Component {
                public int value() { return fixture.api.Dependency.value(); }
            }
            """, List.of("-classpath", compileApi.toString()));

        var runtime = Files.createDirectories(temporary.resolve("runtime"));
        compile(runtime, "fixture.api.Dependency", """
            package fixture.api;
            public final class Dependency {
            }
            """, List.of());
        var componentClass = component.resolve("fixture/Component.class");
        var runtimeComponent = runtime.resolve("fixture/Component.class");
        Files.createDirectories(runtimeComponent.getParent());
        Files.copy(componentClass, runtimeComponent);
        var jar = jar(runtime, temporary.resolve("incompatible-runtime.jar"));

        try (var loader =
            new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            assertThrows(NoSuchMethodError.class, () -> OptionalLinkageVerifier.verify(loader,
                Set.of("fixture.Component"), name -> name.startsWith("fixture.api."), "fixture.Component"::equals));
        }
    }

    private static void compile(Path output, String binaryName, String source, List<String> extraOptions)
        throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new javax.tools.DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var unit = new SimpleJavaFileObject(URI.create("string:///" + binaryName.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            var options = new java.util.ArrayList<String>();
            options.add("--release");
            options.add("17");
            options.add("-d");
            options.add(output.toString());
            options.addAll(extraOptions);
            var task = compiler.getTask(null, manager, diagnostics, options, null, List.of(unit));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
    }

    private static Path jar(Path root, Path output) throws IOException {
        try (var stream = new JarOutputStream(Files.newOutputStream(output)); var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                stream.putNextEntry(new JarEntry(root.relativize(file).toString().replace('\\', '/')));
                stream.write(Files.readAllBytes(file));
                stream.closeEntry();
            }
        }
        return output;
    }
}
