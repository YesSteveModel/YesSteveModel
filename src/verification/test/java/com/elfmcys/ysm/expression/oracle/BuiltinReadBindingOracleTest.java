package com.elfmcys.ysm.expression.oracle;

import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import dev.semaweave.SymbolKey;
import dev.semaweave.registry.InvocationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuiltinReadBindingOracleTest {
    private static final BuiltinCapabilityOracle ORACLE = BuiltinCapabilityOracle.load();

    @Test
    void everyHostBareAccessResolvesToItsGeneratedFunctionBinding() {
        var host = ExpressionHost.create(HostAvailability.all());
        for (var capability : ORACLE.readCapabilities()) {
            if ("roaming".equals(capability.sourceSpelling())) {
                continue;
            }
            var binding =
                host.registry().function(new SymbolKey(capability.stableId())).orElseThrow().methods().get(0).method();
            var descriptor = host.builtins().stream().filter(value -> value.stableId().equals(capability.stableId()))
                .findFirst().orElseThrow();
            assertTrue(descriptor.available(), capability.label());
            assertEquals(
                descriptor.surface().declaringOwner().endsWith("PositionComponent")
                    || descriptor.surface().declaringOwner().endsWith("ImmersiveMelodiesBuiltinComponent")
                        ? InvocationKind.INSTANCE : InvocationKind.STATIC,
                binding.invocationKind(), capability.label());
            assertEquals(descriptor.binding(), binding, capability.label());
            assertEquals(descriptor.componentClass(), binding.owner(), capability.label());
            assertEquals(binding.invocationKind() == InvocationKind.INSTANCE, binding.providerId().isPresent(),
                capability.label());
        }
    }

    @Test
    void unavailableOptionalReadsKeepTheirTypedMissingSurfaceWithoutCallingBusinessCode() {
        var host = ExpressionHost.create(HostAvailability.none());
        for (var capability : ORACLE.optionalCapabilities()) {
            if (!"READ".equals(capability.access())) {
                continue;
            }
            assertTrue(host.registry().function(new SymbolKey(capability.stableId())).isEmpty(), capability.label());
        }
    }

}
