package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.api.molang.MolangEffect;
import java.util.List;
import java.util.Optional;

/** Derived declaration facts. A candidate surface alone does not confer publication. */
public record BuiltinSurface(String stableId, String sourceSpelling, dev.semaweave.ValueType resultType,
    int minimumArguments, int maximumArguments, List<dev.semaweave.ValueType> fixedArguments,
    Optional<dev.semaweave.ValueType> variadicElement, List<DeclarationSchema.Dependency> dependencies,
    MolangEffect effect, List<String> defaultRecipes, String declaringOwner, String bindingMethod, String entityBound) {
    public BuiltinSurface {
        fixedArguments = List.copyOf(fixedArguments);
        dependencies = List.copyOf(dependencies);
        defaultRecipes = List.copyOf(defaultRecipes);
    }
    public boolean isRead() { return effect == MolangEffect.CONTEXT; }
    public boolean variadic() { return variadicElement.isPresent(); }
    public boolean optionalArguments() { return !variadic() && minimumArguments != maximumArguments; }
}
