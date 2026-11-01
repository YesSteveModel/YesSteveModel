package com.elfmcys.ysm.api.internal.processor;

import static org.junit.jupiter.api.Assertions.*;
import com.elfmcys.ysm.api.molang.DeclarationSchema;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

import com.elfmcys.ysm.api.molang.MolangContextView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MolangDeclarationProcessorTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsNestedOptionalMetadataWithoutExhaustingTheStack() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(bytes);
        out.writeUTF("YSM-MOLANG-CALLABLE");
        out.writeUTF(DeclarationSchema.VERSION);
        out.writeUTF("sample.Owner");
        out.writeUTF("sample.OwnerMolangBindings");
        out.writeUTF("sample_mod");
        out.writeUTF("");
        out.writeBoolean(false);
        out.writeBoolean(false);
        out.writeUTF("");
        out.writeInt(0);
        out.writeInt(1);
        out.writeUTF("sample_mod");
        out.writeUTF("value");
        out.writeUTF("value");
        out.writeUTF("(Lcom/elfmcys/ysm/api/molang/MolangContextView;)Ljava/util/Optional;");
        out.writeUTF("m0");
        out.writeUTF("net.minecraft.world.entity.Entity");
        out.writeBoolean(true);
        out.writeUTF("CONTEXT");
        out.writeUTF("CONTEXT");
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(0);
        out.writeBoolean(false);
        // Invalid nested Optional must be rejected before resource traversal can exhaust the JVM stack.
        int wrappers = 20_000;
        for (int index = 0; index < wrappers; index++) {
            out.writeUTF("OPTIONAL");
            out.writeUTF("java.util.Optional");
            out.writeUTF("");
            out.writeBoolean(true);
        }
        out.writeUTF("FLOAT");
        out.writeUTF("java.lang.Float");
        out.writeUTF("");
        out.writeBoolean(false);
        out.writeInt(0);
        for (int index = 0; index < wrappers; index++)
            out.writeInt(0);
        out.writeBoolean(false);
        assertTrue(bytes.size() < 1_048_576);
        var failure = assertThrows(java.io.IOException.class,
            () -> DeclarationSchema.decode(new java.io.ByteArrayInputStream(bytes.toByteArray())));
        assertEquals("flat schema depth limit exceeded", failure.getMessage());
    }

    @Test
    void emitsIndependentValuesWithoutEngineReferences() throws Exception {
        Path output = compile("sample.Extension",
            """
                package sample;
                import com.elfmcys.ysm.api.annotation.*;
                import com.elfmcys.ysm.api.molang.*;
                import java.util.*;
                @YsmEntityComponent("sample_mod") public class Extension {
                    @YsmEntityComponent("nested_mod") public static class Nested {
                        @MolangFunction(effect=MolangEffect.CONTEXT)
                        public static float fixed(MolangContextView<net.minecraft.world.entity.Entity> context){return 1f;}
                    }
                    public record Pair(int count,float value,String label,boolean flag) {
                        @Override public boolean equals(Object other){throw new AssertionError("custom equals");}
                    }
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static Pair pair(MolangContextView<net.minecraft.world.entity.Entity> context){return new Pair(3,1f,"",false);}
                    @MolangFunction(effect=MolangEffect.CONTEXT,minArgs=3,maxArgs=5)
                    public static int bounded(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,int first,boolean... tail){return argc;}
                    @MolangFunction(effect=MolangEffect.CONTEXT,minArgs=0)
                    public static int optional(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,@DefaultValue("0x1_00_00_01") int first,
                            @DefaultValue("9") int second){return first;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static String text(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("line\\nnext\\t\\\"quoted\\\"") String value){return value;}
                }
                """,
            true);
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> helper = loader.loadClass("sample.ExtensionMolangBindings");
            var declaration = DeclarationSchema
                .decode(new java.io.ByteArrayInputStream((byte[])helper.getMethod("schema").invoke(null)));
            var nested = DeclarationSchema.decode(new java.io.ByteArrayInputStream(
                (byte[])loader.loadClass("sample.Extension$NestedMolangBindings").getMethod("schema").invoke(null)));
            assertEquals("nested_mod", nested.namespace());
            assertEquals(4, declaration.members().size());
            var type = declaration.members().stream().filter(f -> f.javaName().equals("pair")).findFirst().orElseThrow()
                .result();
            Class<?> record = loader.loadClass(type.carrier());
            var constructor = record.getConstructor(int.class, float.class, String.class, boolean.class);
            Object original = constructor.newInstance(16777216, -0.0f, "", false);
            Object positive = constructor.newInstance(16777217, 0.0f, "", false);
            assertEquals(true,
                helper.getMethod(type.prefix() + "Equal", record, record).invoke(null, original, positive));
            assertEquals(16777216f, helper.getMethod(type.prefix() + "Read_count", record).invoke(null, positive));
            assertThrows(NoSuchMethodException.class, () -> helper.getMethod(type.prefix() + "Copy", record));
            Object nan = constructor.newInstance(7, Float.NaN, "", false);
            assertEquals(false, helper.getMethod(type.prefix() + "Equal", record, record).invoke(null, nan, nan));
            assertThrows(NoSuchMethodException.class, () -> helper.getMethod(type.prefix() + "Zero"));
            var optional =
                declaration.members().stream().filter(f -> f.javaName().equals("optional")).findFirst().orElseThrow();
            assertEquals(List.of(16777217, 9), parsedDefaults(optional));
            var text =
                declaration.members().stream().filter(f -> f.javaName().equals("text")).findFirst().orElseThrow();
            assertEquals(List.of("line\nnext\t\"quoted\""), parsedDefaults(text));
        }
        try (var files = Files.walk(output)) {
            for (Path file : files.filter(p -> p.toString().endsWith("MolangBindings.class")).toList()) {
                String pool = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(pool.contains("semaweave"));
            }
        }
    }

    @Test
    void rejectsInvalidDeclarationsAtSource() throws Exception {
        List<String> bodies = List.of(
            "@MolangFunction(minArgs=-2) public static float f(MolangContextView<net.minecraft.world.entity.Entity> c){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public float f(MolangContextView<net.minecraft.world.entity.Entity> c){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float f(Object c){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float f(MolangContextView<net.minecraft.world.entity.Entity> c,int argc,float... a){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float f(MolangContextView<net.minecraft.world.entity.Entity> c,int argc,@DefaultValue(\"1\") float a,float b){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float f(MolangContextView<net.minecraft.world.entity.Entity> c,int argc,@DefaultValue(\"2147483648\") int a){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float f(MolangContextView<net.minecraft.world.entity.Entity> c,int argc,@DefaultValue(\"1_e2\") float a){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float f(MolangContextView<net.minecraft.world.entity.Entity> c,int argc,@DefaultValue(\"0x1_p0f\") float a){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float f(MolangContextView<net.minecraft.world.entity.Entity> c,int argc,@DefaultValue(\"1+2\") float a){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float f(MolangContextView<net.minecraft.world.entity.Entity> c,int argc,@DefaultValue(\"1e100f\") float a){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float f(MolangContextView<net.minecraft.world.entity.Entity> c,int argc,@DefaultValue(\"1e-50f\") float a){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float f(MolangContextView<net.minecraft.world.entity.Entity> c,int argc,@DefaultValue(\"1e-50\") float a){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float f(MolangContextView<net.minecraft.world.entity.Entity> c,int argc,@DefaultValue(\"0x0.000001p-126f\") float a){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float f(MolangContextView<net.minecraft.world.entity.Entity> c,int argc,@DefaultValue(\"0x1.0\") float a){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static int f(MolangContextView<net.minecraft.world.entity.Entity> c,int argc,@DefaultValue(\"08\") int a){return 0;}",
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static java.util.List<?> f(MolangContextView<net.minecraft.world.entity.Entity> c){return null;}",
            "public record Cycle(Cycle child){} @MolangFunction(effect=MolangEffect.CONTEXT) public static Cycle f(MolangContextView<net.minecraft.world.entity.Entity> c){return null;}",
            "public record Collision(float Value,float value){} @MolangFunction(effect=MolangEffect.CONTEXT) public static Collision f(MolangContextView<net.minecraft.world.entity.Entity> c){return null;}");
        int index = 0;
        for (String body : bodies)
            compile("sample.Invalid" + (index++),
                "package sample;import com.elfmcys.ysm.api.annotation.*;import com.elfmcys.ysm.api.molang.*;"
                    + "@YsmEntityComponent(\"sample_mod\") public class Invalid" + (index - 1) + "{" + body + "}",
                false);
    }

    @Test
    void rejectsUnsuffixedIntegerLiteralWiderThanInt() throws Exception {
        List<String> literals = List.of("2147483648", "+2147483648", "0x1_0000_0000", "-0x1_0000_0000",
            "040_000_000_000", "0b1_00000000_00000000_00000000_00000000", "9223372036854775808L",
            "0x1_0000_0000_0000_0000L", "02_000_000_000_000_000_000_000L",
            "0b1_00000000_00000000_00000000_00000000_00000000_00000000_00000000_00000000L");
        int index = 0;
        for (String literal : literals) {
            String className = "WideDefault" + (index++);
            compile("sample." + className, """
                package sample;
                import com.elfmcys.ysm.api.annotation.*;
                import com.elfmcys.ysm.api.molang.*;
                @YsmEntityComponent("sample_mod") public class %s {
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float value(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("%s") float value){return value;}
                }
                """.formatted(className, literal), false);
        }
    }

    @Test
    void acceptsJava17NumericLiteralBoundaries() throws Exception {
        Path output = compile("sample.HexDefault",
            """
                package sample;
                import com.elfmcys.ysm.api.annotation.*;
                import com.elfmcys.ysm.api.molang.*;
                @YsmEntityComponent("sample_mod") public class HexDefault {
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static int hexF(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("0x1f") int value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static int hexD(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("0x1d") int value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static int binary(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("0b1__1111") int value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static int octal(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("03_7") int value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static int minimum(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("-2_147_483_648") int value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float decimalIntMaximum(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("2_147_483_647") float value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float hexadecimalIntBits(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("0xffff_ffff") float value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float octalIntBits(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("0377_7777_7777") float value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float binaryIntBits(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("0b11111111_11111111_11111111_11111111") float value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float decimalLongMaximum(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("9_223_372_036_854_775_807L") float value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float decimalLongMinimum(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("-9_223_372_036_854_775_808L") float value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float hexadecimalLongBits(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("0xffff_ffff_ffff_ffffL") float value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float octalLongBits(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("01_777_777_777_777_777_777_777L") float value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float binaryLongBits(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("0b11111111_11111111_11111111_11111111_11111111_11111111_11111111_11111111L") float value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float rounded(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("0x1.000002p0f") float value){return value;}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float subnormal(MolangContextView<net.minecraft.world.entity.Entity> context,int argc,
                            @DefaultValue("0x0.000002p-126f") float value){return value;}
                }
                """,
            true);
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader())) {
            var declaration = DeclarationSchema.decode(new java.io.ByteArrayInputStream(
                (byte[])loader.loadClass("sample.HexDefaultMolangBindings").getMethod("schema").invoke(null)));
            var functions = declaration.members().stream()
                .collect(java.util.stream.Collectors.toMap(f -> f.javaName().toLowerCase(Locale.ROOT), f -> f));
            assertEquals(List.of(31), parsedDefaults(functions.get("hexf")));
            assertEquals(List.of(29), parsedDefaults(functions.get("hexd")));
            assertEquals(List.of(31), parsedDefaults(functions.get("binary")));
            assertEquals(List.of(31), parsedDefaults(functions.get("octal")));
            assertEquals(List.of(Integer.MIN_VALUE), parsedDefaults(functions.get("minimum")));
            assertEquals(List.of((float)Integer.MAX_VALUE), parsedDefaults(functions.get("decimalintmaximum")));
            assertEquals(List.of(-1.0f), parsedDefaults(functions.get("hexadecimalintbits")));
            assertEquals(List.of(-1.0f), parsedDefaults(functions.get("octalintbits")));
            assertEquals(List.of(-1.0f), parsedDefaults(functions.get("binaryintbits")));
            assertEquals(List.of((float)Long.MAX_VALUE), parsedDefaults(functions.get("decimallongmaximum")));
            assertEquals(List.of((float)Long.MIN_VALUE), parsedDefaults(functions.get("decimallongminimum")));
            assertEquals(List.of(-1.0f), parsedDefaults(functions.get("hexadecimallongbits")));
            assertEquals(List.of(-1.0f), parsedDefaults(functions.get("octallongbits")));
            assertEquals(List.of(-1.0f), parsedDefaults(functions.get("binarylongbits")));
            assertEquals(List.of(Float.intBitsToFloat(0x3f800001)), parsedDefaults(functions.get("rounded")));
            assertEquals(List.of(Float.MIN_VALUE), parsedDefaults(functions.get("subnormal")));
        }
    }

    @Test
    void bothMarkersUseStaticMemberFunctionsAndInheritedGenericRules() throws Exception {
        for (boolean builtin : List.of(false, true)) {
            String marker = builtin ? "@BuiltinComponent(value=\"matrix\")" : "@YsmEntityComponent(\"matrix_mod\")";
            String function =
                builtin ? "@BuiltinFunction(namespace=\"matrix\",value=\"value\",effect=MolangEffect.CONTEXT)"
                    : "@MolangFunction(effect=MolangEffect.CONTEXT)";
            String variable =
                builtin ? "@BuiltinFunction(namespace=\"matrix\",value=\"read\",effect=MolangEffect.CONTEXT)"
                    : "@MolangFunction(effect=MolangEffect.CONTEXT)";
            String staticFunction =
                builtin ? "@BuiltinFunction(namespace=\"matrix\",value=\"fixed\",effect=MolangEffect.CONTEXT)"
                    : "@MolangFunction(effect=MolangEffect.CONTEXT)";
            String staticVariable =
                builtin ? "@BuiltinFunction(namespace=\"matrix\",value=\"constant\",effect=MolangEffect.CONTEXT)"
                    : "@MolangFunction(effect=MolangEffect.CONTEXT)";
            Path output = compile("sample.Matrix", ("""
                package sample;
                import com.elfmcys.ysm.api.annotation.*;
                import com.elfmcys.ysm.api.molang.*;
                import com.elfmcys.ysm.expression.host.*;
                import com.elfmcys.ysm.expression.host.builtin.*;
                import net.minecraft.world.entity.*;
                abstract class Base<E extends Entity,R> implements EntityComponent<E> {
                    %s public R value(MolangContextView<E> context,String value){return null;}
                    %s public int read(MolangContextView<E> context){return 0;}
                }
                %s public final class Matrix extends Base<Entity,Matrix.Pair> {
                    public Matrix(com.elfmcys.ysm.client.entity.AnimatableEntity<Entity> owner) {}
                    public record Pair(int count) {}
                    %s public static int fixed(MolangContextView<LivingEntity> context){return 1;}
                    %s public static boolean constant(MolangContextView<Entity> context){return false;}
                }
                """).formatted(function, variable, marker, staticFunction, staticVariable), true);
            var schema = DeclarationSchema
                .decode(Files.newInputStream(output.resolve(DeclarationSchema.RESOURCE_ROOT + "sample.Matrix.bin")));
            assertEquals(4, schema.members().size());
            assertEquals(2, schema.members().stream().filter(DeclarationSchema.Member::isStatic).count());
            assertTrue(schema.members().stream().allMatch(m -> m.contextFreeTarget().isEmpty()));
            var value = schema.members().stream().filter(m -> m.javaName().equals("value")).findFirst().orElseThrow();
            assertEquals("RECORD", value.result().kind());
            assertEquals("java.lang.String", value.fixed().get(0).carrier());
            assertTrue(value.descriptor().contains("Ljava/lang/Object;"));
            assertEquals("net.minecraft.world.entity.Entity", schema.componentSubject());
            assertEquals("net.minecraft.world.entity.LivingEntity", schema.members().stream()
                .filter(m -> m.javaName().equals("fixed")).findFirst().orElseThrow().subject());
            String pool = new String(Files.readAllBytes(output.resolve("sample/MatrixMolangBindings.class")),
                java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(pool.contains("semaweave"));
        }
    }

    @Test
    void closedResultShapesAreSharedForBothMarkers() throws Exception {
        for (boolean builtin : List.of(false, true)) {
            String marker = builtin ? "@BuiltinComponent(value=\"budget\")" : "@YsmEntityComponent(\"budget_mod\")";
            String function =
                builtin ? "@BuiltinFunction(namespace=\"budget\",value=\"value\",effect=MolangEffect.CONTEXT)"
                    : "@MolangFunction(effect=MolangEffect.CONTEXT)";
            for (String type : List.of("java.util.List<Float>", "java.util.List<Boolean>", "java.util.List<?>",
                "java.util.List<java.util.List<String>>", "java.util.Optional<Float>", "java.util.OptionalInt",
                "com.elfmcys.ysm.expression.host.ModelSoundId", "Nested", "Boxed", "Generic<String>")) {
                compile("sample.InvalidShape",
                    "package sample;import com.elfmcys.ysm.api.annotation.*;import com.elfmcys.ysm.api.molang.*;"
                        + "import com.elfmcys.ysm.expression.host.builtin.*;" + marker + " public class InvalidShape {"
                        + "public record Flat(float x){} public record Nested(Flat child){} public record Boxed(Float x){} public record Generic<T>(T x){}"
                        + function + " public static " + type
                        + " value(MolangContextView<net.minecraft.world.entity.Entity> c){return null;}}",
                    false);
            }
        }
    }

    @Test
    void nullableAndFlatRecipesUseBothAuthorEntrypoints() throws Exception {
        for (boolean builtin : List.of(false, true)) {
            String marker = builtin ? "@BuiltinComponent(value=\"values\")" : "@YsmEntityComponent(\"values_mod\")";
            String[] types = {"Float", "Integer", "Boolean", "String", "Pair", "it.unimi.dsi.fastutil.floats.FloatList",
                "java.util.List<String>", "java.util.List<Pair>"};
            StringBuilder body = new StringBuilder("public record Pair(float camelCase){}");
            for (int index = 0; index < types.length; index++) {
                body.append(builtin
                    ? "@BuiltinFunction(namespace=\"values\",value=\"f" + index + "\",effect=MolangEffect.CONTEXT)"
                    : "@MolangFunction(effect=MolangEffect.CONTEXT)");
                if (index == 4)
                    body.append("@MolangNullable ");
                body.append("public static ").append(types[index]).append(" f").append(index)
                    .append("(MolangContextView<net.minecraft.world.entity.Entity> c){return null;}");
            }
            Path output = compile("sample.Values",
                "package sample;import com.elfmcys.ysm.api.annotation.*;"
                    + "import com.elfmcys.ysm.api.molang.*;import com.elfmcys.ysm.expression.host.builtin.*;" + marker
                    + " public class Values {" + body + "}",
                true);
            try (var input =
                Files.newInputStream(output.resolve(DeclarationSchema.RESOURCE_ROOT + "sample.Values.bin"))) {
                var schema = DeclarationSchema.decode(input);
                assertEquals(8, schema.members().size());
                assertEquals(4, schema.members().stream().filter(DeclarationSchema.Member::mayReturnMissing).count());
                var pair = schema.members().stream().filter(member -> member.javaName().equals("f4")).findFirst()
                    .orElseThrow().result();
                assertEquals("camelCase", pair.fields().get(0).name());
                try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader())) {
                    var owner = loader.loadClass("sample.Values");
                    for (var member : schema.members()) {
                        var method = owner.getMethod(member.javaName(), MolangContextView.class);
                        com.elfmcys.ysm.api.molang.DeclarationLinkage.verify(schema, owner, method, member,
                            MolangContextView.class, net.minecraft.world.entity.Entity.class);
                    }
                    var helper = loader.loadClass("sample.ValuesMolangBindings");
                    var floats = schema.members().get(5).result();
                    assertEquals(-0.0f, helper
                        .getMethod(floats.prefix() + "Read", it.unimi.dsi.fastutil.floats.FloatList.class, int.class)
                        .invoke(null, it.unimi.dsi.fastutil.floats.FloatList.of(-0.0f), 0));
                }
            }
        }
    }

    @Test
    void onlyDedicatedRecordNullabilityAndScalarParametersAreAdmitted() throws Exception {
        for (String type : List.of("Float", "Integer", "Boolean", "Pair", "java.util.List<String>",
            "it.unimi.dsi.fastutil.floats.FloatList")) {
            compile("sample.Parameter",
                "package sample;import com.elfmcys.ysm.api.annotation.*;import com.elfmcys.ysm.api.molang.*;"
                    + "@YsmEntityComponent(\"values_mod\") public class Parameter {public record Pair(float x){}"
                    + "@MolangFunction(effect=MolangEffect.CONTEXT) public static float value(MolangContextView<net.minecraft.world.entity.Entity> c,"
                    + type + " value){return 0;}}",
                false);
        }
        for (String type : List.of("Float", "String", "java.util.List<String>")) {
            compile("sample.Nullable",
                "package sample;import com.elfmcys.ysm.api.annotation.*;import com.elfmcys.ysm.api.molang.*;"
                    + "@YsmEntityComponent(\"values_mod\") public class Nullable {@MolangNullable "
                    + "@MolangFunction(effect=MolangEffect.CONTEXT) public static " + type
                    + " value(MolangContextView<net.minecraft.world.entity.Entity> c){return null;}}",
                false);
        }
        var retention =
            com.elfmcys.ysm.api.annotation.MolangNullable.class.getAnnotation(java.lang.annotation.Retention.class);
        assertEquals(java.lang.annotation.RetentionPolicy.RUNTIME, retention.value());
        assertArrayEquals(new java.lang.annotation.ElementType[] {java.lang.annotation.ElementType.METHOD},
            com.elfmcys.ysm.api.annotation.MolangNullable.class.getAnnotation(java.lang.annotation.Target.class)
                .value());
    }

    @Test
    void overloadGroupsRejectNormalizedDuplicatesAndInconsistentContracts() throws Exception {
        for (boolean builtin : List.of(false, true)) {
            String marker = builtin ? "@BuiltinComponent(value=\"overloads\")" : "@YsmEntityComponent(\"values_mod\")";
            String function =
                builtin ? "@BuiltinFunction(namespace=\"values\",value=\"pick\",effect=MolangEffect.CONTEXT)"
                    : "@MolangFunction(value=\"pick\",effect=MolangEffect.CONTEXT)";
            String header =
                "package sample;import com.elfmcys.ysm.api.annotation.*;import com.elfmcys.ysm.api.molang.*;"
                    + "import com.elfmcys.ysm.expression.host.builtin.*;" + marker + " public class Overloads {";
            String first = function
                + " public static float number(MolangContextView<net.minecraft.world.entity.Entity> c,int x){return x;}";
            compile("sample.Overloads", header + first + function
                + " public static float text(MolangContextView<net.minecraft.world.entity.Entity> c,String x){return 0;}}",
                true);
            compile("sample.Overloads", header + first + function
                + " public static float duplicate(MolangContextView<net.minecraft.world.entity.Entity> c,float x){return x;}}",
                false);
            compile("sample.Overloads", header + first + function
                + " public static Float nullable(MolangContextView<net.minecraft.world.entity.Entity> c,String x){return null;}}",
                true);
            compile("sample.Overloads", header + first + function
                + " public static int required(MolangContextView<net.minecraft.world.entity.Entity> c,String x){return 1;}}",
                false);
            compile("sample.Overloads", header + function
                + " public static int required(MolangContextView<net.minecraft.world.entity.Entity> c,int x){return 1;}"
                + function
                + " public static Integer optional(MolangContextView<net.minecraft.world.entity.Entity> c,String x){return null;}}",
                false);
            compile("sample.Overloads", header + first + function
                + " public static float subject(MolangContextView<net.minecraft.world.entity.LivingEntity> c,String x){return 0;}}",
                false);
        }
    }

    @Test
    void ownerConstructorMustResolveTheExactClosedSubject() throws Exception {
        String header = "package sample;import com.elfmcys.ysm.api.annotation.*;import com.elfmcys.ysm.api.molang.*;"
            + "import com.elfmcys.ysm.expression.host.*;import com.elfmcys.ysm.client.entity.AnimatableEntity;"
            + "import net.minecraft.world.entity.*;@YsmEntityComponent(\"ctor_mod\") "
            + "public class Constructor implements EntityComponent<LivingEntity>{";
        String member =
            "@MolangFunction(effect=MolangEffect.CONTEXT) public int read(MolangContextView<LivingEntity> c){return 1;}}";
        for (String constructor : List.of("public Constructor(){}", "public Constructor(LivingEntity subject){}",
            "public Constructor(AnimatableEntity owner){}", "public Constructor(AnimatableEntity<Entity> owner){}",
            "public Constructor(AnimatableEntity<? extends LivingEntity> owner){}",
            "public <E extends LivingEntity> Constructor(AnimatableEntity<E> owner){}"))
            compile("sample.Constructor", header + constructor + member, false);
        compile("sample.Constructor", header + "public Constructor(AnimatableEntity<LivingEntity> owner){}" + member,
            true);
    }

    @Test
    void generatedHelperForwardsAllFiveHooksWithoutConstructingStaticOnlyDeclarations() throws Exception {
        Path output = compile("sample.Hooks",
            """
                package sample;
                import com.elfmcys.ysm.api.annotation.*;
                import com.elfmcys.ysm.api.molang.*;
                import com.elfmcys.ysm.expression.host.*;
                import com.elfmcys.ysm.client.entity.AnimatableEntity;
                import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
                import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
                import net.minecraft.world.entity.Entity;
                @YsmEntityComponent("hooks_mod") public class Hooks implements EntityComponent<Entity> {
                    public final java.util.List<String> calls = new java.util.ArrayList<>();
                    public Hooks(AnimatableEntity<Entity> owner){}
                    public void setRenderTarget(ModelRenderTarget target){calls.add("target");}
                    public void tick(int previous,int current){calls.add("tick:"+previous+":"+current);}
                    public void update(float previous,float current,float partial){calls.add("update:"+previous+":"+current+":"+partial);}
                    public void beginEval(AnimationEvent<?> event){calls.add("begin");}
                    public void endEval(){calls.add("end");}
                    @MolangFunction(effect=MolangEffect.CONTEXT) public int read(MolangContextView<Entity> c){return 1;}
                    @YsmEntityComponent("static_hooks_mod") public static class StaticOnly {
                        public static int constructed;
                        private StaticOnly(){constructed++;throw new AssertionError();}
                        @MolangFunction(effect=MolangEffect.CONTEXT) public static int read(MolangContextView<Entity> c){return 2;}
                    }
                }
                """,
            true);
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> helper = loader.loadClass("sample.HooksMolangBindings");
            Object instance = helper.getConstructor(com.elfmcys.ysm.client.entity.AnimatableEntity.class)
                .newInstance(new Object[] {null});
            Object provider = helper.getMethod("provider").invoke(instance);
            helper.getMethod("setRenderTarget", com.elfmcys.ysm.model.resource.client.ModelRenderTarget.class)
                .invoke(instance, new Object[] {null});
            helper.getMethod("beginEval", com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent.class)
                .invoke(instance, new Object[] {null});
            helper.getMethod("tick", int.class, int.class).invoke(instance, 2, 7);
            helper.getMethod("update", float.class, float.class, float.class).invoke(instance, 1f, 3f, 0.5f);
            helper.getMethod("endEval").invoke(instance);
            assertEquals(List.of("target", "begin", "tick:2:7", "update:1.0:3.0:0.5", "end"),
                provider.getClass().getField("calls").get(provider));
            Class<?> statics = loader.loadClass("sample.Hooks$StaticOnlyMolangBindings");
            assertFalse(com.elfmcys.ysm.expression.host.EntityComponent.class.isAssignableFrom(statics));
            assertEquals(0, loader.loadClass("sample.Hooks$StaticOnly").getField("constructed").getInt(null));
        }
    }

    @Test
    void derivesCanonicalNamesArityPresenceAndExactViews() throws Exception {
        Path output = compile("sample.Weapon", """
            package sample;
            import com.elfmcys.ysm.api.annotation.*;
            import com.elfmcys.ysm.api.molang.*;
            import net.minecraft.world.entity.Entity;
            @YsmEntityComponent(value="weapon",modId="combat_bridge") public class Weapon {
                private Weapon(){throw new AssertionError();}
                @MolangFunction public static float isReady(MolangContextView<Entity> c){return 7;}
                @MolangFunction(value="is_ready",namespace="combat")
                public static Float ready(MolangContextView<Entity> c){return null;}
                @MolangFunction public static int count(MolangContextView<Entity> c){return 1;}
                @MolangFunction public static Integer maybe(MolangContextView<Entity> c){return null;}
                @MolangFunction public static int optional(MolangContextView<Entity> c,int argc,
                    @DefaultValue("11") int value){return argc;}
                @MolangFunction(minArgs=1,maxArgs=16)
                public static int tail(MolangContextView<Entity> c,int argc,String... keys){return argc;}
                @MolangFunction(effect=MolangEffect.ACTION)
                public static void reset(MolangContextView<Entity> c){c.submit((com.elfmcys.ysm.api.molang.PreparedEffect)() -> {});}
                @MolangFunction(effect=MolangEffect.ACTION)
                public static void signal(MolangContextView<Entity> c){}
            }
            """, true);
        var schema = DeclarationSchema
            .decode(Files.newInputStream(output.resolve(DeclarationSchema.RESOURCE_ROOT + "sample.Weapon.bin")));
        assertEquals("weapon", schema.namespace());
        assertEquals("combat_bridge", schema.modId());
        var byName = schema.members().stream().collect(java.util.stream.Collectors
            .toMap(DeclarationSchema.Member::javaName, java.util.function.Function.identity()));
        assertEquals("weapon:isready", byName.get("isReady").symbol());
        assertEquals("combat:is_ready", byName.get("ready").symbol());
        assertTrue(byName.get("isReady").mayReturnMissing());
        assertTrue(byName.get("ready").mayReturnMissing());
        assertFalse(byName.get("count").mayReturnMissing());
        assertTrue(byName.get("maybe").mayReturnMissing());
        assertEquals(0, byName.get("optional").minimum());
        assertEquals(1, byName.get("optional").maximum());
        assertEquals(List.of(11), parsedDefaults(byName.get("optional")));
        assertEquals(1, byName.get("tail").minimum());
        assertEquals(16, byName.get("tail").maximum());
        assertEquals("ACTION", byName.get("reset").effect());
        assertEquals("ACTION", byName.get("signal").effect());
        assertEquals("", byName.get("signal").contextFreeTarget());
        String helper = Files.readString(output.resolve("sample/WeaponMolangBindings.java"));
        assertTrue(helper.contains("MolangContext<net.minecraft.world.entity.Entity> context"));
        assertTrue(helper.contains("sample.Weapon.reset(context)"));
        assertFalse(helper.contains("MolangContextLinkage"));
        assertFalse(helper.contains("try("));
    }

    @Test
    void rejectsInvalidNamesArityAndNonExactBusinessViews() throws Exception {
        String header = "package sample;import com.elfmcys.ysm.api.annotation.*;"
            + "import com.elfmcys.ysm.api.molang.*;import net.minecraft.world.entity.Entity;"
            + "@YsmEntityComponent(\"weapon\") public class InvalidBoundary {";
        for (String declaration : List.of(
            "@MolangFunction(value=\"query.foo\") public static int f(MolangContextView<Entity> c){return 1;}",
            "@MolangFunction(value=\"query:foo\") public static int f(MolangContextView<Entity> c){return 1;}",
            "@MolangFunction(namespace=\"query\") public static int f(MolangContextView<Entity> c){return 1;}",
            "@MolangFunction(namespace=\"Weapon\") public static int f(MolangContextView<Entity> c){return 1;}",
            "@MolangFunction(minArgs=-2) public static int f(MolangContextView<Entity> c){return 1;}",
            "@MolangFunction(maxArgs=-2) public static int f(MolangContextView<Entity> c){return 1;}",
            "@MolangFunction(minArgs=1) public static int f(MolangContextView<Entity> c){return 1;}",
            "@MolangFunction(maxArgs=256) public static int f(MolangContextView<Entity> c,int argc,int... x){return 1;}",
            "@MolangFunction public static int f(MolangContextView c){return 1;}",
            "@MolangFunction public static int f(MolangContextView<? extends Entity> c){return 1;}",
            "public interface Custom extends MolangContextView<Entity>{}"
                + "@MolangFunction public static int f(Custom c){return 1;}",
            "@MolangFunction public static int f(com.elfmcys.ysm.expression.host.MolangContext<Entity> c){return 1;}",
            "@MolangFunction public static int f(MolangContextView<Entity> c){c.reset();return 1;}",
            "@MolangFunction public static int f(MolangContextView<Entity> c){c.batch();return 1;}",
            "@MolangFunction public static int f(MolangContextView<Entity> c){c.execute();return 1;}"))
            compile("sample.InvalidBoundary", header + declaration + "}", false);
        for (String namespace : List.of("query", "ysm", "Weapon", " weapon", "a".repeat(65)))
            compile("sample.InvalidNamespace",
                header.replace("InvalidBoundary", "InvalidNamespace").replace("\"weapon\"", "\"" + namespace + "\"")
                    + "@MolangFunction public static int f(MolangContextView<Entity> c){return 1;}}",
                false);
    }

    @Test
    void contextFreeMetadataRequiresFixedStaticPrimitiveContextDeclarations() throws Exception {
        String header = "package sample;import com.elfmcys.ysm.api.annotation.*;"
            + "import com.elfmcys.ysm.api.molang.*;import net.minecraft.world.entity.Entity;"
            + "@YsmEntityComponent(\"pure_meta\") public class PureMetadata {";
        var output = compile("sample.PureMetadata", header +
            "@MolangFunction(contextFreeTarget=\"target\") public static float declared(MolangContextView<Entity> c,float x){return x;}"
            + "public static float target(float x){return x;}}", true);
        var schema = DeclarationSchema.decode(Files.newInputStream(
            output.resolve(DeclarationSchema.RESOURCE_ROOT + "sample.PureMetadata.bin")));
        assertEquals("target", schema.members().get(0).contextFreeTarget());
        assertArrayEquals(schema.encode(), DeclarationSchema.decode(new java.io.ByteArrayInputStream(schema.encode())).encode());
        for (String declaration : List.of(
            "@MolangFunction(contextFreeTarget=\"bad.name\") public static float f(MolangContextView<Entity> c,float x){return x;}",
            "@MolangFunction(contextFreeTarget=\"target\",effect=MolangEffect.ACTION) public static float f(MolangContextView<Entity> c,float x){return x;}",
            "@MolangFunction(contextFreeTarget=\"target\") public static Float f(MolangContextView<Entity> c,float x){return x;}",
            "@MolangFunction(contextFreeTarget=\"target\") public static String f(MolangContextView<Entity> c,String x){return x;}",
            "@MolangFunction(contextFreeTarget=\"target\") public static float f(MolangContextView<Entity> c,int argc,@DefaultValue(\"0\") float x){return x;}",
            "@MolangFunction(contextFreeTarget=\"target\",maxArgs=5) public static float f(MolangContextView<Entity> c,int argc,float... x){return 0;}"))
            compile("sample.PureMetadata", header + declaration + "}", false);
    }

    private static List<Object> parsedDefaults(DeclarationSchema.Member member) {
        var result = new ArrayList<Object>();
        for (int i = 0; i < member.defaults().size(); i++)
            result.add(DeclarationSchema.literal(
                member.fixed().get(member.fixed().size() - member.defaults().size() + i), member.defaults().get(i)));
        return result;
    }

    private Path compile(String name, String source, boolean expected) throws Exception {
        Path output = Files.createTempDirectory(temporary, "compile-");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var unit = new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignore) {
                    return source;
                }
            };
            var task = compiler.getTask(null, manager, diagnostics, List.of("--release", "17", "-classpath",
                System.getProperty("java.class.path"), "-d", output.toString()), null, List.of(unit));
            task.setProcessors(List.of(new UnifiedDeclarationProcessor()));
            assertEquals(expected, task.call(), () -> diagnostics.getDiagnostics().toString());
            if (!expected)
                assertTrue(
                    diagnostics.getDiagnostics().stream()
                        .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR && d.getLineNumber() > 0),
                    () -> name + ": " + diagnostics.getDiagnostics());
        }
        return output;
    }
}
