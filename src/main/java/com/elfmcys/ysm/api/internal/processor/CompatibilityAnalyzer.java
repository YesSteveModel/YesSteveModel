package com.elfmcys.ysm.api.internal.processor;

import com.elfmcys.ysm.api.internal.processor.asm.ConstantDynamic;
import com.elfmcys.ysm.api.internal.processor.asm.Handle;
import com.elfmcys.ysm.api.internal.processor.asm.Opcodes;
import com.elfmcys.ysm.api.internal.processor.asm.Type;
import com.elfmcys.ysm.api.internal.processor.asm.signature.SignatureReader;
import com.elfmcys.ysm.api.internal.processor.asm.signature.SignatureVisitor;
import com.elfmcys.ysm.api.internal.processor.asm.tree.AbstractInsnNode;
import com.elfmcys.ysm.api.internal.processor.asm.tree.AnnotationNode;
import com.elfmcys.ysm.api.internal.processor.asm.tree.ClassNode;
import com.elfmcys.ysm.api.internal.processor.asm.tree.FieldInsnNode;
import com.elfmcys.ysm.api.internal.processor.asm.tree.FieldNode;
import com.elfmcys.ysm.api.internal.processor.asm.tree.FrameNode;
import com.elfmcys.ysm.api.internal.processor.asm.tree.InvokeDynamicInsnNode;
import com.elfmcys.ysm.api.internal.processor.asm.tree.LdcInsnNode;
import com.elfmcys.ysm.api.internal.processor.asm.tree.MethodInsnNode;
import com.elfmcys.ysm.api.internal.processor.asm.tree.MethodNode;
import com.elfmcys.ysm.api.internal.processor.asm.tree.MultiANewArrayInsnNode;
import com.elfmcys.ysm.api.internal.processor.asm.tree.TypeInsnNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.elfmcys.ysm.api.internal.processor.ExtensionPlan.RequirementKind.FIELD;
import static com.elfmcys.ysm.api.internal.processor.ExtensionPlan.RequirementKind.METHOD;

final class CompatibilityAnalyzer {
    static final String YSM_PREFIX = "com/elfmcys/ysm/";
    static final String MINECRAFT_PREFIX = "net/minecraft/";
    static final String MOJANG_PREFIX = "com/mojang/";
    static final String MOJANG_COMMAND_PREFIX = "com/mojang/brigadier/";

    private static final Set<String> KNOWN_BOOTSTRAPS = Set.of("java/lang/invoke/LambdaMetafactory.metafactory",
        "java/lang/invoke/LambdaMetafactory.altMetafactory", "java/lang/invoke/StringConcatFactory.makeConcat",
        "java/lang/invoke/StringConcatFactory.makeConcatWithConstants", "java/lang/runtime/ObjectMethods.bootstrap",
        "java/lang/runtime/SwitchBootstraps.typeSwitch", "java/lang/runtime/SwitchBootstraps.enumSwitch");

    private final ClassRepository repository;

    CompatibilityAnalyzer(ClassRepository repository) {
        this.repository = repository;
    }

