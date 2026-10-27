package com.elfmcys.ysm.client.compat.swarfare;

import com.elfmcys.ysm.util.NativeLibUtil;
import net.minecraftforge.eventbus.api.IEventBus;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class SWarfareHandAdapterTest {
    @Test
    void rejectsAbsentOldAndNewVersionsBeforeConstructingOptionalHandler() {
        if (Boolean.getBoolean("ysm.test.noSuperb")) {
            assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.atsuishio.superbwarfare.api.event.RenderPlayerArmEvent"));
        }
        for (String version : new String[] {null, "0.8.7", "0.8.7.1-c6d401e0e", "0.8.8", "1.0"}) {
            assertFalse(SWarfareCompat.registerHandAdapter(version, null, () -> {
                throw new AssertionError("early optional load");
            }));
        }
    }

    @Test
    void registrationRollbackRemovesExactlyThePartlyRegisteredHandler() {
        var seen = new ArrayList<Object>();
        var handler = new Object();
        var bus = (IEventBus)Proxy.newProxyInstance(IEventBus.class.getClassLoader(), new Class<?>[] {IEventBus.class},
            (proxy, method, args) -> {
                if (method.getName().equals("register")) {
                    seen.add(args[0]);
                    throw new NoSuchMethodError("simulated registration API failure");
                }
                if (method.getName().equals("unregister")) {
                    seen.add(args[0]);
                    return null;
                }
                throw new AssertionError(method.getName());
            });
        assertFalse(SWarfareCompat.registerHandAdapter("0.8.7.1", bus, () -> handler));
        assertEquals(List.of(handler, handler), seen);
        assertFalse(SWarfareCompat.registerHandAdapter("0.8.7.1", bus, () -> {
            throw new NoClassDefFoundError("simulated missing event");
        }));
        assertEquals(2, seen.size());
    }

    @Test
    void absentClasspathCanLoadBootstrapAndRejectWithoutOptionalClassResolution() throws Exception {
        var loader = new AdapterLoader(false);
        var bootstrap = loader.loadClass(SWarfareCompat.class.getName());
        var register =
            bootstrap.getDeclaredMethod("registerHandAdapter", String.class, IEventBus.class, Supplier.class);
        register.setAccessible(true);
        assertEquals(false, register.invoke(null, null, null, (Supplier<Object>)() -> {
            throw new AssertionError();
        }));
        assertEquals(false, register.invoke(null, "0.8.7", null, (Supplier<Object>)() -> {
            throw new AssertionError();
        }));
        assertEquals(0, loader.optionalLoads);
    }

    @Test
    void incompatibleGetterDisablesOnlyHandAdapterWithoutCancelingHost() throws Exception {
        var loader = new AdapterLoader(true);
        var eventType = loader.loadClass("com.atsuishio.superbwarfare.api.event.RenderPlayerArmEvent");
        var handlerType = loader.loadClass(ReplacePlayerArmRender.class.getName());
        var handler = handlerType.getConstructor().newInstance();
        var event = eventType.getConstructor().newInstance();
        var available = NativeLibUtil.class.getDeclaredField("AVAILABLE");
        available.setAccessible(true);
        boolean previous = available.getBoolean(null);
        available.setBoolean(null, true);
        try {
            handlerType.getMethod("onRenderHand", eventType).invoke(handler, event);
            var enabled = handlerType.getDeclaredField("enabled");
            enabled.setAccessible(true);
            assertFalse(enabled.getBoolean(handler));
            assertEquals(false, eventType.getMethod("isCanceled").invoke(event));
            handlerType.getMethod("onRenderHand", eventType).invoke(handler, event);
            assertEquals(false, eventType.getMethod("isCanceled").invoke(event));
        } finally {
            available.setBoolean(null, previous);
        }
    }

    private static final class AdapterLoader extends ClassLoader {
        final boolean brokenEvent;
        int optionalLoads;

        AdapterLoader(boolean brokenEvent) {
            super(SWarfareCompat.class.getClassLoader());
            this.brokenEvent = brokenEvent;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                var existing = findLoadedClass(name);
                if (existing != null)
                    return existing;
                if (name.startsWith("com.atsuishio.superbwarfare.")) {
                    optionalLoads++;
                    if (brokenEvent && name.equals("com.atsuishio.superbwarfare.api.event.RenderPlayerArmEvent")) {
                        byte[] bytes = brokenEvent();
                        return defineClass(name, bytes, 0, bytes.length);
                    }
                    throw new ClassNotFoundException(name);
                }
                if (name.equals(SWarfareCompat.class.getName())
                    || name.startsWith(ReplacePlayerArmRender.class.getName())) {
                    try (var source = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] bytes = source.readAllBytes();
                        var result = defineClass(name, bytes, 0, bytes.length);
                        if (resolve)
                            resolveClass(result);
                        return result;
                    } catch (java.io.IOException failure) {
                        throw new ClassNotFoundException(name, failure);
                    }
                }
                return super.loadClass(name, resolve);
            }
        }

        private static byte[] brokenEvent() {
            var writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/atsuishio/superbwarfare/api/event/RenderPlayerArmEvent",
                null, "net/minecraftforge/eventbus/api/Event", null);
            writer.visitAnnotation("Lnet/minecraftforge/eventbus/api/Cancelable;", true).visitEnd();
            var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.visitCode();
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraftforge/eventbus/api/Event", "<init>", "()V",
                false);
            constructor.visitInsn(Opcodes.RETURN);
            constructor.visitMaxs(1, 1);
            constructor.visitEnd();
            writer.visitEnd();
            return writer.toByteArray();
        }
    }
}
