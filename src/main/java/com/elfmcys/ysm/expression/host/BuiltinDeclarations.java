package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import java.io.*;
import java.util.*;

/** Safe command metadata. Only the collector's linked output can publish language symbols. */
public final class BuiltinDeclarations {
    private BuiltinDeclarations() {}

    private static final List<DeclarationSchema> SCHEMAS = readSchemas();
    private static final List<BuiltinSurface> SURFACES =
        SCHEMAS.stream().flatMap(s -> s.members().stream().map(m -> surface(s, m)))
            .sorted(Comparator.comparing(BuiltinSurface::stableId)).toList();
    private static final Map<String, Group> BY_SYMBOL =
        SURFACES.stream().collect(java.util.stream.Collectors.groupingBy(BuiltinSurface::stableId,
            java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), Group::new)));

    static List<DeclarationSchema> schemas() {
        return SCHEMAS;
    }

    public static MolangExtensions.Source source() {
        return new MolangExtensions.Source("ysm", Set.of("ysm"),
            SCHEMAS.stream().map(s -> new MolangExtensions.DeclarationRef(s.owner(), s.namespace(), "")).toList(),
            BuiltinDeclarations.class.getClassLoader());
    }

    private static List<DeclarationSchema> readSchemas() {
        var loader = BuiltinDeclarations.class.getClassLoader();
        var schemas = new ArrayList<DeclarationSchema>();
        try (var stream = loader.getResourceAsStream(DeclarationSchema.RESOURCE_ROOT + "builtin.index")) {
            if (stream == null)
                throw new IllegalStateException("missing builtin safe index");
            var reader = new BufferedReader(new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            for (String name; (name = reader.readLine()) != null;) {
                if (name.isBlank())
                    continue;
                try (var input = loader.getResourceAsStream(name)) {
                    if (input == null)
                        throw new IOException("missing declaration " + name);
                    schemas.add(DeclarationSchema.decode(input));
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("invalid builtin declaration resources", failure);
        }
        return List.copyOf(schemas);
    }

    public static List<BuiltinSurface> surfaces() {
        return SURFACES;
    }

    public static void validateDefaultSource(com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceLocation location,
        com.elfmcys.ysm.expression.frontend.MolangFrontend.Result result) {
        for (var symbol : result.referencedSymbols())
            if (symbol.domain() == dev.semaweave.program.ProgramNode.SymbolDomain.EXTERNAL
                && !BY_SYMBOL.containsKey(symbol.name()))
                throw new IllegalArgumentException("Intrinsic default source requires an unpublished builtin: "
                    + location + " symbol=" + symbol.name());
    }

    public static Group group(String symbol) {
        return BY_SYMBOL.get(symbol);
    }

    public static BuiltinSurface requireSurface(String symbol) {
        var group = Objects.requireNonNull(BY_SYMBOL.get(symbol), "unknown builtin " + symbol);
        if (group.methods().size() != 1)
            throw new IllegalArgumentException("exact method required for " + symbol);
        return group.methods().get(0);
    }

    public record Group(List<BuiltinSurface> methods) {
        public Group {
            methods = List.copyOf(methods);
            if (methods.isEmpty())
                throw new IllegalArgumentException("empty builtin group");
        }

        // Common facts have already been validated by DeclarationSchema; no method is selected here.
        public HostSymbol.AccessKind accessKind() {
            return methods.get(0).accessKind();
        }

        public dev.semaweave.ValueType resultType() {
            return methods.get(0).resultType();
        }

        public int minimumArguments() {
            return methods.stream().mapToInt(BuiltinSurface::minimumArguments).min().orElseThrow();
        }

        public int maximumArguments() {
            return methods.stream().mapToInt(BuiltinSurface::maximumArguments).max().orElseThrow();
        }

        public OptionalCapability optionalCapability() {
            return methods.get(0).optionalCapability();
        }

        public Set<EffectKind> effects() {
            return methods.get(0).effects();
        }

        public boolean staged() {
            return methods.get(0).staged();
        }
    }

    public static ComponentScope createComponents(com.elfmcys.ysm.client.entity.AnimatableEntity<?> owner,
        ExpressionHost host) {
        return new DeclarationComponentScope(owner, host.declarations());
    }

    static BuiltinSurface surface(DeclarationSchema owner, DeclarationSchema.Member m) {
        OptionalCapability capability =
            owner.dependencies().isEmpty() ? null : Arrays.stream(OptionalCapability.values())
                .filter(c -> c.modId().equals(owner.dependencies().get(0).modId())).findFirst().orElse(null);
        Set<EffectKind> effects = Set.of(EffectKind.CONTEXT_STATE);
        if (m.effect().equals("ACTION")) {
            var id = m.symbol();
            effects = Set.of(EffectKind.CONTEXT_STATE,
                id.contains("sound") ? EffectKind.SOUND
                    : id.contains("particle") ? EffectKind.PARTICLE : id.endsWith(":sync") ? EffectKind.SYNC
                        : id.endsWith(":defer") ? EffectKind.DEFER : EffectKind.CONTROLLER);
        }
        return new BuiltinSurface(m.symbol(), m.symbol(), HostSymbol.AccessKind.INVOKE,
            LinkedDeclaration.type(m.result()), m.minimum(), m.maximum(),
            m.fixed().stream().map(LinkedDeclaration::type).toList(),
            Optional.ofNullable(m.tail()).map(LinkedDeclaration::type), capability, effects, m.defaults(), "",
            owner.owner(), m.adapter(), m.subject(), m.effect().equals("ACTION"));
    }
}