    ExtensionPlan.AnalysisResult analyze(ExtensionPlan plan, ExtensionPlan.CheckGroup group) {
        var result = new ExtensionPlan.AnalysisResult();
        Optional<ClassNode> root = repository.find(plan.ownerInternalName());
        if (root.isEmpty()) {
            result.issues.add(new ExtensionPlan.CoverageIssue("OWNED_CODE_UNAVAILABLE", plan.ownerBinaryName,
                "The annotated class was not present in javac CLASS_OUTPUT."));
            return result;
        }

        Set<String> structuralClasses = new HashSet<>();
        scanOwnedHierarchy(root.get(), group, result, structuralClasses);

        Set<ExtensionPlan.MethodKey> bodyRoots = new LinkedHashSet<>();
        if (group.wholeClass) {
            collectOwnedHierarchyMethods(root.get(), group, result, new HashSet<>(), bodyRoots);
        } else {
            bodyRoots.addAll(group.roots);
        }

        Set<ExtensionPlan.MethodKey> visitedMethods = new HashSet<>();
        Deque<ExtensionPlan.MethodKey> pending = new ArrayDeque<>(bodyRoots);
        while (!pending.isEmpty()) {
            ExtensionPlan.MethodKey key = pending.removeFirst();
            if (!visitedMethods.add(key)) {
                continue;
            }
            Optional<ResolvedMethod> resolved = resolveMethod(key.owner(), key.name(), key.descriptor());
            if (resolved.isEmpty()) {
                if (isOwned(key.owner(), group)) {
                    addIssue(result, "OWNED_CODE_UNAVAILABLE", key.display(),
                        "An owned call-graph method could not be read from CLASS_OUTPUT or CLASS_PATH.");
                }
                continue;
            }
            scanMethodBody(resolved.get(), group, result, pending);
        }
        return result;
    }

    private void scanOwnedHierarchy(ClassNode node, ExtensionPlan.CheckGroup group, ExtensionPlan.AnalysisResult result,
        Set<String> visited) {
        if (!visited.add(node.name)) {
            return;
        }

        collectType(node.name, result);
        collectType(node.superName, result);
        collectHierarchyParent(node.superName, false, result);
        for (String interfaceName : node.interfaces) {
            collectType(interfaceName, result);
            collectHierarchyParent(interfaceName, true, result);
        }
        collectSignature(node.signature, false, result);
        if (containsMixinAnnotation(node.visibleAnnotations) || containsMixinAnnotation(node.invisibleAnnotations)) {
            addIssue(result, "MIXIN_RUNTIME_TRANSFORM", DescriptorUtil.binaryName(node.name),
                "Mixin can alter the effective runtime call graph after javac analysis.");
        }

        for (FieldNode field : node.fields) {
            collectDescriptor(field.desc, result);
            collectSignature(field.signature, true, result);
        }
        for (MethodNode method : node.methods) {
            if (containsMixinAnnotation(method.visibleAnnotations)
                || containsMixinAnnotation(method.invisibleAnnotations)) {
                addIssue(result, "MIXIN_RUNTIME_TRANSFORM",
                    DescriptorUtil.binaryName(node.name) + '#' + method.name + method.desc,
                    "Mixin annotations can alter linkage or execution after javac analysis.");
            }
            if (!method.name.equals("<clinit>") && !method.name.equals("<init>")) {
                collectDescriptor(method.desc, result);
                collectSignature(method.signature, false, result);
                if (method.exceptions != null) {
                    for (String exception : method.exceptions) {
                        collectType(exception, result);
                    }
                }
                collectOverriddenYsmMethod(node, method, result);
            }
        }

        visitOwnedParent(node.superName, group, result, visited);
        for (String interfaceName : node.interfaces) {
            visitOwnedParent(interfaceName, group, result, visited);
        }
    }

    private void visitOwnedParent(String internalName, ExtensionPlan.CheckGroup group,
        ExtensionPlan.AnalysisResult result, Set<String> visited) {
        if (internalName == null || !isOwned(internalName, group)) {
            return;
        }
        repository.find(internalName).ifPresentOrElse(node -> scanOwnedHierarchy(node, group, result, visited),
            () -> addIssue(result, "OWNED_CODE_UNAVAILABLE", DescriptorUtil.binaryName(internalName),
                "An owned superclass or interface could not be read."));
    }

    private void collectOwnedHierarchyMethods(ClassNode node, ExtensionPlan.CheckGroup group,
        ExtensionPlan.AnalysisResult result, Set<String> visited, Set<ExtensionPlan.MethodKey> methods) {
        if (!visited.add(node.name)) {
            return;
        }
        for (MethodNode method : node.methods) {
            methods.add(new ExtensionPlan.MethodKey(node.name, method.name, method.desc));
        }
        collectOwnedParentMethods(node.superName, group, result, visited, methods);
        for (String interfaceName : node.interfaces) {
            collectOwnedParentMethods(interfaceName, group, result, visited, methods);
        }
    }

