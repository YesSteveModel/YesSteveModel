package com.elfmcys.ysm.api.internal.processor;

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
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuiltinDeclarationProcessorTest {
    @TempDir
    Path temporary;

    @Test
    void resolvesSubstitutedGenericEntityComponentSubject() throws Exception {
        var result = compile("sample.Valid",
            """
                package sample;
                import com.elfmcys.ysm.expression.host.EntityComponent;
                import com.elfmcys.ysm.api.molang.MolangContextView;
                import com.elfmcys.ysm.expression.host.builtin.*;
                import net.minecraft.world.entity.Entity;
                import net.minecraft.world.entity.LivingEntity;

                abstract class Base<T extends Entity> implements EntityComponent<T> {}
                abstract class Middle<T extends Entity> extends Base<T> {}

                @BuiltinComponent(value="generic_test")
                public final class Valid extends Middle<LivingEntity> {
                    public Valid(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {}
                    @BuiltinFunction(namespace="ysm",value="test_generic", effect=com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
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
    void rejectsMethodContextThatNarrowsTheComponentSubject() throws Exception {
        var result = compile("sample.Narrow",
            """
                package sample;
                import com.elfmcys.ysm.expression.host.EntityComponent;
                import com.elfmcys.ysm.api.molang.MolangContextView;
                import com.elfmcys.ysm.expression.host.builtin.*;
                import net.minecraft.world.entity.Entity;
                import net.minecraft.world.entity.LivingEntity;

                @BuiltinComponent(value="narrow_test")
                public final class Narrow implements EntityComponent<Entity> {
                    @BuiltinFunction(namespace="ysm",value="test_narrow", effect=com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
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
        var sources = List.of(
            """
                package sample;
                import com.elfmcys.ysm.expression.host.*;
                import com.elfmcys.ysm.api.molang.*;
                import com.elfmcys.ysm.expression.host.builtin.*;
                import net.minecraft.world.entity.Entity;
                @BuiltinComponent(value="raw")
                public final class InvalidRaw implements EntityComponent {
                    @BuiltinFunction(namespace="ysm",value="test_raw", effect=com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
                    public float value(MolangContextView<Entity> context){ return 0; }
                }
                """,
            """
                package sample;
                import com.elfmcys.ysm.expression.host.*;
                import com.elfmcys.ysm.api.molang.*;
                import com.elfmcys.ysm.expression.host.builtin.*;
                import net.minecraft.world.entity.Entity;
                @BuiltinComponent(value="unresolved")
                public final class InvalidUnresolved<T extends Entity>
                        implements EntityComponent<T> {
                    @BuiltinFunction(namespace="ysm",value="test_unresolved", effect=com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
                    public float value(MolangContextView<Entity> context){ return 0; }
                }
                """,
            """
                package sample;
                import com.elfmcys.ysm.expression.host.*;
                import com.elfmcys.ysm.api.molang.*;
                import com.elfmcys.ysm.expression.host.builtin.*;
                import net.minecraft.world.entity.Entity;
                import net.minecraft.world.entity.LivingEntity;
                @BuiltinComponent(value="mismatch")
                public final class InvalidMismatch implements EntityComponent<Entity> {
                    @BuiltinFunction(namespace="ysm",value="test_mismatch", effect=com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
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
            return new Compilation(success, generated, text);
        }
    }

    private record Compilation(boolean success, Path generated, String diagnostics) {
    }
}
