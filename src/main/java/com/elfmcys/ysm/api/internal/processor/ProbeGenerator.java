package com.elfmcys.ysm.api.internal.processor;

import com.elfmcys.ysm.api.internal.processor.asm.ClassWriter;
import com.elfmcys.ysm.api.internal.processor.asm.Opcodes;
import com.elfmcys.ysm.api.internal.processor.asm.Type;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Filer;
import javax.tools.StandardLocation;

final class ProbeGenerator {
    private static final String CANARY_OWNER = "net/minecraft/world/entity/Entity";
    private static final String CANARY_NAME = "tick";
    private static final String CANARY_DESCRIPTOR = "()V";

    private final Filer filer;
    private final CompatibilityAnalyzer analyzer;
    private final Set<String> emittedClassNames = new LinkedHashSet<>();

    ProbeGenerator(Filer filer, CompatibilityAnalyzer analyzer) {
        this.filer = filer;
        this.analyzer = analyzer;
    }

    PreparedSpec prepare(ExtensionPlan plan, Map<String, ExtensionPlan.AnalysisResult> analysisByGroup)
        throws IOException {
        String probePackage = plan.packageName.isEmpty() ? "__ysmcompat" : plan.packageName + ".__ysmcompat";
        String prefix = probePackage + '.' + plan.checkerSimpleName;

        String canaryProbe = emitCanary(prefix, analysisByGroup);
        Map<String, PreparedSpec.PreparedGroup> groups = new LinkedHashMap<>();
        for (ExtensionPlan.CheckGroup group : plan.effectiveGroups()) {
            ExtensionPlan.AnalysisResult analysis = analysisByGroup.get(group.key);
            groups.put(group.key, prepareGroup(prefix, group, analysis));
        }
        return new PreparedSpec(canaryProbe, groups);
    }

    private PreparedSpec.PreparedGroup prepareGroup(String prefix, ExtensionPlan.CheckGroup group,
        ExtensionPlan.AnalysisResult analysis) throws IOException {
        List<ExtensionPlan.Requirement> requirements =
            analysis.requirements.stream().sorted(Comparator.comparing(ExtensionPlan.Requirement::display)
                .thenComparing(requirement -> requirement.kind().name())).toList();

        Set<String> reobfuscatedTypes = new LinkedHashSet<>();
        for (ExtensionPlan.Requirement requirement : requirements) {
            if (!requirement.descriptor().isEmpty()) {
                for (String internalName : DescriptorUtil.referencedInternalNames(requirement.descriptor())) {
                    if (CompatibilityAnalyzer.requiresReobfProbe(internalName)) {
                        reobfuscatedTypes.add(internalName);
                    }
                }
            }
        }

        Map<String, TypeProbeRef> typeProbes = emitTypeProbe(prefix, group, reobfuscatedTypes);
        List<PreparedSpec.PreparedRequirement> prepared = new ArrayList<>();
        for (ExtensionPlan.Requirement requirement : requirements) {
            String mappedNameProbe = "";
            if (requirement.kind() == ExtensionPlan.RequirementKind.METHOD) {
                var overrideRoot = analyzer.findReobfuscatedOverrideRoot(requirement);
                if (overrideRoot.isPresent()) {
                    mappedNameProbe = emitMethodProbe(prefix, group, requirement, overrideRoot.get());
                }
            }

            String fieldType = "";
            String returnType = "";
            List<String> parameterTypes = List.of();
            if (requirement.kind() == ExtensionPlan.RequirementKind.FIELD) {
                fieldType = typeToken(Type.getType(requirement.descriptor()), typeProbes);
            } else if (requirement.kind() == ExtensionPlan.RequirementKind.METHOD
                || requirement.kind() == ExtensionPlan.RequirementKind.CONSTRUCTOR) {
                Type methodType = Type.getMethodType(requirement.descriptor());
                returnType = typeToken(methodType.getReturnType(), typeProbes);
                parameterTypes = new ArrayList<>();
                for (Type argument : methodType.getArgumentTypes()) {
                    parameterTypes.add(typeToken(argument, typeProbes));
                }
                parameterTypes = List.copyOf(parameterTypes);
            }
            prepared.add(
                new PreparedSpec.PreparedRequirement(requirement.kind(), DescriptorUtil.binaryName(requirement.owner()),
                    requirement.name(), requirement.descriptor(), requirement.declaredOnly(),
                    requirement.staticMember(), mappedNameProbe, fieldType, returnType, parameterTypes));
        }

        if (!group.side.equals("CLIENT")) {
            for (String reobfuscatedType : reobfuscatedTypes) {
                if (CompatibilityAnalyzer.isClientOnlyPlatformType(reobfuscatedType)) {
                    analysis.issues.add(
                        new ExtensionPlan.CoverageIssue("SIDE_MISMATCH", DescriptorUtil.binaryName(reobfuscatedType),
                            "A client-only Minecraft/Mojang type is used by a " + group.side + " check."));
                }
            }
        }
        return new PreparedSpec.PreparedGroup(group.side, prepared, List.copyOf(analysis.issues));
    }