    private void collectOwnedParentMethods(String internalName, ExtensionPlan.CheckGroup group,
        ExtensionPlan.AnalysisResult result, Set<String> visited, Set<ExtensionPlan.MethodKey> methods) {
        if (internalName == null || !isOwned(internalName, group)) {
            return;
        }
        repository.find(internalName).ifPresentOrElse(
            node -> collectOwnedHierarchyMethods(node, group, result, visited, methods),
            () -> addIssue(result, "OWNED_CODE_UNAVAILABLE", DescriptorUtil.binaryName(internalName),
                "Method bodies in this owned hierarchy node are not available."));
    }

    private void collectOverriddenYsmMethod(ClassNode owner, MethodNode method, ExtensionPlan.AnalysisResult result) {
        int forbidden = Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE;
        if ((method.access & forbidden) != 0 || method.name.startsWith("<")) {
            return;
        }
        for (String parent : directParents(owner)) {
            Optional<ResolvedMethod> inherited = resolveMethod(parent, method.name, method.desc);
            if (inherited.isPresent() && isYsm(inherited.get().owner.name)) {
                result.requirements.add(new ExtensionPlan.Requirement(METHOD, inherited.get().owner.name, method.name,
                    method.desc, true, false));
            }
        }
    }

    private void scanMethodBody(ResolvedMethod resolved, ExtensionPlan.CheckGroup group,
        ExtensionPlan.AnalysisResult result, Deque<ExtensionPlan.MethodKey> pending) {
        MethodNode method = resolved.method;
        collectDescriptor(method.desc, result);
        if (method.tryCatchBlocks != null) {
            method.tryCatchBlocks.forEach(block -> collectType(block.type, result));
        }

        Set<ConstantDynamic> constants = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode typeInsn) {
                collectType(typeInsn.desc, result);
                if (typeInsn.getOpcode() == Opcodes.NEW && isYsm(typeInsn.desc)) {
                    result.requirements.add(new ExtensionPlan.Requirement(
                        ExtensionPlan.RequirementKind.INSTANTIABLE_CLASS, typeInsn.desc, "", "", false, false));
                }
            } else if (instruction instanceof FieldInsnNode fieldInsn) {
                collectDescriptor(fieldInsn.desc, result);
                collectCompileOwnerKind(fieldInsn.owner, result);
                collectYsmField(fieldInsn.owner, fieldInsn.name, fieldInsn.desc,
                    fieldInsn.getOpcode() == Opcodes.GETSTATIC || fieldInsn.getOpcode() == Opcodes.PUTSTATIC, result);
            } else if (instruction instanceof MethodInsnNode methodInsn) {
                collectDescriptor(methodInsn.desc, result);
                collectOwnerKind(methodInsn.owner, methodInsn.itf, result);
                collectYsmMethod(methodInsn.owner, methodInsn.name, methodInsn.desc,
                    methodInsn.getOpcode() == Opcodes.INVOKESTATIC, result);
                if (methodInsn.getOpcode() == Opcodes.INVOKEVIRTUAL
                    || methodInsn.getOpcode() == Opcodes.INVOKEINTERFACE) {
                    warnUnknownDynamicDispatch(methodInsn.owner, methodInsn.name, methodInsn.desc, group, result);
                }
                followOwnedMethod(methodInsn.owner, methodInsn.name, methodInsn.desc, group, result, pending);
            } else if (instruction instanceof InvokeDynamicInsnNode dynamicInsn) {
                collectDescriptor(dynamicInsn.desc, result);
                inspectHandle(dynamicInsn.bsm, group, result, pending);
                for (Object argument : dynamicInsn.bsmArgs) {
                    inspectConstant(argument, group, result, pending, constants);
                }
                if (!isKnownBootstrap(dynamicInsn.bsm)) {
                    addIssue(result, "DYNAMIC_TARGET_UNKNOWN",
                        DescriptorUtil.binaryName(resolved.owner.name) + '#' + method.name + method.desc,
                        "invokedynamic bootstrap " + DescriptorUtil.binaryName(dynamicInsn.bsm.getOwner()) + '.'
                            + dynamicInsn.bsm.getName() + " may resolve targets that are not statically visible.");
                }
            } else if (instruction instanceof LdcInsnNode ldcInsn) {
                inspectConstant(ldcInsn.cst, group, result, pending, constants);
            } else if (instruction instanceof MultiANewArrayInsnNode arrayInsn) {
                collectDescriptor(arrayInsn.desc, result);
            } else if (instruction instanceof FrameNode frame) {
                collectFrameTypes(frame.local, result);
                collectFrameTypes(frame.stack, result);
            }
        }
    }

    private void inspectConstant(Object value, ExtensionPlan.CheckGroup group, ExtensionPlan.AnalysisResult result,
        Deque<ExtensionPlan.MethodKey> pending, Set<ConstantDynamic> visitedConstants) {
        if (value instanceof Type type) {
            if (type.getSort() == Type.METHOD) {
                collectDescriptor(type.getDescriptor(), result);
            } else {
                collectType(type.getSort() == Type.OBJECT ? type.getInternalName() : type.getDescriptor(), result);
            }
        } else if (value instanceof Handle handle) {
            inspectHandle(handle, group, result, pending);
        } else if (value instanceof ConstantDynamic dynamic && visitedConstants.add(dynamic)) {
            collectDescriptor(dynamic.getDescriptor(), result);
            inspectHandle(dynamic.getBootstrapMethod(), group, result, pending);
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                inspectConstant(dynamic.getBootstrapMethodArgument(index), group, result, pending, visitedConstants);
            }
            if (!isKnownBootstrap(dynamic.getBootstrapMethod())) {
                addIssue(result, "DYNAMIC_TARGET_UNKNOWN", dynamic.getName() + ':' + dynamic.getDescriptor(),
                    "ConstantDynamic bootstrap " + DescriptorUtil.binaryName(dynamic.getBootstrapMethod().getOwner())
                        + '.' + dynamic.getBootstrapMethod().getName()
                        + " may resolve references that are not statically visible.");
            }
        }
    }

    private void inspectHandle(Handle handle, ExtensionPlan.CheckGroup group, ExtensionPlan.AnalysisResult result,
        Deque<ExtensionPlan.MethodKey> pending) {
        collectDescriptor(handle.getDesc(), result);
        collectOwnerKind(handle.getOwner(), handle.isInterface(), result);
        if (handle.getTag() <= Opcodes.H_PUTSTATIC) {
            collectYsmField(handle.getOwner(), handle.getName(), handle.getDesc(),
                handle.getTag() == Opcodes.H_GETSTATIC || handle.getTag() == Opcodes.H_PUTSTATIC, result);
        } else {
            collectYsmMethod(handle.getOwner(), handle.getName(), handle.getDesc(),
                handle.getTag() == Opcodes.H_INVOKESTATIC, result);
            if (handle.getTag() == Opcodes.H_INVOKEVIRTUAL || handle.getTag() == Opcodes.H_INVOKEINTERFACE) {
                warnUnknownDynamicDispatch(handle.getOwner(), handle.getName(), handle.getDesc(), group, result);
            }
            followOwnedMethod(handle.getOwner(), handle.getName(), handle.getDesc(), group, result, pending);
        }
    }

    private void warnUnknownDynamicDispatch(String owner, String name, String descriptor,
        ExtensionPlan.CheckGroup group, ExtensionPlan.AnalysisResult result) {
        if (!isOwned(owner, group)) {
            return;
        }
        Optional<ResolvedMethod> target = resolveMethod(owner, name, descriptor);
        if (target.isEmpty()) {
            return;
        }
        int fixedMethodFlags = Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
        boolean fixed = (target.get().owner.access & Opcodes.ACC_FINAL) != 0
            || (target.get().method.access & fixedMethodFlags) != 0;
        if (!fixed) {
            addIssue(result, "DYNAMIC_DISPATCH_UNKNOWN", DescriptorUtil.binaryName(owner) + '#' + name + descriptor,
                "Virtual dispatch may select an override outside the statically reachable owned graph.");
        }
    }

    private void followOwnedMethod(String owner, String name, String descriptor, ExtensionPlan.CheckGroup group,
        ExtensionPlan.AnalysisResult result, Deque<ExtensionPlan.MethodKey> pending) {
        if (!isOwned(owner, group)) {
            return;
        }
        Optional<ResolvedMethod> target = resolveMethod(owner, name, descriptor);
        if (target.isPresent() && isOwned(target.get().owner.name, group)) {
            pending.addLast(new ExtensionPlan.MethodKey(target.get().owner.name, target.get().method.name,
                target.get().method.desc));
        } else if (target.isEmpty()) {
            addIssue(result, "OWNED_CODE_UNAVAILABLE", DescriptorUtil.binaryName(owner) + '#' + name + descriptor,
                "The owned call target could not be resolved.");
        }
    }

    private void collectYsmMethod(String owner, String name, String descriptor, boolean staticMember,
        ExtensionPlan.AnalysisResult result) {
        if (!isYsm(owner)) {
            return;
        }
        Optional<ResolvedMethod> declaration = resolveMethod(owner, name, descriptor);
        if (declaration.isEmpty() || isYsm(declaration.get().owner.name)) {
            ExtensionPlan.RequirementKind kind =
                name.equals("<init>") ? ExtensionPlan.RequirementKind.CONSTRUCTOR : METHOD;
            result.requirements
                .add(new ExtensionPlan.Requirement(kind, owner, name, descriptor, name.equals("<init>"), staticMember));
            collectType(owner, result);
        }
    }

    private void collectYsmField(String owner, String name, String descriptor, boolean staticMember,
        ExtensionPlan.AnalysisResult result) {
        if (!isYsm(owner)) {
            return;
        }
        Optional<ResolvedField> declaration = resolveField(owner, name, descriptor);
        if (declaration.isEmpty() || isYsm(declaration.get().owner.name)) {
            result.requirements.add(new ExtensionPlan.Requirement(FIELD, owner, name, descriptor, false, staticMember));
            collectType(owner, result);
        }
    }

    private void collectDescriptor(String descriptor, ExtensionPlan.AnalysisResult result) {
        if (descriptor == null || descriptor.isEmpty()) {
            return;
        }
        for (String internalName : DescriptorUtil.referencedInternalNames(descriptor)) {
            collectType(internalName, result);
        }
    }

    private void collectHierarchyParent(String internalName, boolean interfaceType,
        ExtensionPlan.AnalysisResult result) {
        if (!isYsm(internalName)) {
            return;
        }
        result.requirements.add(new ExtensionPlan.Requirement(
            interfaceType ? ExtensionPlan.RequirementKind.INTERFACE : ExtensionPlan.RequirementKind.EXTENDABLE_CLASS,
            internalName, "", "", false, false));
    }

    private void collectOwnerKind(String internalName, boolean interfaceType, ExtensionPlan.AnalysisResult result) {
        if (!isYsm(internalName)) {
            return;
        }
        result.requirements.add(new ExtensionPlan.Requirement(
            interfaceType ? ExtensionPlan.RequirementKind.INTERFACE : ExtensionPlan.RequirementKind.CLASS_OWNER,
            internalName, "", "", false, false));
    }

    private void collectCompileOwnerKind(String internalName, ExtensionPlan.AnalysisResult result) {
        if (!isYsm(internalName)) {
            return;
        }
        boolean interfaceType =
            repository.find(internalName).map(node -> (node.access & Opcodes.ACC_INTERFACE) != 0).orElse(false);
        collectOwnerKind(internalName, interfaceType, result);
    }

    private void collectType(String nameOrDescriptor, ExtensionPlan.AnalysisResult result) {
        if (nameOrDescriptor == null || nameOrDescriptor.isEmpty()) {
            return;
        }
        String internalName = nameOrDescriptor;
        if (nameOrDescriptor.charAt(0) == '[' || nameOrDescriptor.charAt(0) == 'L') {
            List<String> names = DescriptorUtil.referencedInternalNames(nameOrDescriptor);
            for (String name : names) {
                collectType(name, result);
            }
            return;
        }
        if (isYsm(internalName)) {
            result.requirements.add(ExtensionPlan.Requirement.clazz(internalName));
        }
    }

    private void collectSignature(String signature, boolean typeSignature, ExtensionPlan.AnalysisResult result) {
        if (signature == null) {
            return;
        }
        SignatureVisitor visitor = new SignatureVisitor(Opcodes.ASM9) {
            @Override
            public void visitClassType(String name) {
                collectType(name, result);
            }
        };
        try {
            SignatureReader reader = new SignatureReader(signature);
            if (typeSignature) {
                reader.acceptType(visitor);
            } else {
                reader.accept(visitor);
            }
        } catch (IllegalArgumentException ignored) {
            addIssue(result, "SIGNATURE_UNREADABLE", signature,
                "ASM could not parse a generic signature; descriptor checks still apply.");
        }
    }

    private void collectFrameTypes(List<Object> values, ExtensionPlan.AnalysisResult result) {
        if (values == null) {
            return;
        }
        for (Object value : values) {
            if (value instanceof String internalName) {
                collectType(internalName, result);
            }
        }
    }

    Optional<ResolvedMethod> resolveMethod(String owner, String name, String descriptor) {
        return resolveMethod(owner, name, descriptor, new HashSet<>());
    }

    private Optional<ResolvedMethod> resolveMethod(String owner, String name, String descriptor, Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return Optional.empty();
        }
        Optional<ClassNode> node = repository.find(owner);
        if (node.isEmpty()) {
            return Optional.empty();
        }
        for (MethodNode method : node.get().methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return Optional.of(new ResolvedMethod(node.get(), method));
            }
        }
        Optional<ResolvedMethod> inherited = resolveMethod(node.get().superName, name, descriptor, visited);
        if (inherited.isPresent()) {
            return inherited;
        }
        for (String interfaceName : node.get().interfaces) {
            inherited = resolveMethod(interfaceName, name, descriptor, visited);
            if (inherited.isPresent()) {
                return inherited;
            }
        }
        return Optional.empty();
    }

    Optional<ResolvedField> resolveField(String owner, String name, String descriptor) {
        return resolveField(owner, name, descriptor, new HashSet<>());
    }

    private Optional<ResolvedField> resolveField(String owner, String name, String descriptor, Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return Optional.empty();
        }
        Optional<ClassNode> node = repository.find(owner);
        if (node.isEmpty()) {
            return Optional.empty();
        }
        for (FieldNode field : node.get().fields) {
            if (field.name.equals(name) && field.desc.equals(descriptor)) {
                return Optional.of(new ResolvedField(node.get(), field));
            }
        }
        Optional<ResolvedField> inherited = resolveField(node.get().superName, name, descriptor, visited);
        if (inherited.isPresent()) {
            return inherited;
        }
        for (String interfaceName : node.get().interfaces) {
            inherited = resolveField(interfaceName, name, descriptor, visited);
            if (inherited.isPresent()) {
                return inherited;
            }
        }
        return Optional.empty();
    }

    Optional<ResolvedMethod> findReobfuscatedOverrideRoot(ExtensionPlan.Requirement requirement) {
        if (requirement.kind() != METHOD) {
            return Optional.empty();
        }
        Optional<ResolvedMethod> declaration =
            resolveMethod(requirement.owner(), requirement.name(), requirement.descriptor());
        if (declaration.isEmpty() || !isYsm(declaration.get().owner.name)) {
            return Optional.empty();
        }
        return findReobfuscatedAncestorMethod(declaration.get().owner, requirement.name(), requirement.descriptor(),
            new HashSet<>());
    }

    private Optional<ResolvedMethod> findReobfuscatedAncestorMethod(ClassNode node, String name, String descriptor,
        Set<String> visited) {
        for (String parentName : directParents(node)) {
            if (!visited.add(parentName)) {
                continue;
            }
            Optional<ClassNode> parent = repository.find(parentName);
            if (parent.isEmpty()) {
                continue;
            }
            for (MethodNode method : parent.get().methods) {
                if (method.name.equals(name) && method.desc.equals(descriptor) && requiresReobfProbe(parentName)) {
                    return Optional.of(new ResolvedMethod(parent.get(), method));
                }
            }
            Optional<ResolvedMethod> deeper = findReobfuscatedAncestorMethod(parent.get(), name, descriptor, visited);
            if (deeper.isPresent()) {
                return deeper;
            }
        }
        return Optional.empty();
    }

    private boolean isOwned(String internalName, ExtensionPlan.CheckGroup group) {
        if (internalName == null || isYsm(internalName) || requiresReobfProbe(internalName)
            || internalName.startsWith("java/") || internalName.startsWith("javax/") || internalName.startsWith("jdk/")
            || internalName.startsWith("sun/")) {
            return false;
        }
        if (repository.isCurrentOutput(internalName)) {
            return true;
        }
        String binaryName = DescriptorUtil.binaryName(internalName);
        for (String prefix : group.ownedPackages) {
            if (binaryName.equals(prefix) || binaryName.startsWith(prefix + '.')) {
                return true;
            }
        }
        return false;
    }

    private static boolean isYsm(String internalName) {
        return internalName != null && internalName.startsWith(YSM_PREFIX);
    }

    static boolean requiresReobfProbe(String internalName) {
        return internalName != null && (internalName.startsWith(MINECRAFT_PREFIX)
            || (internalName.startsWith(MOJANG_PREFIX) && !internalName.startsWith(MOJANG_COMMAND_PREFIX)));
    }

    static boolean isClientOnlyPlatformType(String internalName) {
        return internalName != null && (internalName.startsWith("net/minecraft/client/")
            || internalName.startsWith("com/mojang/blaze3d/") || internalName.startsWith("com/mojang/realmsclient/"));
    }

    private static boolean containsMixinAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        return annotations.stream().anyMatch(annotation -> annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/"));
    }

    private static boolean isKnownBootstrap(Handle bootstrap) {
        String identity = bootstrap.getOwner() + '.' + bootstrap.getName();
        return KNOWN_BOOTSTRAPS.contains(identity)
            || bootstrap.getOwner().equals("java/lang/invoke/ConstantBootstraps");
    }

    private static List<String> directParents(ClassNode node) {
        var parents = new ArrayList<String>();
        if (node.superName != null) {
            parents.add(node.superName);
        }
        parents.addAll(node.interfaces);
        return parents;
    }

    private static void addIssue(ExtensionPlan.AnalysisResult result, String kind, String symbol, String detail) {
        var issue = new ExtensionPlan.CoverageIssue(kind, symbol, detail);
        if (!result.issues.contains(issue)) {
            result.issues.add(issue);
        }
    }

    record ResolvedMethod(ClassNode owner, MethodNode method) {
    }

    record ResolvedField(ClassNode owner, FieldNode field) {
    }
}
