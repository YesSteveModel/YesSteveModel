package com.elfmcys.ysm.expression.oracle;

import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.frontend.MolangFrontendException;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import dev.semaweave.BuiltinType;
import dev.semaweave.SymbolKey;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.ProgramNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks production registries and frontend behavior against independently authored expectations. */
final class BuiltinCapabilityOracleTest {
    private static final BuiltinCapabilityOracle ORACLE = BuiltinCapabilityOracle.load();
    private static final MolangFrontend.SourceLocation LOCATION =
        new MolangFrontend.SourceLocation("builtin-oracle/capability");

    @Test
    void independentExpectationsHaveUniqueSymbolsAndCompleteTypedCoverage() {
        assertEquals(ORACLE.capabilities().size(),
            ORACLE.capabilities().stream().map(BuiltinCapabilityOracle.Capability::sourceSpelling).distinct().count());
        assertEquals(ORACLE.capabilities().size(),
            ORACLE.capabilities().stream().map(BuiltinCapabilityOracle.Capability::stableId).distinct().count());
        assertEquals(BuiltinStaticOracle.sources(),
            ORACLE.capabilities().stream().filter(capability -> !"roaming".equals(capability.sourceSpelling()))
                .map(BuiltinCapabilityOracle.Capability::sourceSpelling)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    @Test
    void fullAvailabilityRegistryAndManifestMatchIndependentExpectations() {
        var host = ExpressionHost.create(HostAvailability.all());
        var actualFunctions = host.registry().functions().keySet().stream().map(SymbolKey::canonical)
            .collect(java.util.stream.Collectors.toSet());
        var actualVariables = host.registry().variables().keySet().stream().map(SymbolKey::canonical)
            .collect(java.util.stream.Collectors.toSet());

        var expectedFunctions =
            capabilitiesWithRegistryEntry().stream().map(BuiltinCapabilityOracle.Capability::stableId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        mathIds().forEach(expectedFunctions::add);

        assertEquals(expectedFunctions, actualFunctions, "registered function set");
        assertEquals(Set.of(), actualVariables, "declared built-ins are functions");
        assertFalse(actualVariables.contains(ORACLE.require("roaming").stableId()),
            "roaming belongs to instance state, outside the host registry");

        for (var capability : capabilitiesWithRegistryEntry()) {
            var entry = manifestEntry(host, capability);
            assertTrue(entry.available(), capability.label());
            assertEquals("INVOKE", entry.accessKind(), capability.label());
            assertEquals(capability.minimum(), entry.minimumArguments(), capability.label());
            assertEquals(capability.maximum(), entry.maximumArguments(), capability.label());
            assertEquals("READ".equals(capability.access()) ? "FIXED" : expectedShape(capability), entry.callShape(),
                capability.label());
            assertEquals(expectedResultStableId(capability), entry.resultType(), capability.label());
            var signature =
                "READ".equals(capability.access()) ? null : BuiltinStaticOracle.signature(capability.sourceSpelling());
            assertEquals(signature == null ? 0 : signature.defaultCount(), entry.defaultArguments().size(),
                capability.label());
            assertEquals(expectedArgumentTypes(capability), entry.argumentTypes(), capability.label());
            assertEquals(signature != null && signature.hasVariadic() ? expectedTypeStableId(signature.variadic()) : "",
                entry.variadicElementType(), capability.label());
            assertEquals(
                capability.staged() ? List.of("ysm:action", "ysm:context-state") : List.of("ysm:context-state"),
                entry.effects(), capability.label());
            assertEquals("Published", entry.availability(), capability.label());
            assertFalse(entry.javaMember().isEmpty(), capability.label());
        }
    }

    @Test
    void absentAndSingleFamilyAvailabilityRemoveExactlyTheOptionalFamilies() {
        var empty = ExpressionHost.create(HostAvailability.none());
        var coreIds = new TreeSet<>(expectedRegistryIds());
        var optionalIds = new TreeSet<>(
            ORACLE.optionalCapabilities().stream().map(BuiltinCapabilityOracle.Capability::stableId).toList());

        coreIds.removeAll(optionalIds);
        coreIds.addAll(mathIds());
        assertEquals(coreIds, registeredCapabilityIds(empty), "absent configuration keeps the complete typed surface");
        assertTrue(empty.manifest().stream().noneMatch(e -> optionalIds.contains(e.stableId())));

        for (var family : ORACLE.families()) {
            var host = ExpressionHost.create(HostAvailability.detect(loadedMod -> loadedMod.equals(family.modId())));
            var expected = new TreeSet<>(coreIds);
            family.expectedKeys().forEach(key -> expected.add(ORACLE.require(key).stableId()));
            assertEquals(expected, registeredCapabilityIds(host), family.family());

            for (var other : ORACLE.families()) {
                if (other.family().equals(family.family())) {
                    continue;
                }
                for (var key : other.expectedKeys()) {
                    var stableId = ORACLE.require(key).stableId();
                    assertTrue(host.manifest().stream().noneMatch(e -> e.stableId().equals(stableId)),
                        family.family() + " must not enable " + stableId);
                }
            }
            for (var key : family.expectedKeys()) {
                var entry = manifestEntry(host, ORACLE.require(key));
                assertTrue(entry.available(), family.family() + ' ' + key);
            }
        }
    }

    @Test
    void qAndQueryAreSynonymsAndRoamingNeverBecomesAHostSymbol() throws Exception {
        var frontend = new MolangFrontend();
        for (var pair : List.of(List.of("q.anim_time", "query.anim_time"),
            List.of("q.position(0)", "query.position(0)"))) {
            var short_ = frontend.compile(pair.get(0), MolangFrontend.SourceMode.EXPRESSION, LOCATION);
            var long_ = frontend.compile(pair.get(1), MolangFrontend.SourceMode.EXPRESSION, LOCATION);
            org.junit.jupiter.api.Assertions.assertArrayEquals(short_.program().payload().toByteArray(),
                long_.program().payload().toByteArray(), pair.toString());
            assertEquals(short_.referencedSymbols(), long_.referencedSymbols(), pair.toString());
        }

        var canonical = frontend.compile("roaming.x", MolangFrontend.SourceMode.EXPRESSION, LOCATION);
        for (var source : List.of("variable.roaming.x", "v.roaming.x", "r.x")) {
            var other = frontend.compile(source, MolangFrontend.SourceMode.EXPRESSION, LOCATION);
            org.junit.jupiter.api.Assertions.assertArrayEquals(canonical.program().payload().toByteArray(),
                other.program().payload().toByteArray(), source);
        }
        assertTrue(
            canonical.referencedSymbols().stream()
                .noneMatch(reference -> reference.domain() == ProgramNode.SymbolDomain.EXTERNAL),
            "roaming belongs to instance state, outside the host registry");
        assertTrue(
            canonical.referencedSymbols().stream()
                .anyMatch(reference -> reference.domain() == ProgramNode.SymbolDomain.INSTANCE),
            "roaming belongs to instance state, outside the host registry");

        var host = ExpressionHost.create(HostAvailability.all());
        assertFalse(host.registry().variable(new SymbolKey(ORACLE.require("roaming").stableId())).isPresent());
        assertTrue(
            host.manifest().stream().noneMatch(entry -> entry.stableId().equals(ORACLE.require("roaming").stableId())));
    }

    @Test
    void statelessMathUsesTheSameExternalDeclarationChain() throws Exception {
        var mathCapabilities =
            ORACLE.capabilities().stream().filter(capability -> capability.sourceSpelling().startsWith("math."))
                .map(BuiltinCapabilityOracle.Capability::sourceSpelling)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(Set.of("math.random", "math.random_integer")), mathCapabilities);

        var host = ExpressionHost.create(HostAvailability.all());
        assertTrue(host.manifest().stream().filter(entry -> entry.stableId().startsWith("math:"))
            .map(entry -> entry.stableId()).collect(java.util.stream.Collectors.toCollection(TreeSet::new))
            .equals(new TreeSet<>(java.util.stream.Stream
                .concat(mathIds().stream(), java.util.stream.Stream.of("math:random", "math:random_integer"))
                .toList())));

        var frontend = new MolangFrontend();
        for (var source : List.of("math.abs(1)", "math.clamp(1,0,1)", "math.floor(1)", "math.lerp(1,2,3)",
            "math.min(1,2)", "math.sqrt(1)")) {
            var result = frontend.compile(source, MolangFrontend.SourceMode.EXPRESSION, LOCATION);
            assertEquals(1, result.referencedSymbols().size(), source);
            assertInstanceOf(ProgramNode.Symbol.class,
                PortableProgramCodecs.v1().decode(result.program()).roots().get(0), source);
        }
        var pi = frontend.compile("math.pi", MolangFrontend.SourceMode.EXPRESSION, LOCATION);
        assertTrue(pi.referencedSymbols().isEmpty());
        assertInstanceOf(ProgramNode.F32.class, PortableProgramCodecs.v1().decode(pi.program()).roots().get(0));
    }

    @Test
    void overBoundVariadicArityIsNotRepresentableInPortableProgramV1() {
        var frontend = new MolangFrontend();
        var arguments = String.join(",", java.util.Collections.nCopies(256, "1"));
        var failure = assertThrows(MolangFrontendException.class,
            () -> frontend.compile("ysm.defer(" + arguments + ')', MolangFrontend.SourceMode.EXPRESSION, LOCATION));
        assertEquals(MolangFrontendException.Kind.SOURCE_LIMIT, failure.kind());

    }

    private static Set<String> mathIds() {
        return java.util.stream.Stream.of("abs", "clamp", "cos", "exp", "floor", "lerp", "max", "min_angle", "min",
            "mod", "pow", "round", "sin", "sqrt").map(name -> "math:" + name)
            .collect(java.util.stream.Collectors.toSet());
    }

    private static String expectedShape(BuiltinCapabilityOracle.Capability capability) {
        var signature = BuiltinStaticOracle.signature(capability.sourceSpelling());
        if (signature.hasVariadic()) {
            return "VARIADIC";
        }
        if (signature.defaultCount() == 0) {
            return "FIXED";
        }
        return "OPTIONAL";
    }

    private static List<BuiltinCapabilityOracle.Capability> capabilitiesWithRegistryEntry() {
        return ORACLE.capabilities().stream().filter(capability -> !"roaming".equals(capability.sourceSpelling()))
            .toList();
    }

    private static Set<String> expectedRegistryIds() {
        return ORACLE.capabilities().stream().filter(capability -> !"roaming".equals(capability.sourceSpelling()))
            .map(BuiltinCapabilityOracle.Capability::stableId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> registeredCapabilityIds(ExpressionHost host) {
        var ids = new TreeSet<String>();
        host.registry().functions().keySet().stream().map(SymbolKey::canonical).forEach(ids::add);
        host.registry().variables().keySet().stream().map(SymbolKey::canonical).forEach(ids::add);
        return ids;
    }

    private static ExpressionHost.ManifestEntry manifestEntry(ExpressionHost host,
        BuiltinCapabilityOracle.Capability capability) {
        return host.manifest().stream().filter(entry -> entry.stableId().equals(capability.stableId()))
            .filter(entry -> !capability.sourceSpelling().equals("ysm.play_sound")
                && !capability.sourceSpelling().equals("ysm.stop_sound")
                || entry.argumentTypes().get(0).equals(BuiltinType.F32.stableId()))
            .findFirst().orElseThrow(
                () -> new AssertionError("capability is not projected into the manifest: " + capability.label()));
    }

    private static String expectedResultStableId(BuiltinCapabilityOracle.Capability capability) {
        return expectedTypeStableId(BuiltinStaticOracle.result(capability.sourceSpelling()));
    }

    private static List<String> expectedArgumentTypes(BuiltinCapabilityOracle.Capability capability) {
        if ("READ".equals(capability.access())) {
            return List.of();
        }
        var signature = BuiltinStaticOracle.signature(capability.sourceSpelling());
        var result = new ArrayList<String>(signature.fixed().size());
        for (var type : signature.fixed()) {
            result.add(expectedTypeStableId(type));
        }
        return List.copyOf(result);
    }

    private static String expectedTypeStableId(BuiltinStaticOracle.Type type) {
        return switch (type) {
            case UNSET -> BuiltinType.UNSET.stableId();
            case F32 -> BuiltinType.F32.stableId();
            case BOOL -> BuiltinType.BOOL.stableId();
            case STRING -> BuiltinType.STRING.stableId();
            case VECTOR -> ExpressionHost.VECTOR.stableId();
            case SOUND_ID -> BuiltinType.F32.stableId();
            case OPTIONAL_BOOL -> BuiltinType.BOOL.stableId();
            case OPTIONAL_F32 -> BuiltinType.F32.stableId();
            case OPTIONAL_STRING -> BuiltinType.STRING.stableId();
        };
    }

}
