package com.elfmcys.ysm.api.internal.processor;

import com.elfmcys.ysm.api.internal.processor.asm.ClassWriter;
import com.elfmcys.ysm.api.internal.processor.asm.ConstantDynamic;
import com.elfmcys.ysm.api.internal.processor.asm.Handle;
import com.elfmcys.ysm.api.internal.processor.asm.Opcodes;
import com.elfmcys.ysm.api.internal.processor.asm.Type;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityAnalyzerTest {
    @Test
    void recognizesReobfuscatedMojangPackagesButNotBrigadier() {
        assertTrue(CompatibilityAnalyzer.requiresReobfProbe("net/minecraft/world/entity/Entity"));
        assertTrue(CompatibilityAnalyzer.requiresReobfProbe("com/mojang/blaze3d/vertex/PoseStack"));
        assertFalse(CompatibilityAnalyzer.requiresReobfProbe("com/mojang/brigadier/CommandDispatcher"));
    }

    @Test
    void findsMojangOverrideRootButNotBrigadierOverrideRoot() {
        ClassRepository repository = new ClassRepository(null);
        repository.recordGenerated("com/mojang/blaze3d/vertex/PoseStack",
            abstractClass("com/mojang/blaze3d/vertex/PoseStack", "java/lang/Object", "pushPose"));
        repository.recordGenerated("com/elfmcys/ysm/api/MojangBase",
            abstractClass("com/elfmcys/ysm/api/MojangBase", "com/mojang/blaze3d/vertex/PoseStack", "pushPose"));
        repository.recordGenerated("com/mojang/brigadier/CommandBase",
            abstractClass("com/mojang/brigadier/CommandBase", "java/lang/Object", "run"));
        repository.recordGenerated("com/elfmcys/ysm/api/CommandBridge",
            abstractClass("com/elfmcys/ysm/api/CommandBridge", "com/mojang/brigadier/CommandBase", "run"));

        CompatibilityAnalyzer analyzer = new CompatibilityAnalyzer(repository);
        var mojangRoot =
            analyzer.findReobfuscatedOverrideRoot(new ExtensionPlan.Requirement(ExtensionPlan.RequirementKind.METHOD,
                "com/elfmcys/ysm/api/MojangBase", "pushPose", "()V", true, false));
        assertEquals("com/mojang/blaze3d/vertex/PoseStack", mojangRoot.orElseThrow().owner().name);

        var commandRoot =
            analyzer.findReobfuscatedOverrideRoot(new ExtensionPlan.Requirement(ExtensionPlan.RequirementKind.METHOD,
                "com/elfmcys/ysm/api/CommandBridge", "run", "()V", true, false));
        assertTrue(commandRoot.isEmpty());
    }

    @Test
    void scansInvokeDynamicAndNestedConstantDynamicArguments() {
        ClassRepository repository = new ClassRepository(null);
        repository.recordGenerated("sample/Extension", extensionClass());
        repository.recordGenerated("com/elfmcys/ysm/api/Api", ysmApiClass());

        ExtensionPlan plan = new ExtensionPlan("sample.Extension", "sample", "ExtensionCompatibilityChecker");
        ExtensionPlan.CheckGroup group = new ExtensionPlan.CheckGroup("feature", "checkFeature", "COMMON", false);
        group.roots.add(new ExtensionPlan.MethodKey("sample/Extension", "feature", "()V"));

        ExtensionPlan.AnalysisResult result = new CompatibilityAnalyzer(repository).analyze(plan, group);

        assertTrue(result.requirements.stream()
            .anyMatch(requirement -> requirement.kind() == ExtensionPlan.RequirementKind.METHOD
                && requirement.owner().equals("com/elfmcys/ysm/api/Api") && requirement.name().equals("target")));
        assertTrue(result.requirements.stream()
            .anyMatch(requirement -> requirement.kind() == ExtensionPlan.RequirementKind.FIELD
                && requirement.owner().equals("com/elfmcys/ysm/api/Api") && requirement.name().equals("VALUE")));
        assertTrue(result.issues.stream().anyMatch(issue -> issue.kind().equals("DYNAMIC_TARGET_UNKNOWN")));
    }

    private byte[] extensionClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "sample/Extension", null, "java/lang/Object",
            null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "feature", "()V", null, null);
        method.visitCode();

        Handle target = new Handle(Opcodes.H_INVOKESTATIC, "com/elfmcys/ysm/api/Api", "target", "()V", false);
        Handle value = new Handle(Opcodes.H_GETSTATIC, "com/elfmcys/ysm/api/Api", "VALUE", "Ljava/lang/String;", false);
        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "sample/Bootstrap", "bootstrap",
            Type.getMethodDescriptor(Type.getType(CallSite.class), Type.getType(MethodHandles.Lookup.class),
                Type.getType(String.class), Type.getType(MethodType.class), Type.getType(Object.class)),
            false);
        ConstantDynamic constant = new ConstantDynamic("value", "Ljava/lang/String;", bootstrap, value);
        method.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", bootstrap, target, constant);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] ysmApiClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, "com/elfmcys/ysm/api/Api",
            null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "VALUE", "Ljava/lang/String;", null, null)
            .visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "target", "()V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] abstractClass(String name, String superName, String methodName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER, name, null, superName,
            null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, methodName, "()V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
