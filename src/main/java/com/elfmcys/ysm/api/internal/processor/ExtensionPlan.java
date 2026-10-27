package com.elfmcys.ysm.api.internal.processor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ExtensionPlan {
    final String ownerBinaryName;
    final String packageName;
    final String checkerSimpleName;
    final Map<String, CheckGroup> methodGroups = new LinkedHashMap<>();
    CheckGroup classGroup;

    ExtensionPlan(String ownerBinaryName, String packageName, String checkerSimpleName) {
        this.ownerBinaryName = ownerBinaryName;
        this.packageName = packageName;
        this.checkerSimpleName = checkerSimpleName;
    }

    String ownerInternalName() {
        return DescriptorUtil.internalName(ownerBinaryName);
    }

    String checkerBinaryName() {
        return packageName.isEmpty() ? checkerSimpleName : packageName + '.' + checkerSimpleName;
    }

    String manifestPath() {
        return "META-INF/ysm-compat/" + ownerBinaryName.replace('.', '/') + ".json";
    }

    Collection<CheckGroup> effectiveGroups() {
        return classGroup == null ? methodGroups.values() : List.of(classGroup);
    }

    static final class CheckGroup {
        final String key;
        final String checkerMethodName;
        final String side;
        final boolean wholeClass;
        final Set<MethodKey> roots = new LinkedHashSet<>();
        final Set<String> ownedPackages = new LinkedHashSet<>();

        CheckGroup(String key, String checkerMethodName, String side, boolean wholeClass) {
            this.key = key;
            this.checkerMethodName = checkerMethodName;
            this.side = side;
            this.wholeClass = wholeClass;
        }
    }

    record MethodKey(String owner, String name, String descriptor) {
        String display() {
            return DescriptorUtil.binaryName(owner) + '#' + name + descriptor;
        }
    }

    enum RequirementKind {
        CLASS, CLASS_OWNER, EXTENDABLE_CLASS, INTERFACE, INSTANTIABLE_CLASS, METHOD, FIELD, CONSTRUCTOR
    }

    record Requirement(RequirementKind kind, String owner, String name, String descriptor, boolean declaredOnly,
        boolean staticMember) {
        static Requirement clazz(String internalName) {
            return new Requirement(RequirementKind.CLASS, internalName, "", "", false, false);
        }

        String display() {
            return switch (kind) {
                case CLASS, CLASS_OWNER, EXTENDABLE_CLASS, INTERFACE, INSTANTIABLE_CLASS ->
                    DescriptorUtil.binaryName(owner);
                case METHOD, CONSTRUCTOR -> DescriptorUtil.binaryName(owner) + '#' + name + descriptor;
                case FIELD -> DescriptorUtil.binaryName(owner) + '#' + name + ':' + descriptor;
            };
        }
    }

    record CoverageIssue(String kind, String symbol, String detail) {
    }

    static final class AnalysisResult {
        final Set<Requirement> requirements = new LinkedHashSet<>();
        final List<CoverageIssue> issues = new ArrayList<>();
    }
}