    private Map<String, TypeProbeRef> emitTypeProbe(String prefix, ExtensionPlan.CheckGroup group,
        Set<String> reobfuscatedTypes) throws IOException {
        if (reobfuscatedTypes.isEmpty()) {
            return Map.of();
        }
        String binaryName = prefix + "__TypeProbe_" + shortHash(group.key);
        String internalName = DescriptorUtil.internalName(binaryName);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
            internalName, null, "java/lang/Object", null);

        Map<String, TypeProbeRef> result = new LinkedHashMap<>();
        int index = 0;
        for (String reobfuscatedType : reobfuscatedTypes.stream().sorted().toList()) {
            String fieldName = "t" + index++;
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, fieldName,
                'L' + reobfuscatedType + ';', null, null).visitEnd();
            result.put(reobfuscatedType, new TypeProbeRef(binaryName, fieldName));
        }
        writer.visitEnd();
        writeClass(binaryName, writer.toByteArray());
        return result;
    }

    private String emitMethodProbe(String prefix, ExtensionPlan.CheckGroup group, ExtensionPlan.Requirement requirement,
        CompatibilityAnalyzer.ResolvedMethod root) throws IOException {
        String identity =
            group.key + ':' + root.owner().name + ':' + requirement.name() + ':' + requirement.descriptor();
        String binaryName = prefix + "__MethodProbe_" + shortHash(identity);
        String internalName = DescriptorUtil.internalName(binaryName);
        boolean rootIsInterface = (root.owner().access & Opcodes.ACC_INTERFACE) != 0;

        ClassWriter writer = new ClassWriter(0);
        if (rootIsInterface) {
            writer.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_SYNTHETIC, internalName,
                null, "java/lang/Object", new String[] {root.owner().name});
        } else {
            writer.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC, internalName,
                null, root.owner().name, null);
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, requirement.name(), requirement.descriptor(),
            null, null).visitEnd();
        writer.visitEnd();
        writeClass(binaryName, writer.toByteArray());
        return binaryName;
    }

    private String emitCanary(String prefix, Map<String, ExtensionPlan.AnalysisResult> analysisByGroup)
        throws IOException {
        var canary = analyzer.resolveMethod(CANARY_OWNER, CANARY_NAME, CANARY_DESCRIPTOR);
        if (canary.isEmpty()) {
            for (ExtensionPlan.AnalysisResult analysis : analysisByGroup.values()) {
                analysis.issues.add(new ExtensionPlan.CoverageIssue("REOBF_CANARY_UNAVAILABLE",
                    DescriptorUtil.binaryName(CANARY_OWNER) + '#' + CANARY_NAME + CANARY_DESCRIPTOR,
                    "The standard Forge reobf canary could not be generated."));
            }
            return "";
        }

        String binaryName = prefix + "__ReobfCanary_" + shortHash(prefix);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
            DescriptorUtil.internalName(binaryName), null, CANARY_OWNER, null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, CANARY_NAME, CANARY_DESCRIPTOR, null, null)
            .visitEnd();
        writer.visitEnd();
        writeClass(binaryName, writer.toByteArray());
        return binaryName;
    }

    private String typeToken(Type type, Map<String, TypeProbeRef> probes) {
        int dimensions = 0;
        while (type.getSort() == Type.ARRAY) {
            dimensions++;
            type = type.getElementType();
        }
        StringBuilder token = new StringBuilder("[".repeat(dimensions));
        if (type.getSort() == Type.OBJECT) {
            TypeProbeRef probe = probes.get(type.getInternalName());
            if (probe == null) {
                token.append('L').append(type.getClassName());
            } else {
                token.append('P').append(probe.className).append('#').append(probe.fieldName);
            }
        } else {
            token.append(type.getDescriptor());
        }
        return token.toString();
    }

    private void writeClass(String binaryName, byte[] bytes) throws IOException {
        if (!emittedClassNames.add(binaryName)) {
            return;
        }
        try (OutputStream output =
            filer.createResource(StandardLocation.CLASS_OUTPUT, "", DescriptorUtil.internalName(binaryName) + ".class")
                .openOutputStream()) {
            output.write(bytes);
        }
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private record TypeProbeRef(String className, String fieldName) {
    }
}
