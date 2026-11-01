package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.expression.host.builtin.MathBuiltins;
import java.util.ArrayList;
import java.util.Optional;

/** Isolates identity inputs without changing the production declarations or persisted artifact. */
public final class ArtifactContractVariants {
    private ArtifactContractVariants() {}

    public static ExpressionHost changed(ExpressionHost original, String dimension) {
        if (dimension.equals("dependencies"))
            return ExpressionHost.create(HostAvailability.none());
        var declarations = new ArrayList<LinkedDeclaration>();
        for (var declaration : original.declarations()) {
            if (declaration.owner() != MathBuiltins.class) {
                declarations.add(declaration);
                continue;
            }
            var members = new ArrayList<LinkedDeclaration.Member>();
            for (var member : declaration.members()) {
                if (!member.schema().name().equals("abs")) {
                    members.add(member);
                    continue;
                }
                var s = member.schema();
                boolean effect = dimension.equals("effect");
                var schema = new DeclarationSchema.Member(s.namespace(), s.name(), s.javaName(), s.descriptor(),
                    s.adapter(), s.subject(), s.isStatic(), effect ? "" : "floor", effect ? "ACTION" : s.effect(),
                    s.minimum(), s.maximum(), s.fixed(), s.defaults(), s.tail(), s.result(), s.nullableRecord());
                members.add(new LinkedDeclaration.Member(schema, member.subject(), member.binding(), effect
                    ? Optional.empty()
                    : Optional.of(LinkedDeclaration.binding(MathBuiltins.class, "floor", float.class, float.class))));
            }
            var s = declaration.schema();
            var schema = new DeclarationSchema(s.owner(), s.helper(), s.namespace(), s.modId(), s.builtin(),
                s.manual(), s.componentSubject(), s.dependencies(), members.stream().map(LinkedDeclaration.Member::schema).toList());
            declarations.add(new LinkedDeclaration(schema, declaration.verifiedModId(), declaration.owner(),
                declaration.helper(), declaration.loader(), members));
        }
        return ExpressionHost.createFrozen(original.availability(), original.compilationPolicy(), declarations, declarations);
    }
}
