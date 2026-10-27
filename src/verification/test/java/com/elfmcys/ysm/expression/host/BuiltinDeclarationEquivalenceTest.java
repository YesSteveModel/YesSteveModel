package com.elfmcys.ysm.expression.host;

import dev.semaweave.SymbolKey;
import dev.semaweave.registry.InvocationKind;
import org.junit.jupiter.api.Test;
import java.util.TreeSet;
import static org.junit.jupiter.api.Assertions.*;

/** Safe metadata is diagnostic input; only the collector's final classes publish symbols. */
final class BuiltinDeclarationEquivalenceTest {
    @Test
    void generatedCatalogContainsLegacySurfaceAndFourteenOrdinaryMathFunctions() {
        var surfaces = BuiltinDeclarations.surfaces();
        assertEquals(125, surfaces.size());
        assertEquals(125, surfaces.stream().filter(s -> !s.isRead()).count());
        assertEquals(0, surfaces.stream().filter(BuiltinSurface::isRead).count());
        var ids = new TreeSet<String>();
        surfaces.forEach(s -> ids.add(s.stableId()));
        assertEquals(123, ids.size());
        for (var symbol : HostSymbol.values())
            assertTrue(ids.contains(symbol.stableId()));
    }

    @Test
    void publishedDescriptorsAndManifestProjectExactCommonBindings() {
        var host = ExpressionHost.create(HostAvailability.all());
        assertEquals(125, host.builtins().size());
        for (var declaration : host.declarations())
            for (var member : declaration.members()) {
                var schema = member.schema();
                var binding = member.binding();
                var entry = host.manifest().stream().filter(e -> e.stableId().equals(schema.symbol()))
                    .filter(e -> e.javaMember().startsWith(binding.toString() + ":")).findFirst().orElseThrow();
                assertEquals(declaration.helper(), binding.owner());
                assertEquals(schema.adapter(), binding.methodName());
                assertEquals(schema.isStatic() ? InvocationKind.STATIC : InvocationKind.INSTANCE,
                    binding.invocationKind());
                assertEquals(!schema.isStatic(), binding.providerId().isPresent());
                assertEquals("Published", entry.availability());
                assertEquals(schema.shape(), entry.callShape());
                assertEquals(schema.minimum(), entry.minimumArguments());
                assertEquals(schema.maximum(), entry.maximumArguments());
                assertEquals(schema.defaults(), entry.defaultArguments());
                assertTrue(entry.javaMember().startsWith(binding.toString() + ":"));
                var key = new SymbolKey(schema.symbol());
                assertTrue(host.registry().variable(key).isEmpty());
                assertEquals(1, host.registry().function(key).orElseThrow().methods().stream()
                    .filter(method -> method.method().equals(binding)).count());
            }
    }

    @Test
    void rejectedClassesPublishNoPlaceholderOrProvider() {
        var host = ExpressionHost.create(HostAvailability.none());
        for (var surface : BuiltinDeclarations.surfaces()) {
            var key = new SymbolKey(surface.stableId());
            boolean present = host.registry().variable(key).isPresent() || host.registry().function(key).isPresent();
            assertEquals(surface.optionalCapability() == null, present, surface.stableId());
        }
        assertTrue(host.manifest().stream().allMatch(ExpressionHost.ManifestEntry::available));
        assertTrue(host.declarations().stream().allMatch(d -> d.schema().dependencies().isEmpty()));
    }
}
