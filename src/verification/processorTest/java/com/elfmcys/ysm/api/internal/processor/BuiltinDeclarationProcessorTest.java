package com.elfmcys.ysm.api.internal.processor;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuiltinDeclarationProcessorTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsAnEmptyExportName() throws Exception {
        var result = compile("sample.Unnamed", """
            package sample;
            import com.elfmcys.ysm.expression.annotation.*;

            @BuiltinComponent("names")
            public final class Unnamed {
                @BuiltinFunction("")
                public static int readValue() { return 0; }
            }
            """);
        assertTrue(!result.success(), result::diagnostics);
        assertTrue(result.diagnostics().contains("invalid canonical name"), result::diagnostics);
    }

    @Test
    void rejectsUnresolvedBooleanAttributesWithoutAbortingProcessing() throws Exception {
        var result = compile("sample.UnresolvedFlags", """
            package sample;
            import com.elfmcys.ysm.api.annotation.MolangFunction;
            import com.elfmcys.ysm.api.annotation.YsmEntityComponent;
            import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
            import com.elfmcys.ysm.expression.annotation.BuiltinFunction;

            public final class UnresolvedFlags {
                @BuiltinComponent("builtin_test")
                public static final class Builtin {
                    @BuiltinFunction(value = "read", alwaysOrigin = MissingFlags.ORIGIN)
                    public static int read() { return 1; }
                }

                @YsmEntityComponent("extension_test")
                public static final class Extension {
                    @MolangFunction(value = "act", action = MissingFlags.ACTION)
                    public static void act() {}
                }
            }
            """);

        assertTrue(!result.success(), result::diagnostics);
        assertTrue(result.diagnostics().contains("alwaysOrigin must be a boolean constant"), result::diagnostics);
        assertTrue(result.diagnostics().contains("action must be a boolean constant"), result::diagnostics);
    }

    @Test
    void preservesBuiltinOverloadOrderAcrossAdapterNumberBoundaries() throws Exception {
        var unrelated = IntStream.range(0, 9).mapToObj(index -> """
            @BuiltinFunction("read%d") public static int read%d() { return 0; }
            """.formatted(index, index)).collect(Collectors.joining());
        var result = compile("sample.Overloads", """
            package sample;
            import com.elfmcys.ysm.expression.annotation.*;

            @BuiltinComponent("overload_test")
            public final class Overloads {
                %s
                @BuiltinFunction("select") public static int select(String id) { return 1; }
                @BuiltinFunction("select") public static int select(int id) { return 2; }
            }
            """.formatted(unrelated));

        assertTrue(result.success(), result::diagnostics);
        try (var input =
            Files.newInputStream(result.classes().resolve(DeclarationSchema.RESOURCE_ROOT + "sample.Overloads.bin"))) {
            var members = DeclarationSchema.decode(input).members();
            var carriers = members.stream().filter(member -> member.name().equals("select"))
                .sorted(Comparator.comparing(DeclarationSchema.Member::adapter))
                .map(member -> member.fixed().get(0).carrier()).toList();
            assertEquals(List.of("java.lang.String", "int"), carriers);
        }
    }

    @Test
    void resolvesSubstitutedGenericEntityComponentSubject() throws Exception {
        var result = compile("sample.Valid", """
            package sample;
            import com.elfmcys.ysm.expression.host.EntityComponent;
            import com.elfmcys.ysm.api.molang.MolangContextView;
            import com.elfmcys.ysm.expression.annotation.*;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.LivingEntity;

            abstract class Base<T extends Entity> implements EntityComponent<T> {}
            abstract class Middle<T extends Entity> extends Base<T> {}

            @BuiltinComponent("generic_test")
            public final class Valid extends Middle<LivingEntity> {
                public Valid(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {}
                @BuiltinFunction(value="test_generic", alwaysOrigin = true, namespace="ysm")
                public float value(MolangContextView<LivingEntity> context) {
                    return 1;
                }
            }
            """);

        assertTrue(result.success(), result::diagnostics);
        var helper = Files.readString(result.generated().resolve("sample/ValidMolangBindings.java"));
        assertTrue(helper.contains("net.minecraft.world.entity.LivingEntity"));
        assertTrue(helper.contains("new sample.Valid("));
        assertTrue(!helper.contains("semaweave"));
    }

    @Test
    void infersComponentFromLifecycleOverridesOnlyForBuiltins() throws Exception {
        var result = compile("sample.HookDeclarations", """
            package sample;
            import com.elfmcys.ysm.api.annotation.MolangFunction;
            import com.elfmcys.ysm.api.annotation.YsmEntityComponent;
            import com.elfmcys.ysm.client.entity.AnimatableEntity;
            import com.elfmcys.ysm.expression.host.EntityComponent;
            import com.elfmcys.ysm.expression.annotation.*;
            import net.minecraft.world.entity.Entity;

            public final class HookDeclarations {
                public abstract static class Base implements EntityComponent<Entity> {
                    @Override public void endEval() {}
                }

                public interface DefaultHook extends EntityComponent<Entity> {
                    @Override default void tick(int previous, int current) {}
                }

                @BuiltinComponent("direct")
                public static final class Direct implements EntityComponent<Entity> {
                    public Direct(AnimatableEntity<Entity> owner) {}
                    @Override public void tick(int previous, int current) {}
                    @BuiltinFunction("read") public static int read() { return 1; }
                }

                @BuiltinComponent("inherited")
                public static final class Inherited extends Base {
                    public Inherited(AnimatableEntity<Entity> owner) {}
                    @BuiltinFunction("read") public static int read() { return 2; }
                }

                @BuiltinComponent("interface_default")
                public static final class InterfaceDefault implements DefaultHook {
                    public InterfaceDefault(AnimatableEntity<Entity> owner) {}
                    @BuiltinFunction("read") public static int read() { return 3; }
                }

                @BuiltinComponent("overload")
                public static final class Overload implements EntityComponent<Entity> {
                    private int tick;

                    public void tick(int current) { tick = current; }
                    @BuiltinFunction("read") public static int read() { return 4; }
                }

                @YsmEntityComponent("extension_hooks")
                public static final class Extension extends Base {
                    @MolangFunction("read") public static int read() { return 5; }
                }
            }
            """);

        assertTrue(result.success(), result::diagnostics);
        for (String name : List.of("Direct", "Inherited", "InterfaceDefault", "Overload", "Extension")) {
            var path =
                result.classes().resolve(DeclarationSchema.RESOURCE_ROOT + "sample.HookDeclarations$" + name + ".bin");
            try (var input = Files.newInputStream(path)) {
                var schema = DeclarationSchema.decode(input);
                String expectedSubject =
                    name.equals("Overload") || name.equals("Extension") ? "" : "net.minecraft.world.entity.Entity";
                assertEquals(expectedSubject, schema.componentSubject(), name);
                assertTrue(schema.members().get(0).isStatic(), name);
            }
        }
    }

    @Test
    void rejectsMethodContextThatNarrowsTheComponentSubject() throws Exception {
        var result = compile("sample.Narrow", """
            package sample;
            import com.elfmcys.ysm.expression.host.EntityComponent;
            import com.elfmcys.ysm.api.molang.MolangContextView;
            import com.elfmcys.ysm.expression.annotation.*;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.LivingEntity;

            @BuiltinComponent("narrow_test")
            public final class Narrow implements EntityComponent<Entity> {
                @BuiltinFunction(value="test_narrow", alwaysOrigin = true, namespace="ysm")
                public float value(MolangContextView<LivingEntity> context) {
                    return 1;
                }
            }
            """);

        assertTrue(!result.success(), result::diagnostics);
        assertTrue(result.diagnostics().contains("exactly"), result::diagnostics);
    }

    @Test
    void rejectsRawUnresolvedAndMismatchedComponentSubjects() throws Exception {
        var sources = List.of("""
            package sample;
            import com.elfmcys.ysm.expression.host.*;
            import com.elfmcys.ysm.api.molang.*;
            import com.elfmcys.ysm.expression.annotation.*;
            import net.minecraft.world.entity.Entity;
            @BuiltinComponent("raw")
            public final class InvalidRaw implements EntityComponent {
                @BuiltinFunction(value="test_raw", alwaysOrigin = true, namespace="ysm")
                public float value(MolangContextView<Entity> context){ return 0; }
            }
            """, """
            package sample;
            import com.elfmcys.ysm.expression.host.*;
            import com.elfmcys.ysm.api.molang.*;
            import com.elfmcys.ysm.expression.annotation.*;
            import net.minecraft.world.entity.Entity;
            @BuiltinComponent("unresolved")
            public final class InvalidUnresolved<T extends Entity>
                    implements EntityComponent<T> {
                @BuiltinFunction(value="test_unresolved", alwaysOrigin = true, namespace="ysm")
                public float value(MolangContextView<Entity> context){ return 0; }
            }
            """, """
            package sample;
            import com.elfmcys.ysm.expression.host.*;
            import com.elfmcys.ysm.api.molang.*;
            import com.elfmcys.ysm.expression.annotation.*;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.LivingEntity;
            @BuiltinComponent("mismatch")
            public final class InvalidMismatch implements EntityComponent<Entity> {
                @BuiltinFunction(value="test_mismatch", alwaysOrigin = true, namespace="ysm")
                public float value(MolangContextView<LivingEntity> context){ return 0; }
            }
            """);

        for (var index = 0; index < sources.size(); index++) {
            var className = switch (index) {
                case 0 -> "sample.InvalidRaw";
                case 1 -> "sample.InvalidUnresolved";
                default -> "sample.InvalidMismatch";
            };
            var result = compile(className, sources.get(index));
            assertTrue(!result.success(), className);
            assertTrue(!result.diagnostics().isEmpty(), result::diagnostics);
        }
    }

    private Compilation compile(String name, String source) throws Exception {
        var root = Files.createTempDirectory(temporary, "builtin-processor-");
        var classes = Files.createDirectories(root.resolve("classes"));
        var generated = Files.createDirectories(root.resolve("generated"));
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var unit = new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            var options = List.of("--release", "17", "-classpath", System.getProperty("java.class.path"), "-d",
                classes.toString(), "-s", generated.toString());
            var task = compiler.getTask(null, manager, diagnostics, options, null, List.of(unit));
            task.setProcessors(List.of(new UnifiedDeclarationProcessor()));
            var success = task.call();
            var text = diagnostics.getDiagnostics().stream()
                .map(diagnostic -> diagnostic.getKind() + ":" + diagnostic.getLineNumber() + ":"
                    + diagnostic.getMessage(Locale.ROOT))
                .reduce("", (left, right) -> left + right + System.lineSeparator());
            if (!success) {
                assertTrue(diagnostics.getDiagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR));
            }
            return new Compilation(success, classes, generated, text);
        }
    }

    private record Compilation(boolean success, Path classes, Path generated, String diagnostics) {
    }
}
