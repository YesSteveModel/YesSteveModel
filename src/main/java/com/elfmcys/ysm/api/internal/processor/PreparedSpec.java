package com.elfmcys.ysm.api.internal.processor;

import java.util.List;
import java.util.Map;

record PreparedSpec(String canaryProbe, Map<String, PreparedGroup> groups) {
    record PreparedGroup(String side, List<PreparedRequirement> requirements,
        List<ExtensionPlan.CoverageIssue> issues) {
    }

    record PreparedRequirement(ExtensionPlan.RequirementKind kind, String ownerBinaryName, String name,
        String displayDescriptor, boolean declaredOnly, boolean staticMember, String mappedNameProbe, String fieldType,
        String returnType, List<String> parameterTypes) {
    }
}
