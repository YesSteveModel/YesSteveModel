package com.elfmcys.ysm.api.internal.processor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.annotation.processing.Filer;
import javax.tools.StandardLocation;

final class ManifestWriter {
    static final int VERSION = 1;

    private ManifestWriter() {}

    // 这里没有 GSON
    static void write(Filer filer, ExtensionPlan plan, PreparedSpec spec) throws IOException {
        var resource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", plan.manifestPath());
        try (var output =
            new BufferedWriter(new OutputStreamWriter(resource.openOutputStream(), StandardCharsets.UTF_8))) {
            output.write("{\n  \"version\": ");
            output.write(Integer.toString(VERSION));
            output.write(",\n  \"canaryProbe\": ");
            writeString(output, spec.canaryProbe());
            output.write(",\n  \"groups\": {");

            boolean firstGroup = true;
            for (var entry : spec.groups().entrySet()) {
                if (!firstGroup) {
                    output.write(',');
                }
                firstGroup = false;
                output.write("\n    ");
                writeString(output, entry.getKey());
                output.write(": {\n      \"side\": ");
                PreparedSpec.PreparedGroup group = entry.getValue();
                writeString(output, group.side());
                output.write(",\n      \"issues\": [");

                boolean firstIssue = true;
                for (ExtensionPlan.CoverageIssue issue : group.issues()) {
                    if (!firstIssue) {
                        output.write(',');
                    }
                    firstIssue = false;
                    output.write("\n        {\"kind\": ");
                    writeString(output, issue.kind());
                    output.write(", \"symbol\": ");
                    writeString(output, issue.symbol());
                    output.write(", \"detail\": ");
                    writeString(output, issue.detail());
                    output.write('}');
                }
                if (!group.issues().isEmpty()) {
                    output.write('\n');
                    output.write("      ");
                }
                output.write("],\n      \"requirements\": [");

                boolean firstRequirement = true;
                for (PreparedSpec.PreparedRequirement requirement : group.requirements()) {
                    if (!firstRequirement) {
                        output.write(',');
                    }
                    firstRequirement = false;
                    output.write("\n        {\n          \"kind\": ");
                    writeString(output, requirement.kind().name());
                    output.write(",\n          \"owner\": ");
                    writeString(output, requirement.ownerBinaryName());
                    output.write(",\n          \"name\": ");
                    writeString(output, requirement.name());
                    output.write(",\n          \"descriptor\": ");
                    writeString(output, requirement.displayDescriptor());
                    output.write(",\n          \"declaredOnly\": ");
                    output.write(Boolean.toString(requirement.declaredOnly()));
                    output.write(",\n          \"staticMember\": ");
                    output.write(Boolean.toString(requirement.staticMember()));
                    output.write(",\n          \"nameProbe\": ");
                    writeString(output, requirement.mappedNameProbe());
                    output.write(",\n          \"fieldType\": ");
                    writeString(output, requirement.fieldType());
                    output.write(",\n          \"returnType\": ");
                    writeString(output, requirement.returnType());
                    output.write(",\n          \"parameterTypes\": [");
                    boolean firstParameter = true;
                    for (String parameterType : requirement.parameterTypes()) {
                        if (!firstParameter) {
                            output.write(", ");
                        }
                        firstParameter = false;
                        writeString(output, parameterType);
                    }
                    output.write("]\n        }");
                }
                if (!group.requirements().isEmpty()) {
                    output.write('\n');
                    output.write("      ");
                }
                output.write("]\n    }");
            }
            if (!spec.groups().isEmpty()) {
                output.write('\n');
                output.write("  ");
            }
            output.write("}\n}\n");
        }
    }

    private static void writeString(Writer output, String value) throws IOException {
        output.write('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.write("\\\"");
                case '\\' -> output.write("\\\\");
                case '\b' -> output.write("\\b");
                case '\f' -> output.write("\\f");
                case '\n' -> output.write("\\n");
                case '\r' -> output.write("\\r");
                case '\t' -> output.write("\\t");
                default -> {
                    if (character < 0x20) {
                        output.write("\\u");
                        output.write(String.format(Locale.ROOT, "%04x", (int)character));
                    } else {
                        output.write(character);
                    }
                }
            }
        }
        output.write('"');
    }
}
