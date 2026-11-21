package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import java.util.ArrayList;

/** Isolates identity inputs without changing the production declarations or persisted artifact. */
public final class ArtifactContractVariants {
    private ArtifactContractVariants() {}

    public static float replacement(MolangContext context) {
        return 0;
    }

    public static ExpressionHost changed(ExpressionHost original, String dimension) {
        if (dimension.equals("dependencies"))
            return ExpressionHost.create(HostAvailability.none());
        boolean changed = false;
        var declarations = new ArrayList<LinkedDeclaration>();
        for (var declaration : original.declarations()) {
            var members = new ArrayList<LinkedDeclaration.Member>();
            for (var member : declaration.members()) {
                var s = member.schema();
                if (changed || !s.isStatic() || !s.injectContext() || !s.shape().equals("FIXED") || s.maximum() != 0
                    || !s.result().carrier().equals("float")) {
                    members.add(member);
                    continue;
                }
                changed = true;
                boolean effect = dimension.equals("effect");
                var schema =
                    new DeclarationSchema.Member(s.namespace(), s.name(), s.javaName(), s.descriptor(), s.adapter(),
                        s.subject(), s.isStatic(), s.injectContext(), effect ? "ACTION" : s.effect(), s.scope(),
                        s.minimum(), s.maximum(), s.fixed(), s.defaults(), s.tail(), s.result(), s.nullableReturn());
                members.add(
                    new LinkedDeclaration.Member(schema, member.subject(), effect ? member.binding() : LinkedDeclaration
                        .binding(ArtifactContractVariants.class, "replacement", float.class, MolangContext.class)));
            }
            var s = declaration.schema();
            var schema = new DeclarationSchema(s.owner(), s.helper(), s.namespace(), s.modId(), s.builtin(), s.manual(),
                s.componentSubject(), s.dependencies(),
                members.stream().map(LinkedDeclaration.Member::schema).toList());
            declarations.add(new LinkedDeclaration(schema, declaration.verifiedModId(), declaration.owner(),
                declaration.helper(), declaration.loader(), members));
        }
        if (!changed)
            throw new AssertionError("missing identity fixture declaration");
        return ExpressionHost.createFrozen(original.availability(), original.compilationPolicy(), declarations,
            declarations);
    }
}
