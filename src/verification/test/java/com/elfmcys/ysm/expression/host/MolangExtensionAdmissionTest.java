package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import static org.junit.jupiter.api.Assertions.*;

import com.elfmcys.ysm.api.internal.processor.ExtensionCompatibilityProcessor;
import com.elfmcys.ysm.api.molang.*;
import dev.semaweave.*;
import dev.semaweave.compilation.*;
import dev.semaweave.program.*;
import java.io.*;
import java.lang.annotation.ElementType;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.*;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.forgespi.language.*;
import net.minecraftforge.forgespi.locating.IModFile;
import org.objectweb.asm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Separate author compilation, JAR scanning and startup freeze; not a running Forge release. */
public class MolangExtensionAdmissionTest {
    @TempDir
    Path temporary;

    @Test
    void malformedOptionalMetadataIsRejectedBeforeLoadingAndIndependentClassesStillFreeze() throws Exception {
        Path good = extension(apiJar(), "GoodMetadata", "good_mod", """
            @MolangFunction(effect=MolangEffect.CONTEXT)
            public static float value(MolangContextView<net.minecraft.world.entity.Entity> c){return 3;}
            """);
        for (boolean manual : List.of(false, true)) {
            Path bad = Files.createDirectory(temporary.resolve("bad-" + manual));
            Path resource = bad.resolve(DeclarationSchema.RESOURCE_ROOT + "independent.Broken.bin");
            Files.createDirectories(resource.getParent());
            Files.write(resource, nestedOptionalMetadata(manual));
            var urls = new ArrayList<URL>();
            urls.add(AdmissionRuntimeFixture.classes(temporary.resolve("runtime-" + manual)).toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(File.pathSeparator))
                urls.add(Path.of(entry).toUri().toURL());
            urls.add(bad.toUri().toURL());
            urls.add(good.toUri().toURL());
            var loads = new java.util.concurrent.atomic.AtomicInteger();
            try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals("independent.Broken") || name.equals("independent.BrokenMolangBindings"))
                        loads.incrementAndGet();
                    return super.loadClass(name, resolve);
                }
            }) {
                loader.loadClass(getClass().getName())
                    .getMethod("verifyMetadataIsolation", String.class, String.class, boolean.class)
                    .invoke(null, bad.toString(), good.toString(), manual);
                assertEquals(0, loads.get());
            }
        }
    }

    public static void verifyMetadataIsolation(String bad, String good, boolean manual) {
        var loader = MolangExtensionAdmissionTest.class.getClassLoader();
        var collector = new MolangExtensions.Collector();
        collector.collect(new MolangExtensions.Source("bad", Set.of("bad_mod"),
            List.of(new MolangExtensions.DeclarationRef("independent.Broken", "bad_mod", "")), loader, Path.of(bad)));
        collector.collect(new MolangExtensions.Source("good", Set.of("good_mod"),
            List.of(new MolangExtensions.DeclarationRef("independent.GoodMetadata", "good_mod", "")), loader,
            Path.of(good)));
        if (manual) {
            String resource = DeclarationSchema.RESOURCE_ROOT + "independent.Broken.bin";
            collector.submit(new MolangExtensions.Submission(resource, loader.getResource(resource), true, "precheck"));
        }
        var admission = collector.freeze(HostAvailability.none(), new MolangExtensions.Qualification() {
            public boolean dependency(String id, String version) {
                throw new AssertionError("unexpected dependency");
            }

            public boolean checker(MolangExtensions.Source source, DeclarationSchema schema) {
                assertEquals("independent.GoodMetadata", schema.owner());
                return true;
            }
        });
        assertTrue(
            admission.diagnostics().stream()
                .anyMatch(d -> d.startsWith("independent.Broken") && d.contains("METADATA:")),
            admission.diagnostics().toString());
        assertTrue(admission.host().registry().function(new SymbolKey("bad_mod:value")).isEmpty());
        assertTrue(admission.host().registry().function(new SymbolKey("good_mod:value")).isPresent());
        assertThrows(IllegalStateException.class,
            () -> collector.submit(new MolangExtensions.Submission("sealed", null, true, "")));
    }

    private static byte[] nestedOptionalMetadata(boolean manual) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeUTF("YSM-MOLANG-CALLABLE");
        out.writeUTF(DeclarationSchema.VERSION);
        out.writeUTF("independent.Broken");
        out.writeUTF("independent.BrokenMolangBindings");
        out.writeUTF("bad_mod");
        out.writeUTF("");
        out.writeBoolean(false);
        out.writeBoolean(manual);
        out.writeUTF("");
        out.writeInt(0);
        out.writeInt(1);
        out.writeUTF("bad_mod");
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
        for (int i = 0; i < 20_000; i++) {
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
        for (int i = 0; i < 20_000; i++)
            out.writeInt(0);
        out.writeBoolean(false);
        assertTrue(bytes.size() < 1_048_576);
        return bytes.toByteArray();
    }

    @Test
    void independentJarsUseTheProductionDiscoveryAndAtomicAdmissionPath() throws Exception {
        Path api = apiJar();
        Path alpha = extension(api, "Alpha", "alpha_mod",
            """
                @YsmEntityComponent("alpha_mod") public static class Nested {
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public static float nested(MolangContextView<net.minecraft.world.entity.Entity> c){return 1f;}
                }
                public record Pair(int count,float value) {}
                @MolangFunction(effect=MolangEffect.CONTEXT)
                public static Pair pair(MolangContextView<net.minecraft.world.entity.Entity> c){return new Pair(7,2f);}
                @MolangFunction(effect=MolangEffect.CONTEXT)
                public static int count(MolangContextView<net.minecraft.world.entity.Entity> c,int value){return value;}
                @MolangFunction(effect=MolangEffect.CONTEXT)
                public static java.util.List<String> labels(MolangContextView<net.minecraft.world.entity.Entity> c){return java.util.List.of("one");}
                """);
        Path beta = extension(api, "Beta", "beta_mod", """
            @MolangFunction(effect=MolangEffect.CONTEXT)
            public static String count(MolangContextView<net.minecraft.world.entity.Entity> c){return "OBSERVATION";}
            @MolangFunction(effect=MolangEffect.ACTION)
            public static void reset(MolangContextView<net.minecraft.world.entity.Entity> c){c.submit((com.elfmcys.ysm.api.molang.PreparedEffect)() -> {});}
            """);
        Path duplicate = extension(api, "Duplicate", "alpha_mod", """
            @MolangFunction(effect=MolangEffect.CONTEXT)
            public static float count(MolangContextView<net.minecraft.world.entity.Entity> c){return 0;}
            """);
        Path spoof =
            extension(api, "Spoof", "alpha_mod", "@YsmEntityComponent(value=\"alpha_mod\",modId=\"alpha_mod\")", """
                @MolangFunction(effect=MolangEffect.CONTEXT)
                public static float spoof(MolangContextView<net.minecraft.world.entity.Entity> c){return 0;}
                """);
        Path bad = extension(api, "Bad", "bad_mod", """
            public record BadZero(int count){public BadZero{if(count==0)throw new IllegalArgumentException("zero");}}
            @MolangFunction(effect=MolangEffect.CONTEXT)
            public static BadZero bad(MolangContextView<net.minecraft.world.entity.Entity> c){return new BadZero(1);}
            """);
        Path more = extension(api, "More", "alpha_mod",
            """
                @MolangFunction(effect=MolangEffect.CONTEXT)
                public static java.util.List<String> more(MolangContextView<net.minecraft.world.entity.Entity> c){return java.util.List.of("two");}
                """);
        var urls = new ArrayList<URL>();
        urls.add(AdmissionRuntimeFixture.classes(temporary.resolve("runtime")).toUri().toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator))
            urls.add(Path.of(entry).toUri().toURL());
        for (Path jar : List.of(alpha, beta, duplicate, spoof, bad, more))
            urls.add(jar.toUri().toURL());
        // All nominal carriers and the host share the same production visibility root.
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            loader.loadClass(getClass().getName()).getMethod("verifyAdmission", String[].class).invoke(null,
                (Object)new String[] {alpha.toString(), beta.toString(), duplicate.toString(), spoof.toString(),
                    bad.toString(), more.toString()});
        }
    }

    @Test
    void collectorClosesManualAllOfAndEveryPrepublicationFailureStage() throws Exception {
        Path api = apiJar();
        String function =
            "@MolangFunction(effect=MolangEffect.CONTEXT) public static float value(MolangContextView<net.minecraft.world.entity.Entity> c){return 3;}";
        var jars = new ArrayList<Path>();
        jars.add(extension(api, "Both", "both_mod",
            "@YsmEntityComponent(value=\"both_mod\",dependencies={@MolangDependency(modId=\"dep_a\",version=\"[1,2)\"),@MolangDependency(modId=\"dep_b\")})",
            "static {System.setProperty(\"ysm.fixture.both.initialized\",\"yes\");}" + function));
        jars.add(extension(api, "OnlyA", "only_mod",
            "@YsmEntityComponent(value=\"only_mod\",dependencies=@MolangDependency(modId=\"dep_a\",version=\"[1,2)\"))",
            function));
        jars.add(extension(api, "Manual", "manual_mod",
            "@YsmEntityComponent(value=\"manual_mod\",mode=MolangAdmissionMode.MANUAL)", function));
        jars.add(extension(api, "InitBad", "init_mod",
            "static {if(System.nanoTime()!=0)throw new IllegalStateException(\"initializer-source\");}" + function));
        Path binding = extension(api, "BindingBad", "binding_mod", function);
        jars.add(rewriteJar(binding, false, true));
        Path resolution = extension(api, "ResolutionBad", "resolution_mod", function);
        jars.add(rewriteJar(resolution, true, false));
        var urls = new ArrayList<URL>();
        urls.add(AdmissionRuntimeFixture.classes(temporary.resolve("runtime")).toUri().toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator))
            urls.add(Path.of(entry).toUri().toURL());
        for (Path jar : jars)
            urls.add(jar.toUri().toURL());
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            loader.loadClass(getClass().getName()).getMethod("verifyCollector", String[].class).invoke(null,
                (Object)jars.stream().map(Path::toString).toArray(String[]::new));
        }
    }

    public static void verifyCollector(String[] paths) throws Exception {
        var loader = MolangExtensionAdmissionTest.class.getClassLoader();
        String[] mods = {"both_mod", "only_mod", "manual_mod", "init_mod", "binding_mod", "resolution_mod"};
        var files = new ArrayList<IModFileInfo>();
        for (int i = 0; i < paths.length; i++)
            files.add(scan(Path.of(paths[i]), mods[i]));
        var sources = new ArrayList<>(MolangExtensionLoader.discover(files, loader));
        // The discovery receipt predates removal; resolution must still be class-local.
        sources.set(5,
            new MolangExtensions.Source(paths[5], Set.of("resolution_mod"),
                List.of(new MolangExtensions.DeclarationRef("independent.ResolutionBad", "resolution_mod", "")), loader,
                Path.of(paths[5])));
        var versions = new HashMap<String, String>();
        versions.put("dep_a", "1.5");
        var qualification = new MolangExtensions.Qualification() {
            public boolean dependency(String id, String range) {
                String actual = versions.get(id);
                return actual != null && (range.isEmpty() || MolangExtensions.versionMatches(actual, range));
            }

            public boolean checker(MolangExtensions.Source source, DeclarationSchema schema) {
                return true;
            }
        };
        var collector = new MolangExtensions.Collector();
        sources.forEach(collector::collect);
        var rejected = collector.freeze(HostAvailability.none(), qualification);
        assertNull(System.getProperty("ysm.fixture.both.initialized"));
        assertTrue(rejected.host().registry().function(new SymbolKey("only_mod:value")).isPresent(),
            rejected.diagnostics().toString());
        for (String mod : List.of("both_mod", "manual_mod", "init_mod", "binding_mod", "resolution_mod"))
            assertTrue(rejected.host().registry().function(new SymbolKey(mod + ":value")).isEmpty());
        for (String stage : List.of("QUALIFICATION", "INITIALIZATION", "BINDING", "RESOLUTION"))
            assertTrue(rejected.diagnostics().stream().anyMatch(d -> d.contains(stage)),
                rejected.diagnostics().toString());
        assertThrows(IllegalStateException.class, () -> collector.collect(sources.get(0)));
        String resource = DeclarationSchema.RESOURCE_ROOT + "independent.Manual.bin";
        var receipt = new MolangExtensions.Submission(resource, loader.getResource(resource), true, "precheck");
        assertThrows(IllegalStateException.class, () -> collector.submit(receipt));
        versions.put("dep_b", "3");
        var accepted = new MolangExtensions.Collector();
        List.of(sources.get(0), sources.get(1), sources.get(2)).forEach(accepted::collect);
        accepted.submit(receipt);
        var full = accepted.freeze(HostAvailability.none(), qualification);
        assertEquals("yes", System.getProperty("ysm.fixture.both.initialized"));
        assertEquals(3, full.host().registry().functions().size());
        assertTrue(full.rejectedMods().isEmpty(), full.diagnostics().toString());
        var duplicate = new MolangExtensions.Collector();
        duplicate.collect(sources.get(2));
        duplicate.collect(sources.get(1));
        duplicate.submit(receipt);
        duplicate.submit(receipt);
        var doubleManual = duplicate.freeze(HostAvailability.none(), qualification);
        assertTrue(doubleManual.host().registry().function(new SymbolKey("manual_mod:value")).isEmpty());
        assertTrue(doubleManual.host().registry().function(new SymbolKey("only_mod:value")).isPresent());
        var doubleAuto = new MolangExtensions.Collector();
        doubleAuto.collect(sources.get(1));
        String auto = DeclarationSchema.RESOURCE_ROOT + "independent.OnlyA.bin";
        doubleAuto.submit(new MolangExtensions.Submission(auto, loader.getResource(auto), true, "precheck"));
        assertTrue(doubleAuto.freeze(HostAvailability.none(), qualification).host().registry()
            .function(new SymbolKey("only_mod:value")).isEmpty());
        var checkerReject = new MolangExtensions.Collector();
        checkerReject.collect(sources.get(2));
        checkerReject.submit(new MolangExtensions.Submission(resource, loader.getResource(resource), false, "denied"));
        assertTrue(checkerReject.freeze(HostAvailability.none(), qualification).host().registry()
            .function(new SymbolKey("manual_mod:value")).isEmpty());
        System.clearProperty("ysm.fixture.both.initialized");
    }

    private Path rewriteJar(Path original, boolean removeOwner, boolean changeSchema) throws Exception {
        Path path = temporary.resolve(original.getFileName().toString().replace(".jar", "-changed.jar"));
        try (var input = new JarFile(original.toFile());
            var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : Collections.list(input.entries())) {
                if (removeOwner && entry.getName().equals("independent/ResolutionBad.class"))
                    continue;
                byte[] bytes = input.getInputStream(entry).readAllBytes();
                if (changeSchema && entry.getName().endsWith(".bin")) {
                    var schema = DeclarationSchema.decode(new ByteArrayInputStream(bytes));
                    var m = schema.members().get(0);
                    var changed = new DeclarationSchema.Member(m.namespace(), m.name(), m.javaName(), "()V",
                        m.adapter(), m.subject(), m.isStatic(), m.contextFreeTarget(), m.effect(), m.minimum(), m.maximum(),
                        m.fixed(), m.defaults(), m.tail(), m.result(), m.nullableRecord());
                    bytes = new DeclarationSchema(schema.owner(), schema.helper(), schema.namespace(), schema.modId(),
                        schema.builtin(), schema.manual(), schema.componentSubject(), schema.dependencies(),
                        List.of(changed)).encode();
                }
                output.putNextEntry(new JarEntry(entry.getName()));
                output.write(bytes);
                output.closeEntry();
            }
        }
        return path;
    }

    public static void verifyAdmission(String[] paths) throws Throwable {
        var loader = MolangExtensionAdmissionTest.class.getClassLoader();
        var files = new ArrayList<IModFileInfo>();
        String[] owners = {"alpha_mod", "beta_mod", "alpha_mod", "evil_mod", "bad_mod", "alpha_mod"};
        for (int i = 0; i < paths.length; i++)
            files.add(scan(Path.of(paths[i]), owners[i]));
        var sources = MolangExtensionLoader.discover(files, loader);
        assertEquals(2, sources.get(0).declarations().size());
        assertEquals(Set.of("evil_mod"), sources.get(3).modIds());
        var valid =
            MolangExtensions.admit(HostAvailability.none(), List.of(sources.get(0), sources.get(1), sources.get(5)));
        var reversed =
            MolangExtensions.admit(HostAvailability.none(), List.of(sources.get(5), sources.get(1), sources.get(0)));
        assertEquals(valid.host().registry().fingerprint(), reversed.host().registry().fingerprint());
        assertEquals(HostLinkage.production(valid.host()).fingerprint(),
            HostLinkage.production(reversed.host()).fingerprint());
        assertEquals(List.of(), valid.rejectedMods(), valid.diagnostics().toString());
        assertTrue(valid.host().registry().functions().containsKey(new dev.semaweave.SymbolKey("alpha_mod:pair")));
        assertTrue(valid.host().registry().functions().containsKey(new dev.semaweave.SymbolKey("beta_mod:count")));
        assertEquals(2, valid.host().registry().types().values().stream()
            .filter(t -> t.type() instanceof dev.semaweave.ListType list && !list.carrierIdentity().isEmpty()).count());
        HostLinkage.production(valid.host()).requireCompatible(valid.host());
        executeGenerated(valid.host());
        var rejected = MolangExtensions.admit(HostAvailability.none(), sources);
        assertEquals(List.of("alpha_mod"), rejected.rejectedMods(), rejected.diagnostics().toString());
        assertTrue(rejected.diagnostics().stream().anyMatch(d -> d.contains("symbol conflict")),
            rejected.diagnostics().toString());
        assertTrue(rejected.host().registry().functions().containsKey(new SymbolKey("bad_mod:bad")));
        assertFalse(rejected.host().registry().functions().containsKey(new dev.semaweave.SymbolKey("alpha_mod:pair")));
        assertTrue(rejected.host().registry().functions().containsKey(new dev.semaweave.SymbolKey("beta_mod:count")));
        var victim = MolangExtensions.admit(HostAvailability.none(), List.of(sources.get(0), sources.get(3)));
        assertEquals(List.of(), victim.rejectedMods());
        assertTrue(victim.host().registry().functions().containsKey(new dev.semaweave.SymbolKey("alpha_mod:pair")));
        assertFalse(victim.host().registry().functions().containsKey(new dev.semaweave.SymbolKey("alpha_mod:spoof")));
        assertFalse(victim.diagnostics().isEmpty());
        try (var foreign = new URLClassLoader(new URL[] {Path.of(paths[0]).toUri().toURL()}, loader) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("independent."))
                    return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null)
                    type = findClass(name);
                if (resolve)
                    resolveClass(type);
                return type;
            }
        }) {
            var foreignSource = new MolangExtensions.Source(paths[0], Set.of("alpha_mod"),
                sources.get(0).declarations(), foreign, Path.of(paths[0]));
            var mismatch = MolangExtensions.admit(HostAvailability.none(), List.of(foreignSource, sources.get(1)));
            assertEquals(List.of("alpha_mod"), mismatch.rejectedMods(), mismatch.diagnostics().toString());
            assertTrue(mismatch.host().registry().functions().containsKey(new SymbolKey("beta_mod:count")));
        }
    }

    private static void executeGenerated(ExpressionHost host) throws Throwable {
        var pair =
            new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "alpha_mod:pair"),
                ProgramNode.AccessForm.INVOKE, List.of());
        var root =
            new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "alpha_mod:count"),
                ProgramNode.AccessForm.INVOKE, List.of(new ProgramNode.Member(pair, "count")));
        var codec = PortableProgramCodecs.v1();
        var source =
            new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {1})),
                codec.encode(PortablePrograms.ofRoots(List.of(root))), new TopLevelEntrySignature(ProgramShape.F32,
                    new EntryResult.Value(BuiltinType.F32), List.of(), List.of()),
                Optional.empty(), new DiagnosticLocation("independent"));
        var unit = CompilationUnit.builder(host.compilationPolicy(), codec, ExpressionIdentityHash.SHA_256)
            .addProgram(source).build();
        var artifact = new DefaultExpressionAotCompiler<MolangContext>(
            new dev.semaweave.backend.janino.JaninoJavaCompilerBackend()).compile(TaskContext.NONE, unit,
                host.hostAbi(), host.registry(), CompilerOptions.DEFAULT, ExpressionIdentityHash.SHA_256);
        var loader = new ClassLoader(host.registry().visibilityRoot()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                var bytes = artifact.classes().get(name);
                if (bytes == null)
                    throw new ClassNotFoundException(name);
                var array = bytes.toByteArray();
                return defineClass(name, array, 0, array.length);
            }
        };
        var rootClass = loader.loadClass(artifact.manifest().rootBinaryName());
        var roaming = (dev.semaweave.CompiledRoamingState)rootClass
            .getMethod("semaweaveNewRoamingState", dev.semaweave.compilation.ResourceGenerationToken.class,
                RoamingRole.class)
            .invoke(null, dev.semaweave.compilation.TestResourceGenerations.create(), RoamingRole.CONSUMER);
        @SuppressWarnings("unchecked")
        var generated = (GeneratedModuleRoot<MolangContext>)rootClass
            .getConstructor(Object[].class, Object[].class, ValueType[].class, ChildContextAdapter.class,
                dev.semaweave.CompiledRoamingState.class, SubjectBindingFactory.class)
            .newInstance(TestComponentHolders.providers(host).toDenseArray(), new Object[0],
                host.registry().types().values().stream().map(t -> t.type()).toArray(ValueType[]::new), null, roaming,
                subjectFactory(rootClass, roaming));
        var context = MolangContext.observation(new EntityView() {}, new RenderView() {}, (command, scope) -> {
            throw new AssertionError("unexpected effect " + command);
        }, roaming, new InvocationProvenance("jar", "test", 0, 1, 1), () -> false, () -> true);
        var frame = context;
        frame.configureLimits(host.executionLimits());
        frame.bindExpression("admission", 0);
        frame.beginTopLevel(false);
        try {
            assertEquals(7f,
                generated.executeFloat(0, 0, context,
                    generated.subjectBindingFactory().bind(MolangContext.subjectOrNull(context)), frame,
                    ProgramArguments.empty()));
        } finally {
            frame.endTopLevel();
            frame.releaseExpression();
        }
    }

    @Test
    void independentMixedOwnersInvalidateDirectoriesAndBorrowedViews() throws Exception {
        Path api = apiJar();
        String imports = "package independent;import com.elfmcys.ysm.api.annotation.*;"
            + "import com.elfmcys.ysm.api.molang.*;import com.elfmcys.ysm.expression.host.*;"
            + "import net.minecraft.world.entity.Entity;";
        Path mixed = extensionSource(api, "Lifecycle", imports + """
            @YsmEntityComponent("lifecycle_mod")
            public class Lifecycle implements EntityComponent<Entity> {
                public static int constructed;
                public int count,updates;
                public static MolangContextView<Entity> borrowed;
                public Lifecycle(com.elfmcys.ysm.client.entity.AnimatableEntity<Entity> owner){constructed++;}
                public void update(float previous,float current,float partial){updates++;}
                @MolangFunction(effect=MolangEffect.CONTEXT)
                public int read(MolangContextView<Entity> context){borrowed=context;return ++count;}
                @MolangFunction(namespace="lifecycle_other")
                public int next(MolangContextView<Entity> context){return ++count;}
                @MolangFunction(effect=MolangEffect.CONTEXT)
                public static int fixed(MolangContextView<Entity> context){return 5;}
            }
            """);
        Path statics = extension(api, "StaticOnly", "static_mod", """
            private StaticOnly(){throw new AssertionError("static constructed");}
            @MolangFunction(effect=MolangEffect.CONTEXT)
            public static int fixed(MolangContextView<net.minecraft.world.entity.Entity> context){return 5;}
            """);
        Path failing = extensionSource(api, "ZFailure", imports
            + """
                @YsmEntityComponent("failure_mod")
                public class ZFailure implements EntityComponent<Entity> {
                    public ZFailure(com.elfmcys.ysm.client.entity.AnimatableEntity<Entity> owner){throw new IllegalStateException("construction-source");}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public int read(MolangContextView<Entity> context){return 1;}
                }
                """);
        var urls = new ArrayList<URL>();
        urls.add(AdmissionRuntimeFixture.classes(temporary.resolve("runtime")).toUri().toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator))
            urls.add(Path.of(entry).toUri().toURL());
        for (Path jar : List.of(mixed, statics, failing))
            urls.add(jar.toUri().toURL());
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            var previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                loader.loadClass(getClass().getName()).getMethod("verifyOwners", String[].class).invoke(null,
                    (Object)new String[] {mixed.toString(), statics.toString(), failing.toString()});
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    public static void verifyOwners(String[] paths) throws Throwable {
        var loader = MolangExtensionAdmissionTest.class.getClassLoader();
        var distribution = loader.loadClass("net.minecraftforge.fml.loading.FMLEnvironment").getField("dist");
        distribution.set(null, net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER);
        try {
            ComponentScopeTest.initializeMinecraftEntityClasses();
        } finally {
            distribution.set(null, net.minecraftforge.api.distmarker.Dist.CLIENT);
        }
        var subject = (net.minecraft.world.entity.Entity)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe()
            .allocateInstance(net.minecraft.world.entity.decoration.ArmorStand.class);
        var position = net.minecraft.world.entity.Entity.class.getDeclaredField("position");
        position.setAccessible(true);
        position.set(subject, net.minecraft.world.phys.Vec3.ZERO);
        var files = new ArrayList<IModFileInfo>();
        String[] mods = {"lifecycle_mod", "static_mod", "failure_mod"};
        for (int i = 0; i < paths.length; i++)
            files.add(scan(Path.of(paths[i]), mods[i]));
        var sources = MolangExtensionLoader.discover(files, loader);
        var valid = MolangExtensions.admit(HostAvailability.none(), sources.subList(0, 2));
        assertEquals(List.of(), valid.rejectedMods(), valid.diagnostics().toString());
        var host = valid.host();
        Class<?> owner = loader.loadClass("independent.Lifecycle");
        var first = new DeclarationComponentScope(TestComponentHolders.owner(subject), host.declarations());
        var second = new DeclarationComponentScope(TestComponentHolders.owner(subject), host.declarations());
        Object firstOwner = first.exact(owner).orElseThrow(), secondOwner = second.exact(owner).orElseThrow();
        assertNotSame(firstOwner, secondOwner);
        assertEquals(2, owner.getField("constructed").getInt(null));
        var read =
            new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "lifecycle_mod:read"),
                ProgramNode.AccessForm.READ, List.of());
        var shared = new SymbolKey("test:reader");
        var call = new ProgramNode.Symbol(
            new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.USER_FUNCTION, shared.canonical()),
            ProgramNode.AccessForm.INVOKE, List.of());
        var root = new ProgramNode.Binary(ProgramNode.BinaryOperator.ADD, read, call);
        var codec = PortableProgramCodecs.v1();
        var source =
            new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {1})),
                codec.encode(PortablePrograms.ofRoots(List.of(root))), new TopLevelEntrySignature(ProgramShape.F32,
                    new EntryResult.Value(BuiltinType.F32), List.of(), List.of()),
                Optional.empty(), new DiagnosticLocation("owner/read-order"), EntryPermissions.CONTEXT);
        var reader = new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {2})),
            codec.encode(PortablePrograms.ofRoots(List.of(new ProgramNode.Symbol(
                new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "lifecycle_other:next"),
                ProgramNode.AccessForm.READ, List.of())))),
            UserFunctionEntrySignature.inferred(shared, new LocalName("args"),
                com.elfmcys.ysm.expression.inventory.ProgramRole.scalarValueType(), List.of()),
            Optional.empty(), new DiagnosticLocation("owner/shared-read"));
        var plan = host.expressionBinding().bindingPlan();
        var artifact = new DefaultExpressionAotCompiler<MolangContext>(
            new dev.semaweave.backend.janino.JaninoJavaCompilerBackend())
            .compile(TaskContext.NONE,
                CompilationUnit.builder(host.compilationPolicy(), codec, ExpressionIdentityHash.SHA_256)
                    .addProgram(source).addProgram(reader).build(),
                host.hostAbi(), host.registry(), CompilerOptions.DEFAULT, plan, ExpressionIdentityHash.SHA_256);
        var generatedLoader = new ClassLoader(loader) {
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                var bytes = artifact.classes().get(name);
                if (bytes == null)
                    throw new ClassNotFoundException(name);
                byte[] array = bytes.toByteArray();
                return defineClass(name, array, 0, array.length);
            }
        };
        Class<?> rootClass = generatedLoader.loadClass(artifact.manifest().rootBinaryName());
        var roaming = (CompiledRoamingState)rootClass
            .getMethod("semaweaveNewRoamingState", ResourceGenerationToken.class, RoamingRole.class)
            .invoke(null, TestResourceGenerations.create(), RoamingRole.PRODUCER);
        var inputs = new InvocationInputs<Entity>() {
            public net.minecraft.world.entity.Entity entity() {
                return subject;
            }

            public void prevalidateEffect(PreparedEffect command, MolangContext<?> scope) {}
        };
        var state = MolangContext.observation(subject, inputs, inputs, roaming,
            new InvocationProvenance("owner", "read-order", 0, 1, 1), () -> false, () -> true);
        var constructor = rootClass.getConstructor(Object[].class, Object[].class, ValueType[].class,
            ChildContextAdapter.class, CompiledRoamingState.class, SubjectBindingFactory.class);
        @SuppressWarnings("unchecked")
        var generated =
            (GeneratedModuleRoot<MolangContext>)constructor.newInstance(first.providers(host, plan).toDenseArray(),
                new Object[0], host.registry().types().values().stream().map(t -> t.type()).toArray(ValueType[]::new),
                null, roaming, subjectFactory(rootClass, roaming));
        var frame = state;
        frame.configureLimits(host.executionLimits());
        frame.bindExpression("admission", 0);
        frame.beginTopLevel(false);
        try {
            assertEquals(3f,
                generated.executeFloat(0,0, state,
                    generated.subjectBindingFactory().bind(MolangContext.subjectOrNull(state)), frame,
                    ProgramArguments.empty()));
        } finally {
            frame.endTopLevel();
            frame.releaseExpression();
        }
        assertEquals(2, owner.getField("count").getInt(firstOwner));
        assertEquals(0, owner.getField("count").getInt(secondOwner));
        @SuppressWarnings("unchecked")
        var expired = (MolangContextView<Entity>)owner.getField("borrowed").get(null);
        assertThrows(IllegalStateException.class, expired::subject);
        var declaration = host.declarations().stream().filter(d -> d.owner() == owner).findFirst().orElseThrow();
        var fixed =
            declaration.schema().members().stream().filter(m -> m.javaName().equals("fixed")).findFirst().orElseThrow();
        assertEquals(5, declaration.helper().getMethod(fixed.adapter(), MolangContext.class).invoke(null, state));
        assertEquals(2, owner.getField("constructed").getInt(null));
        first.update(0, 1, 1, () -> true);
        assertEquals(1, owner.getField("updates").getInt(firstOwner));
        assertEquals(0, owner.getField("updates").getInt(secondOwner));
        var all = MolangExtensions.admit(HostAvailability.none(), sources);
        assertEquals(List.of(), all.rejectedMods(), all.diagnostics().toString());
        assertThrows(ComponentLifecycleException.class,
            () -> new DeclarationComponentScope(TestComponentHolders.owner(subject), all.host().declarations()));
        int constructionCount = owner.getField("constructed").getInt(null);
        var defaultHolder =
            new DeclarationComponentScope(TestComponentHolders.owner(subject), all.host().builtinOnly().declarations());
        assertTrue(defaultHolder.exact(owner).isEmpty());
        int previousUpdates = owner.getField("updates").getInt(firstOwner);
        defaultHolder.update(1, 2, 0.5f, () -> true);
        assertEquals(previousUpdates, owner.getField("updates").getInt(firstOwner));
        assertEquals(constructionCount, owner.getField("constructed").getInt(null));
        defaultHolder.invalidate();
        assertSame(firstOwner, first.exact(owner).orElseThrow());
        assertTrue(all.host().registry().functions().containsKey(new SymbolKey("failure_mod:read")));
        first.invalidate();
        assertTrue(first.exact(owner).isEmpty());
        assertSame(secondOwner, second.exact(owner).orElseThrow());
        assertThrows(IllegalStateException.class, expired::subject);
        second.invalidate();
    }

    @Test
    void realSourcesSeparateNamespaceModOwnershipAndConflictEndpoints() throws Exception {
        Path api = apiJar();
        var jars = new ArrayList<Path>();
        String context = "MolangContextView<net.minecraft.world.entity.Entity>";
        String one = "@MolangFunction public static int one(" + context + " c){return 7;}";
        String two = "@MolangFunction public static int two(" + context + " c){return 9;}";
        jars.add(ownershipFixture(api, "SameOne", "weapon", "", one));
        jars.add(ownershipFixture(api, "SameTwo", "weapon", "", two));
        jars.add(ownershipFixture(api, "Ambiguous", "ambiguous", "", one));
        jars.add(ownershipFixture(api, "Chosen", "combat", "bridge_b", one));
        jars.add(ownershipFixture(api, "Wrong", "wrong", "elsewhere", one));
        jars.add(ownershipFixture(api, "Elsewhere", "elsewhere_api", "", one));
        jars.add(ownershipFixture(api, "CrossOne", "shared", "", one));
        jars.add(ownershipFixture(api, "CrossTwo", "shared", "", two));
        jars.add(ownershipFixture(api, "ChainOne", "left", "", one));
        jars.add(ownershipFixture(api, "ChainMiddle", "left", "",
            two + "@MolangFunction(namespace=\"right\") public static int one(" + context + " c){return 1;}"));
        jars.add(ownershipFixture(api, "ChainLast", "right", "", two));
        jars.add(ownershipFixture(api, "UnusedDefault", "shared", "",
            "@MolangFunction(namespace=\"isolated\") public static int one(" + context + " c){return 1;}"));
        jars.add(ownershipFixture(api, "DuplicateOne", "duplicate", "", one));
        jars.add(ownershipFixture(api, "DuplicateTwo", "duplicate", "",
            "@MolangFunction(value=\"one\") public static int other(" + context + " c,String value){return 1;}"));
        jars.add(ownershipFixture(api, "FactsMismatch", "facts", "", one));
        jars.add(extension(api, "Ineligible", "qualified",
            "@YsmEntityComponent(value=\"qualified\",dependencies=@MolangDependency(modId=\"missing_optional\"))",
            "static {System.setProperty(\"ysm.ownership.Ineligible\",\"initialized\");}" + one));
        jars.add(ownershipFixture(api, "Qualified", "qualified", "", two));
        var urls = new ArrayList<URL>();
        urls.add(AdmissionRuntimeFixture.classes(temporary.resolve("ownership-runtime")).toUri().toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator))
            urls.add(Path.of(entry).toUri().toURL());
        for (Path jar : jars)
            urls.add(jar.toUri().toURL());
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            loader.loadClass(getClass().getName()).getMethod("verifyOwnershipMatrix", String[].class).invoke(null,
                (Object)jars.stream().map(Path::toString).toArray(String[]::new));
        }
    }

    private Path ownershipFixture(Path api, String name, String namespace, String modId, String body) throws Exception {
        return extension(api, name, namespace,
            "@YsmEntityComponent(value=\"" + namespace + "\",modId=\"" + modId + "\")",
            "static {System.setProperty(\"ysm.ownership." + name + "\",\"initialized\");}" + body);
    }

    public static void verifyOwnershipMatrix(String[] paths) throws Exception {
        var loader = MolangExtensionAdmissionTest.class.getClassLoader();
        var owners = List.of(List.of("combat_bridge"), List.of("combat_bridge"), List.of("bridge_a", "bridge_b"),
            List.of("bridge_a", "bridge_b"), List.of("actual_owner"), List.of("elsewhere"), List.of("cross_a"),
            List.of("cross_b"), List.of("chain_a"), List.of("chain_b"), List.of("chain_c"), List.of("isolated_owner"),
            List.of("duplicate_owner"), List.of("duplicate_owner"), List.of("facts_owner"), List.of("ineligible_owner"),
            List.of("qualified_owner"));
        var sources = new ArrayList<MolangExtensions.Source>();
        for (int i = 0; i < paths.length; i++)
            sources.addAll(MolangExtensionLoader.discover(List.of(scan(Path.of(paths[i]), owners.get(i))), loader));
        var facts = sources.get(14);
        sources.set(14,
            new MolangExtensions.Source(facts.id(), facts.modIds(),
                facts.declarations().stream()
                    .map(ref -> new MolangExtensions.DeclarationRef(ref.className(), ref.namespace(), "facts_owner"))
                    .toList(),
                facts.loader(), facts.file()));
        var expected = Set.of("independent.SameOne", "independent.SameTwo", "independent.Chosen",
            "independent.Elsewhere", "independent.UnusedDefault", "independent.Qualified");
        for (boolean reversed : List.of(false, true)) {
            var ordered = new ArrayList<>(sources);
            if (reversed)
                Collections.reverse(ordered);
            var admission = MolangExtensions.admit(HostAvailability.none(), ordered);
            assertEquals(
                expected, admission.host().declarations().stream().filter(d -> !d.schema().builtin())
                    .map(d -> d.schema().owner()).collect(java.util.stream.Collectors.toSet()),
                admission.diagnostics().toString());
            var chosen = admission.host().declarations().stream()
                .filter(d -> d.schema().owner().equals("independent.Chosen")).findFirst().orElseThrow();
            assertEquals("bridge_b", chosen.verifiedModId());
            assertEquals("combat", chosen.schema().namespace());
            assertEquals("bridge_b", chosen.schema().modId());
            assertTrue(admission.host().registry().function(new SymbolKey("weapon:one")).isPresent());
            assertTrue(admission.host().registry().function(new SymbolKey("weapon:two")).isPresent());
            for (String name : List.of("SameOne", "SameTwo", "UnusedDefault")) {
                var linked = admission.host().declarations().stream()
                    .filter(d -> d.schema().owner().equals("independent." + name)).findFirst().orElseThrow();
                assertEquals(name.equals("UnusedDefault") ? "isolated_owner" : "combat_bridge", linked.verifiedModId());
                assertEquals("", linked.schema().modId());
                var member = linked.schema().members().get(0);
                assertEquals(name.equals("SameOne") ? 7 : name.equals("SameTwo") ? 9 : 1,
                    linked.helper().getMethod(member.adapter(), MolangContext.class).invoke(null, (Object)null));
            }
            for (String symbol : List.of("shared:one", "shared:two", "left:one", "left:two", "right:one", "right:two",
                "duplicate:one", "ambiguous:one", "wrong:one", "facts:one"))
                assertTrue(admission.host().registry().function(new SymbolKey(symbol)).isEmpty(), symbol);
            for (String name : List.of("Ambiguous", "Wrong", "CrossOne", "CrossTwo", "ChainOne", "ChainMiddle",
                "ChainLast", "DuplicateOne", "DuplicateTwo", "FactsMismatch", "Ineligible"))
                assertNull(System.getProperty("ysm.ownership." + name), name + " initialized before admission");
        }
    }

    private static IModFileInfo scan(Path path, String owner) throws IOException {
        return scan(path, List.of(owner));
    }

    private static IModFileInfo scan(Path path, List<String> owners) throws IOException {
        var scan = new ModFileScanData();
        try (var jar = new JarFile(path.toFile())) {
            for (var entry : Collections.list(jar.entries()))
                if (entry.getName().endsWith(".class")) {
                    try (var input = jar.getInputStream(entry)) {
                        new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                            String name;

                            @Override
                            public void visit(int v, int access, String n, String signature, String parent,
                                String[] interfaces) {
                                name = n;
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                                if (!descriptor.equals("Lcom/elfmcys/ysm/api/annotation/YsmEntityComponent;"))
                                    return null;
                                Map<String, Object> values = new HashMap<>();
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String key, Object value) {
                                        values.put(key, value);
                                    }

                                    @Override
                                    public void visitEnd() {
                                        scan.getAnnotations()
                                            .add(new ModFileScanData.AnnotationData(Type.getType(descriptor),
                                                ElementType.TYPE, Type.getObjectType(name), name, values));
                                    }
                                };
                            }
                        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    }
                }
        }
        var mods = owners.stream().map(owner -> proxy(IModInfo.class, Map.of("getModId", owner))).toList();
        IModFile file = proxy(IModFile.class, Map.of("getScanResult", scan, "getFilePath", path));
        return proxy(IModFileInfo.class, Map.of("getMods", mods, "getFile", file));
    }

    private static <T> T proxy(Class<T> type, Map<String, Object> fields) {
        return type
            .cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) -> {
                if (fields.containsKey(method.getName()))
                    return fields.get(method.getName());
                throw new UnsupportedOperationException(method.toString());
            }));
    }

    @Test
    void uniformDeclarationsBindWithoutBusinessCatalogsAndPublicHintsDoNotAuthorizePurity() throws Exception {
        Path extension = extension(apiJar(), "Uniform", "uniform", """
            public static final java.util.List<Float> committed = new java.util.ArrayList<>();
            @MolangFunction
            public static float fresh(MolangContextView<net.minecraft.world.entity.Entity> context) { return 7; }
            @MolangFunction(contextFreeTarget="pure")
            public static float read(MolangContextView<net.minecraft.world.entity.Entity> context, float value) {
                return context.inputs().partialTick() + value;
            }
            public static float pure(float value) { throw new AssertionError("untrusted pure target"); }
            @MolangFunction(effect=MolangEffect.ACTION)
            public static void apply(MolangContextView<net.minecraft.world.entity.Entity> context, float value) {
                context.submit((PreparedEffect)() -> committed.add(value));
            }
            """);
        var urls = new ArrayList<URL>();
        urls.add(AdmissionRuntimeFixture.classes(temporary.resolve("uniform-runtime")).toUri().toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator))
            urls.add(Path.of(entry).toUri().toURL());
        urls.add(extension.toUri().toURL());
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            var previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                loader.loadClass(getClass().getName()).getMethod("verifyUniform", String.class)
                    .invoke(null, extension.toString());
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw new AssertionError(failure.getCause());
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    public static void verifyUniform(String path) throws Throwable {
        var loader = MolangExtensionAdmissionTest.class.getClassLoader();
        var dist = loader.loadClass("net.minecraftforge.fml.loading.FMLEnvironment").getField("dist");
        dist.set(null, net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER);
        try { ComponentScopeTest.initializeMinecraftEntityClasses(); }
        finally { dist.set(null, net.minecraftforge.api.distmarker.Dist.CLIENT); }
        var sources = MolangExtensionLoader.discover(List.of(scan(Path.of(path), "uniform")), loader);
        var admission = MolangExtensions.admit(HostAvailability.none(), sources);
        assertTrue(admission.rejectedMods().isEmpty(), admission.diagnostics().toString());
        var host = admission.host();
        var declaration = host.declarations().stream().filter(d -> d.schema().owner().equals("independent.Uniform"))
            .findFirst().orElseThrow();
        assertTrue(host.registry().functions().containsKey(new SymbolKey("uniform:fresh")));
        assertTrue(host.registry().functions().containsKey(new SymbolKey("uniform:apply")));
        assertTrue(declaration.members().stream().allMatch(m -> m.contextFreeTarget().isEmpty()));
        assertTrue(host.builtinOnly().registry().functions().keySet().stream()
            .noneMatch(key -> key.canonical().startsWith("uniform:")));
        HostLinkage.production(host).requireCompatible(host);
        var encoded = new com.elfmcys.ysm.expression.frontend.MolangFrontend().compile(
            "uniform.apply(3); uniform.read(math.abs(-2)); return uniform.fresh();",
            com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceMode.EXPRESSION,
            new com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceLocation("uniform")).program();
        var source = new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {11})), encoded,
            new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(), List.of()),
            Optional.empty(), new DiagnosticLocation("uniform"), EntryPermissions.ACTION);
        var plan = host.expressionBinding().bindingPlan();
        var artifact = new DefaultExpressionAotCompiler<MolangContext>(
            new dev.semaweave.backend.janino.JaninoJavaCompilerBackend()).compile(TaskContext.NONE,
                CompilationUnit.builder(host.compilationPolicy(), PortableProgramCodecs.v1(), ExpressionIdentityHash.SHA_256)
                    .addProgram(source).build(), host.hostAbi(), host.registry(), CompilerOptions.DEFAULT, plan,
                ExpressionIdentityHash.SHA_256);
        var invocations = new ArrayList<String>();
        for (var bytes : artifact.classes().values()) {
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(bytes.toByteArray()).accept(node, 0);
            for (var method : node.methods)
                for (var instruction : method.instructions)
                    if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)
                        invocations.add(call.owner + "." + call.name + call.desc);
        }
        assertTrue(invocations.contains("com/elfmcys/ysm/expression/host/builtin/MathBuiltins.abs(F)F"), invocations.toString());
        assertFalse(invocations.stream().anyMatch(i -> i.startsWith("independent/Uniform.pure")));
        var generatedLoader = new ClassLoader(loader) {
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                var bytes = artifact.classes().get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                var value = bytes.toByteArray();
                return defineClass(name, value, 0, value.length);
            }
        };
        var root = generatedLoader.loadClass(artifact.manifest().rootBinaryName());
        var roaming = (CompiledRoamingState)root.getMethod("semaweaveNewRoamingState", ResourceGenerationToken.class,
            RoamingRole.class).invoke(null, TestResourceGenerations.create(), RoamingRole.PRODUCER);
        @SuppressWarnings("unchecked")
        var generated = (GeneratedModuleRoot<MolangContext>)root.getConstructor(Object[].class, Object[].class,
            ValueType[].class, ChildContextAdapter.class, CompiledRoamingState.class, SubjectBindingFactory.class)
            .newInstance(TestComponentHolders.providers(host, plan).toDenseArray(), new Object[0],
                host.registry().types().values().stream().map(t -> t.type()).toArray(ValueType[]::new), null, roaming,
                subjectFactory(root, roaming));
        var subject = (Entity)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe()
            .allocateInstance(net.minecraft.world.entity.decoration.ArmorStand.class);
        var inputs = new InvocationInputs<Entity>() {
            public Entity entity() { return subject; }
            public float partialTick() { return .25f; }
        };
        var context = MolangContext.logical(subject, inputs, MolangContext.LogicalPhase.ACTION, inputs, roaming,
            new InvocationProvenance("owner", "uniform", 0, 1, 1), () -> false, () -> true, 4);
        context.configureLimits(host.executionLimits());
        try {
            context.bindExpression("uniform", 0); context.beginTopLevel(true);
            try {
                assertEquals(7f, generated.executeFloat(0, 0, context,
                    generated.subjectBindingFactory().bind(subject), context, ProgramArguments.empty()));
            } finally { context.endTopLevel(); context.releaseExpression(); }
            assertEquals(List.of(), declaration.owner().getField("committed").get(null));
            EffectTransaction.finish(context, EffectSink.direct(), true);
            assertEquals(List.of(3f), declaration.owner().getField("committed").get(null));
        } finally { context.closeRound(); }
    }

    private Path apiJar() throws Exception {
        Path path = temporary.resolve("ysm-public-api.jar");
        Path root = Path.of(MolangContextView.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var jar = new JarOutputStream(Files.newOutputStream(path));
            var files = Files.walk(root.resolve("com/elfmcys/ysm/api"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = root.relativize(file).toString().replace('\\', '/');
                if (!(name.contains("/annotation/") || name.contains("/molang/")))
                    continue;
                jar.putNextEntry(new JarEntry(name));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
        return path;
    }

    private Path extension(Path api, String name, String mod, String body) throws Exception {
        return extension(api, name, mod, "@YsmEntityComponent(\"" + mod + "\")", body);
    }

    private Path extension(Path api, String name, String mod, String marker, String body) throws Exception {
        String source =
            "package independent;import com.elfmcys.ysm.api.annotation.*;import com.elfmcys.ysm.api.molang.*;" + marker
                + " public class " + name + "{" + body + "}";
        return extensionSource(api, name, source);
    }

    private Path extensionSource(Path api, String name, String source) throws Exception {
        Path output = Files.createDirectory(temporary.resolve(name + "-classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var unit = new SimpleJavaFileObject(URI.create("string:///independent/" + name + ".java"),
                JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignore) {
                    return source;
                }
            };
            var task = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath",
                    api + File.pathSeparator + System.getProperty("java.class.path"), "-d", output.toString()),
                null, List.of(unit));
            task.setProcessors(List.of(new com.elfmcys.ysm.api.internal.processor.UnifiedDeclarationProcessor(),
                new ExtensionCompatibilityProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        Path path = temporary.resolve(name + ".jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(path)); var files = Files.walk(output)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                byte[] bytes = Files.readAllBytes(file);
                assertFalse(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("semaweave"));
                jar.putNextEntry(new JarEntry(output.relativize(file).toString().replace('\\', '/')));
                jar.write(bytes);
                jar.closeEntry();
            }
        }
        return path;
    }

    private static SubjectBindingFactory subjectFactory(Class<?> root, CompiledRoamingState roaming) {
        return subject -> {
            try {
                return (SubjectBinding)root
                    .getMethod("semaweaveBindSubject", Object.class, ResourceGenerationToken.class)
                    .invoke(null, subject, roaming.generation());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        };
    }

}
