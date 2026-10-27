package com.elfmcys.ysm.expression.host;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Safe build-time logical surface; it contains no class literal from an optional group. */
public record BuiltinSurface(String stableId, String sourceSpelling, HostSymbol.AccessKind accessKind,
    dev.semaweave.ValueType resultType, int minimumArguments, int maximumArguments,
    List<dev.semaweave.ValueType> fixedArguments, Optional<dev.semaweave.ValueType> variadicElement,
    OptionalCapability optionalCapability, Set<EffectKind> effects, List<String> defaultRecipes, String enumConstant,
    String declaringOwner, String bindingMethod, String entityBound, boolean staged) {
    public BuiltinSurface {
        Objects.requireNonNull(stableId, "stableId");
        Objects.requireNonNull(sourceSpelling, "sourceSpelling");
        Objects.requireNonNull(accessKind, "accessKind");
        Objects.requireNonNull(resultType, "resultType");
        fixedArguments = List.copyOf(fixedArguments);
        variadicElement = Objects.requireNonNull(variadicElement, "variadicElement");
        effects = Set.copyOf(effects);
        defaultRecipes = List.copyOf(defaultRecipes);
        Objects.requireNonNull(enumConstant, "enumConstant");
        Objects.requireNonNull(declaringOwner, "declaringOwner");
        Objects.requireNonNull(bindingMethod, "bindingMethod");
        Objects.requireNonNull(entityBound, "entityBound");
        if (minimumArguments < 0 || maximumArguments < minimumArguments || maximumArguments > 255) {
            throw new IllegalArgumentException("invalid built-in arity " + stableId);
        }
    }

    public boolean isRead() {
        return accessKind == HostSymbol.AccessKind.READ;
    }

    public boolean variadic() {
        return variadicElement.isPresent();
    }

    public boolean optionalArguments() {
        return !variadic() && minimumArguments != maximumArguments;
    }
}
