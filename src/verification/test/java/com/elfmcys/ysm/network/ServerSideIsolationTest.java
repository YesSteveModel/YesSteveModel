package com.elfmcys.ysm.network;

import com.elfmcys.ysm.model.catalog.ReloadableModelCatalog;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.session.server.ServerConnectionRegistry;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.network.dispatch.ResourceDispatchWorker;
import com.elfmcys.ysm.network.dispatch.ServerAssetTransfers;
import com.elfmcys.ysm.network.forge.ForgeTransportPort;
import com.elfmcys.ysm.proto.network.ServerHelloProto;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static bytecode reachability gate verifying dedicated-server entrypoints never reach client types. */
class ServerSideIsolationTest {
    private static final String YSM_PREFIX = "com/elfmcys/ysm/";
    private static final String PROTOCOL_MESSAGES = "com/elfmcys/ysm/network/protocol/ProtocolMessages";
    private static final String DIST_EXECUTOR = "net/minecraftforge/fml/DistExecutor";
    private static final String DIST_CLASS = "net/minecraftforge/api/distmarker/Dist";
    private static final String NETWORK_DIRECTION = "net/minecraftforge/network/NetworkDirection";
    private static final List<String> FORBIDDEN_PREFIXES =
        List.of("net/minecraft/client/", "com/mojang/blaze3d/", "com/elfmcys/ysm/client/");
    private static final Set<String> DIST_GUARD_METHODS =
        Set.of("unsafeCallWhenOn", "unsafeRunWhenOn", "safeCallWhenOn", "safeRunWhenOn", "callWhenOn", "runWhenOn");
    private static final List<Class<?>> SERVER_ROOTS = List.of(ServerModelService.class, ServerAssetTransfers.class,
        ResourceDispatchWorker.class, ForgeTransportPort.class, ServerModelSession.class,
        ServerConnectionRegistry.class, ServerResourceRuntime.class, ReloadableModelCatalog.class);

    @Test
    void dedicatedServerEntrypointsDoNotReachClientClasses() throws Exception {
        var production = ProductionClasses.load();
        var scanner = new ReachabilityScanner(production);
        for (var root : SERVER_ROOTS) {
            scanner.seedRoot(Type.getInternalName(root));
        }
        var violations = scanner.scan();
        assertTrue(violations.isEmpty(),
            () -> "Dedicated-server bytecode reached client classes:\n" + String.join("\n", violations));
    }

    private record MethodKey(String owner, String name, String desc) {
        @Override
        public String toString() {
            return owner + "#" + name + desc;
        }
    }

    private static final class ReachabilityScanner {
        private final ProductionClasses classes;
        private final Set<String> visitedClasses = new HashSet<>();
        private final Map<MethodKey, String> methodTrace = new HashMap<>();
        private final ArrayDeque<MethodKey> queue = new ArrayDeque<>();
        private final List<String> violations = new ArrayList<>();

        private ReachabilityScanner(ProductionClasses classes) {
            this.classes = classes;
        }

        private void seedRoot(String internalName) {
            touchClass(internalName, "root:" + internalName);
            var node = classes.get(internalName);
            if (node == null) {
                throw new IllegalStateException("Missing server root class in production output: " + internalName);
            }
            for (var method : node.methods) {
                if ((method.access & Opcodes.ACC_SYNTHETIC) == 0) {
                    enqueueMethod(new MethodKey(internalName, method.name, method.desc), "root:" + internalName);
                }
            }
        }

        private List<String> scan() {
            while (!queue.isEmpty()) {
                var key = queue.removeFirst();
                var trace = methodTrace.get(key);
                var ownerNode = classes.get(key.owner());
                if (ownerNode == null) {
                    continue;
                }
                var method = findDeclaredMethod(ownerNode, key.name(), key.desc());
                if (method == null) {
                    continue;
                }
                inspectMethod(key, method, trace);
            }
            return violations;
        }

        private void touchClass(String internalName, String trace) {
            checkInternalName(internalName, trace);
            if (!internalName.startsWith(YSM_PREFIX) || !visitedClasses.add(internalName)) {
                return;
            }
            var node = classes.get(internalName);
            if (node == null) {
                return;
            }
            if (node.superName != null) {
                touchClass(node.superName, trace + " -> super " + node.superName);
            }
            for (var iface : node.interfaces) {
                touchClass(iface, trace + " -> implements " + iface);
            }
            for (var field : node.fields) {
                checkType(Type.getType(field.desc), trace + " -> field " + internalName + "." + field.name);
            }
            var clinit = findDeclaredMethod(node, "<clinit>", "()V");
            if (clinit != null) {
                enqueueMethod(new MethodKey(internalName, "<clinit>", "()V"), trace + " -> <clinit>");
            }
        }

