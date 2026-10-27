package com.elfmcys.ysm.api.internal.processor;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import javax.annotation.processing.Filer;

final class CheckerSourceGenerator {
    private static final String TEMPLATE_RESOURCE = "/META-INF/checker-template.java.txt";

    private CheckerSourceGenerator() {}

    static void generate(Filer filer, ExtensionPlan plan) throws IOException {
        String template;
        try (var input = CheckerSourceGenerator.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing checker source template: " + TEMPLATE_RESOURCE);
            }
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        StringBuilder methods = new StringBuilder();
        for (ExtensionPlan.CheckGroup group : plan.effectiveGroups()) {
            methods.append("    public static Result ").append(group.checkerMethodName).append("() {\n")
                .append("        return RuntimeCheck.check(\"").append(escape(group.key)).append("\");\n")
                .append("    }\n\n");
        }

        String packageDeclaration = plan.packageName.isEmpty() ? "" : "package " + plan.packageName + ";\n";
        String source = template.replace("{{PACKAGE}}", packageDeclaration)
            .replace("{{CHECKER}}", plan.checkerSimpleName).replace("{{RESOURCE}}", escape(plan.manifestPath()))
            .replace("{{METHODS}}", methods.toString().stripTrailing());

        try (Writer writer = filer.createSourceFile(plan.checkerBinaryName()).openWriter()) {
            writer.write(source);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
