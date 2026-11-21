package com.elfmcys.ysm.api.internal.event;

import static org.junit.jupiter.api.Assertions.*;

import com.elfmcys.ysm.api.annotation.YsmEventHandler;
import java.lang.annotation.ElementType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.forgespi.language.ModFileScanData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class ExtensionEventHandlerLoaderTest {
    private static ClassLoader fixtures;

    @BeforeAll
    static void compileHandlers() throws Exception {
        var sources = new ArrayList<JavaFileObject>();
        sources.add(source("ProbeEvent", """
            package handlerfixture;
            public final class ProbeEvent extends net.minecraftforge.eventbus.api.Event {}
            """));
        for (var name : List.of("HealthyBefore", "HealthyAfter", "Rejected", "ThrowingConstructor")) {
            var constructor = name.equals("ThrowingConstructor")
                ? "constructs++; throw new IllegalStateException(\"constructor failed\");" : "constructs++;";
            sources.add(source(name, """
                package handlerfixture;
                public final class %s {
                    public static int calls;
                    public static int constructs;
                    public %s() { %s }
                    @net.minecraftforge.eventbus.api.SubscribeEvent
                    public void onEvent(ProbeEvent event) { calls++; }
                }
                """.formatted(name, name, constructor)));
            sources.add(checker(name, !name.equals("Rejected")));
        }
        for (int invalid = 0; invalid < 8; invalid++) {
            var name = "Partial" + invalid;
            var methods = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                methods.append("@net.minecraftforge.eventbus.api.SubscribeEvent public void method").append(index)
                    .append(index == invalid ? "(String event) {}\n" : "(ProbeEvent event) { calls++; }\n");
            }
            sources.add(source(name, """
                package handlerfixture;
                public final class %s {
                    public static int calls;
                    public %s() {}
                    %s
                }
                """.formatted(name, name, methods)));
            sources.add(checker(name, true));
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var eventBus = Path.of(Event.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // Forge's generated listeners resolve handlers through the event bus class loader.
            var root = Path
                .of(ExtensionEventHandlerLoaderTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var options =
                List.of("--release", "17", "-proc:none", "-classpath", eventBus.toString(), "-d", root.toString());
            assertTrue(Boolean.TRUE.equals(compiler.getTask(null, manager, null, options, null, sources).call()));
        }
        fixtures = ExtensionEventHandlerLoaderTest.class.getClassLoader();
    }

    @Test
    void selectsTypeAnnotationsByNameThenDeduplicatesAndSortsHandlers() {
        ModFileScanData first = new ModFileScanData();
        first.getAnnotations().add(annotation(ElementType.TYPE, "sample.ZHandler", YsmEventHandler.class));
        first.getAnnotations().add(annotation(ElementType.METHOD, "sample.Ignored#method", YsmEventHandler.class));
        first.getAnnotations().add(annotation(ElementType.TYPE, "sample.IgnoredAnnotation", Deprecated.class));

        ModFileScanData second = new ModFileScanData();
        second.getAnnotations().add(annotation(ElementType.TYPE, "sample.A$Nested", YsmEventHandler.class));
        second.getAnnotations().add(annotation(ElementType.TYPE, "sample.ZHandler", YsmEventHandler.class));

        assertEquals(List.of("sample.A$Nested", "sample.ZHandler"),
            ExtensionEventHandlerLoader.discoverHandlerNames(List.of(first, second)));
    }

    @Test
    void failedRealBusRegistrationRollsBackItsInstalledPrefixAndKeepsHealthyHandlers() throws Exception {
        var name = partiallyRegisteredHandler();
        var real = BusBuilder.builder().build();
        var registered = new AtomicReference<Object>();
        var unregistered = new AtomicReference<Object>();
        var installedPrefix = new AtomicInteger();
        var bus = forwardingBus(real, (method, target) -> {
            if (method.equals("register") && target.getClass().getSimpleName().equals(name))
                registered.set(target);
            if (method.equals("unregister"))
                unregistered.set(target);
        }, failure -> {
            real.post(event());
            installedPrefix.set(calls(name));
        });

        register(bus, "HealthyBefore");
        register(bus, name);
        register(bus, "HealthyAfter");
        assertTrue(installedPrefix.get() > 0, "The real event bus must have installed a listener before failing");
        assertSame(registered.get(), unregistered.get());
        resetCalls(name, "HealthyBefore", "HealthyAfter");
        real.post(event());
        assertEquals(0, calls(name));
        assertEquals(1, calls("HealthyBefore"));
        assertEquals(1, calls("HealthyAfter"));
    }

    @Test
    void rollbackFailurePreservesBothCausesAndAllowsTheNextHandler() throws Exception {
        var real = BusBuilder.builder().build();
        var failure = new IllegalArgumentException("registration failed after installing listeners");
        var rollback = new LinkageError("unregister failed");
        var registered = new AtomicReference<Object>();
        var unregistered = new AtomicReference<Object>();
        var bus = (IEventBus)Proxy.newProxyInstance(IEventBus.class.getClassLoader(), new Class<?>[] {IEventBus.class},
            (proxy, method, arguments) -> {
                var target = arguments == null || arguments.length == 0 ? null : arguments[0];
                if (method.getName().equals("register") && target.getClass().getSimpleName().equals("HealthyBefore")) {
                    registered.set(target);
                    real.register(target);
                    throw failure;
                }
                if (method.getName().equals("unregister")) {
                    unregistered.set(target);
                    throw rollback;
                }
                try {
                    return method.invoke(real, arguments);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            });
        register(bus, "HealthyBefore");
        register(bus, "HealthyAfter");
        assertSame(registered.get(), unregistered.get());
        assertArrayEquals(new Throwable[] {rollback}, failure.getSuppressed());
        resetCalls("HealthyBefore", "HealthyAfter");
        real.post(event());
        assertEquals(1, calls("HealthyBefore"), "A failed rollback cannot promise that listeners are gone");
        assertEquals(1, calls("HealthyAfter"));
    }

    @Test
    void rejectedQualificationAndConstructorFailureNeverEnterTheBus() throws Exception {
        var operations = new ArrayList<String>();
        var bus = forwardingBus(BusBuilder.builder().build(), (method, target) -> operations.add(method), ignored -> {
            fail("Qualification and construction failures must precede event bus registration");
        });
        register(bus, "Rejected");
        register(bus, "ThrowingConstructor");
        assertTrue(operations.isEmpty());
        assertEquals(0, fixtures.loadClass("handlerfixture.Rejected").getField("constructs").getInt(null));
        assertEquals(1, fixtures.loadClass("handlerfixture.ThrowingConstructor").getField("constructs").getInt(null));
    }

    private static String partiallyRegisteredHandler() throws Exception {
        for (int invalid = 0; invalid < 8; invalid++) {
            var name = "Partial" + invalid;
            var handler = fixtures.loadClass("handlerfixture." + name).getConstructor().newInstance();
            var bus = BusBuilder.builder().build();
            assertThrows(IllegalArgumentException.class, () -> bus.register(handler));
            bus.post(event());
            var installed = calls(name);
            bus.unregister(handler);
            resetCalls(name);
            if (installed > 0)
                return name;
        }
        throw new AssertionError("No fixture exposed a partially installed prefix in the real event bus");
    }

    private static IEventBus forwardingBus(IEventBus real, java.util.function.BiConsumer<String, Object> observe,
        ThrowingConsumer failedRegistration) {
        return (IEventBus)Proxy.newProxyInstance(IEventBus.class.getClassLoader(), new Class<?>[] {IEventBus.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("register") || method.getName().equals("unregister"))
                    observe.accept(method.getName(), arguments[0]);
                try {
                    return method.invoke(real, arguments);
                } catch (InvocationTargetException failure) {
                    if (method.getName().equals("register"))
                        failedRegistration.accept(failure.getCause());
                    throw failure.getCause();
                }
            });
    }

    private static void register(IEventBus bus, String name) {
        ExtensionEventHandlerLoader.registerOne(bus, "handlerfixture." + name, fixtures);
    }

    private static Event event() throws Exception {
        return (Event)fixtures.loadClass("handlerfixture.ProbeEvent").getConstructor().newInstance();
    }

    private static int calls(String name) throws Exception {
        return fixtures.loadClass("handlerfixture." + name).getField("calls").getInt(null);
    }

    private static void resetCalls(String... names) throws Exception {
        for (var name : names)
            fixtures.loadClass("handlerfixture." + name).getField("calls").setInt(null, 0);
    }

    private static JavaFileObject checker(String name, boolean compatible) {
        return source(name + "CompatibilityChecker", """
            package handlerfixture;
            public final class %sCompatibilityChecker {
                public static Result check() { return new Result(); }
                public static final class Result {
                    public String status() { return "%s"; }
                    public boolean isCompatible() { return %s; }
                    public boolean coverageComplete() { return true; }
                    public java.util.List<String> issues() { return java.util.List.of(); }
                }
            }
            """.formatted(name, compatible ? "COMPATIBLE" : "INCOMPATIBLE", compatible));
    }

    private static JavaFileObject source(String name, String content) {
        return new SimpleJavaFileObject(URI.create("string:///handlerfixture/" + name + ".java"),
            JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingConsumer {
        void accept(Throwable failure) throws Exception;
    }

    private static ModFileScanData.AnnotationData annotation(ElementType target, String memberName,
        Class<?> annotationType) {
        return new ModFileScanData.AnnotationData(Type.getType(annotationType), target, Type.getType(Object.class),
            memberName, Map.of());
    }
}