        private void enqueueMethod(MethodKey key, String trace) {
            checkInternalName(key.owner(), trace);
            checkType(Type.getMethodType(key.desc()), trace);
            if (!key.owner().startsWith(YSM_PREFIX) || methodTrace.containsKey(key)) {
                return;
            }
            methodTrace.put(key, trace);
            queue.addLast(key);
        }

        private void inspectMethod(MethodKey key, MethodNode method, String trace) {
            if (method.tryCatchBlocks != null) {
                for (var block : method.tryCatchBlocks) {
                    if (block.type != null) {
                        checkInternalName(block.type, trace + " -> catch " + block.type);
                    }
                }
            }
            if (method.instructions == null) {
                return;
            }

            var distClientLambdas = collectDistClientGuardedLambdas(method);
            var clientDirectionSkipTargets = collectPlayToClientTargets(method);
            var isProtocolMessagesClinit = PROTOCOL_MESSAGES.equals(key.owner()) && "<clinit>".equals(key.name());
            String currentMessageDirection = null;

            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode field) {
                    if (isProtocolMessagesClinit
                        && "com/elfmcys/ysm/network/protocol/MessageDirection".equals(field.owner)
                        && field.getOpcode() == Opcodes.GETSTATIC) {
                        currentMessageDirection = field.name;
                    }
                    touchClass(field.owner, trace + " -> field " + field.owner + "." + field.name);
                    checkType(Type.getType(field.desc), trace + " -> fieldType " + field.owner + "." + field.name);
                } else if (insn instanceof TypeInsnNode typeInsn) {
                    checkType(Type.getObjectType(typeInsn.desc), trace + " -> type " + typeInsn.desc);
                    if (typeInsn.desc.startsWith(YSM_PREFIX)) {
                        touchClass(typeInsn.desc, trace + " -> type " + typeInsn.desc);
                    }
                } else if (insn instanceof MultiANewArrayInsnNode multi) {
                    checkType(Type.getType(multi.desc), trace + " -> newarray " + multi.desc);
                } else if (insn instanceof LdcInsnNode ldc) {
                    inspectConstant(ldc.cst, trace);
                } else if (insn instanceof MethodInsnNode call) {
                    if (clientDirectionSkipTargets.contains(insn)) {
                        continue;
                    }
                    touchClass(call.owner, trace + " -> " + call.owner + "#" + call.name);
                    checkType(Type.getMethodType(call.desc), trace + " -> " + call.owner + "#" + call.name);
                    if (call.owner.startsWith(YSM_PREFIX)) {
                        resolveAndEnqueueCall(call, trace + " -> " + call.owner + "#" + call.name);
                    }
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    if (distClientLambdas.contains(indy)) {
                        continue;
                    }
                    if (indy.bsm != null) {
                        checkInternalName(indy.bsm.getOwner(), trace + " -> indy bsm");
                    }
                    if (indy.bsmArgs != null) {
                        for (var arg : indy.bsmArgs) {
                            if (arg instanceof Handle handle) {
                                // Server-only registration skips SERVER_TO_CLIENT deferred handler lambdas
                                // while still validating direct method references (whose owner is not
                                // ProtocolMessages).
                                var isDeferredClientSpecLambda =
                                    isProtocolMessagesClinit && "SERVER_TO_CLIENT".equals(currentMessageDirection)
                                        && PROTOCOL_MESSAGES.equals(handle.getOwner())
                                        && handle.getName().startsWith("lambda$static$");
                                // BOTH-direction handlers route PLAY_TO_CLIENT at runtime; still reject direct client
                                // owners.
                                var isDeferredBothSpecLambda =
                                    isProtocolMessagesClinit && "BOTH".equals(currentMessageDirection)
                                        && PROTOCOL_MESSAGES.equals(handle.getOwner())
                                        && handle.getName().startsWith("lambda$static$");
                                if (isDeferredClientSpecLambda || isDeferredBothSpecLambda) {
                                    checkInternalName(handle.getOwner(), trace + " -> spec lambda");
                                    continue;
                                }
                                inspectHandle(handle,
                                    trace + " -> lambda " + handle.getOwner() + "#" + handle.getName());
                            } else {
                                inspectConstant(arg, trace);
                            }
                        }
                    }
                }
            }
        }

        private Set<AbstractInsnNode> collectDistClientGuardedLambdas(MethodNode method) {
            Set<AbstractInsnNode> skipped = new HashSet<>();
            var sawDistClient = false;
            AbstractInsnNode pendingIndy = null;
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && DIST_CLASS.equals(field.owner) && "CLIENT".equals(field.name)) {
                    sawDistClient = true;
                    pendingIndy = null;
                } else if (sawDistClient && insn instanceof InvokeDynamicInsnNode) {
                    pendingIndy = insn;
                } else if (sawDistClient && insn instanceof MethodInsnNode call && DIST_EXECUTOR.equals(call.owner)
                    && DIST_GUARD_METHODS.contains(call.name)) {
                    if (pendingIndy != null) {
                        skipped.add(pendingIndy);
                    }
                    sawDistClient = false;
                    pendingIndy = null;
                } else if (insn.getOpcode() == Opcodes.RETURN || insn.getOpcode() == Opcodes.ARETURN) {
                    sawDistClient = false;
                    pendingIndy = null;
                }
            }
            return skipped;
        }

        private Set<AbstractInsnNode> collectPlayToClientTargets(MethodNode method) {
            // Skips guarded single-branch calls inside `if (context.getDirection() ==
            // NetworkDirection.PLAY_TO_CLIENT)`.
            Set<AbstractInsnNode> skipped = new HashSet<>();
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && NETWORK_DIRECTION.equals(field.owner) && "PLAY_TO_CLIENT".equals(field.name)) {
                    var next = nextReal(field.getNext());
                    if (next != null && next.getOpcode() == Opcodes.IF_ACMPNE
                        && next instanceof org.objectweb.asm.tree.JumpInsnNode jump) {
                        for (var cursor = nextReal(next.getNext()); cursor != null && cursor != jump.label;
                            cursor = cursor.getNext()) {
                            if (cursor instanceof MethodInsnNode) {
                                skipped.add(cursor);
                            }
                        }
                    }
                }
            }
            return skipped;
        }

        private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
            while (insn != null && insn.getOpcode() == -1) {
                insn = insn.getNext();
            }
            return insn;
        }

        private void inspectConstant(Object cst, String trace) {
            if (cst instanceof Type type) {
                checkType(type, trace + " -> ldc " + type.getDescriptor());
            } else if (cst instanceof Handle handle) {
                inspectHandle(handle, trace + " -> handle " + handle.getOwner() + "#" + handle.getName());
            } else if (cst instanceof ConstantDynamic cd) {
                checkType(Type.getType(cd.getDescriptor()), trace + " -> condy");
                for (var i = 0; i < cd.getBootstrapMethodArgumentCount(); i++) {
                    inspectConstant(cd.getBootstrapMethodArgument(i), trace);
                }
            }
        }

        private void inspectHandle(Handle handle, String trace) {
            touchClass(handle.getOwner(), trace);
            if (handle.getTag() == Opcodes.H_GETFIELD || handle.getTag() == Opcodes.H_PUTFIELD
                || handle.getTag() == Opcodes.H_GETSTATIC || handle.getTag() == Opcodes.H_PUTSTATIC) {
                checkType(Type.getType(handle.getDesc()), trace);
            } else {
                checkType(Type.getMethodType(handle.getDesc()), trace);
                if (handle.getOwner().startsWith(YSM_PREFIX)) {
                    resolveHierarchyAndEnqueue(handle.getOwner(), handle.getName(), handle.getDesc(),
                        handle.getTag() == Opcodes.H_INVOKEINTERFACE || handle.getTag() == Opcodes.H_INVOKEVIRTUAL,
                        trace);
                }
            }
        }

        private void resolveAndEnqueueCall(MethodInsnNode call, String trace) {
            var polymorphic = call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE;
            resolveHierarchyAndEnqueue(call.owner, call.name, call.desc, polymorphic, trace);
        }

        private void resolveHierarchyAndEnqueue(String owner, String name, String desc, boolean polymorphic,
            String trace) {
            var direct = findMethodInHierarchy(owner, name, desc);
            if (direct != null) {
                enqueueMethod(direct, trace);
            }
            if (polymorphic) {
                for (var sub : classes.subtypesOf(owner)) {
                    var subNode = classes.get(sub);
                    if (subNode != null && !Modifier.isAbstract(subNode.access)
                        && findDeclaredMethod(subNode, name, desc) != null) {
                        enqueueMethod(new MethodKey(sub, name, desc), trace + " [override " + sub + "]");
                    }
                }
            }
        }

        private MethodKey findMethodInHierarchy(String owner, String name, String desc) {
            var visited = new HashSet<String>();
            var pending = new ArrayDeque<String>();
            pending.add(owner);
            while (!pending.isEmpty()) {
                var current = pending.removeFirst();
                if (!visited.add(current) || !current.startsWith(YSM_PREFIX)) {
                    continue;
                }
                var node = classes.get(current);
                if (node == null) {
                    continue;
                }
                if (findDeclaredMethod(node, name, desc) != null) {
                    return new MethodKey(current, name, desc);
                }
                if (node.superName != null) {
                    pending.addLast(node.superName);
                }
                pending.addAll(node.interfaces);
            }
            return null;
        }

        private static MethodNode findDeclaredMethod(ClassNode node, String name, String desc) {
            for (var method : node.methods) {
                if (method.name.equals(name) && method.desc.equals(desc)) {
                    return method;
                }
            }
            return null;
        }

        private void checkType(Type type, String trace) {
            if (type.getSort() == Type.METHOD) {
                checkType(type.getReturnType(), trace);
                for (var arg : type.getArgumentTypes()) {
                    checkType(arg, trace);
                }
            } else if (type.getSort() == Type.ARRAY) {
                checkType(type.getElementType(), trace);
            } else if (type.getSort() == Type.OBJECT) {
                checkInternalName(type.getInternalName(), trace);
            }
        }

        private void checkInternalName(String internalName, String trace) {
            if (internalName.startsWith("[")) {
                checkType(Type.getType(internalName), trace);
                return;
            }
            for (var forbidden : FORBIDDEN_PREFIXES) {
                if (internalName.startsWith(forbidden)) {
                    violations.add(internalName + " <- " + trace);
                    return;
                }
            }
        }
    }

    private static final class ProductionClasses {
        private final Map<String, ClassNode> byName;
        private final Map<String, Set<String>> subtypes = new HashMap<>();

        private ProductionClasses(Map<String, ClassNode> byName) {
            this.byName = byName;
            for (var node : byName.values()) {
                if (node.superName != null && node.superName.startsWith(YSM_PREFIX)) {
                    subtypes.computeIfAbsent(node.superName, ignored -> new HashSet<>()).add(node.name);
                }
                for (var iface : node.interfaces) {
                    if (iface.startsWith(YSM_PREFIX)) {
                        subtypes.computeIfAbsent(iface, ignored -> new HashSet<>()).add(node.name);
                    }
                }
            }
        }

        private ClassNode get(String internalName) {
            return byName.get(internalName);
        }

        private Set<String> subtypesOf(String owner) {
            var direct = subtypes.get(owner);
            if (direct == null || direct.isEmpty()) {
                return Set.of();
            }
            var all = new HashSet<String>();
            var pending = new ArrayDeque<>(direct);
            while (!pending.isEmpty()) {
                var next = pending.removeFirst();
                if (all.add(next)) {
                    var children = subtypes.get(next);
                    if (children != null) {
                        pending.addAll(children);
                    }
                }
            }
            return all;
        }

        private static ProductionClasses load() throws IOException, URISyntaxException {
            Map<String, ClassNode> loaded = new LinkedHashMap<>();
            var roots = new LinkedHashSet<Path>();
            roots.add(codeSourcePath(ServerModelService.class));
            roots.add(codeSourcePath(ServerHelloProto.class));
            for (var root : roots) {
                if (Files.isDirectory(root)) {
                    try (var stream = Files.walk(root)) {
                        for (var path : stream.filter(Files::isRegularFile).toList()) {
                            var rel = root.relativize(path).toString().replace('\\', '/');
                            if (rel.startsWith(YSM_PREFIX) && rel.endsWith(".class")) {
                                try (var in = Files.newInputStream(path)) {
                                    readClass(in, loaded);
                                }
                            }
                        }
                    }
                } else if (Files.isRegularFile(root)) {
                    try (var jar = new JarFile(root.toFile())) {
                        var entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            var entry = entries.nextElement();
                            if (!entry.isDirectory() && entry.getName().startsWith(YSM_PREFIX)
                                && entry.getName().endsWith(".class")) {
                                try (var in = jar.getInputStream(entry)) {
                                    readClass(in, loaded);
                                }
                            }
                        }
                    }
                }
            }
            return new ProductionClasses(loaded);
        }

        private static void readClass(InputStream in, Map<String, ClassNode> target) throws IOException {
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            target.putIfAbsent(node.name, node);
        }

        private static Path codeSourcePath(Class<?> anchor) throws URISyntaxException {
            return Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath()
                .normalize();
        }
    }
}
