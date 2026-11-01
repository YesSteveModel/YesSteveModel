package com.elfmcys.ysm.expression.host;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HostLinkageTest {
    @Test
    void manifestClosesBothDirectionsOverExactAvailability() {
        var host = ExpressionHost.create(HostAvailability.all());
        var first = HostLinkage.production(host);
        var second = HostLinkage.production(host);

        first.requireCompatible(host);
        assertEquals(first.descriptorManifest(), second.descriptorManifest());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(127, first.descriptorManifest().size());
        assertEquals(125,
            first.descriptorManifest().stream().map(HostLinkage.DescriptorBinding::stableId).distinct().count());
        assertEquals(host.manifest().stream().filter(ExpressionHost.ManifestEntry::available).count(),
            first.descriptorManifest().size());
        assertTrue(first.descriptorManifest().stream().noneMatch(value -> value.adapter().contains("ExpressionEvaluator")
            || value.adapter().contains("ControlledHostFixture")));

        var absentHost = ExpressionHost.create(HostAvailability.none());
        var absent = HostLinkage.production(absentHost);
        assertTrue(absent.descriptorManifest().size() < first.descriptorManifest().size());
        assertNotEquals(absent.fingerprint(), first.fingerprint());
    }

    @Test
    void missingOrAbiMismatchedBindingFailsBeforeAnInvocationCanExist() {
        var host = ExpressionHost.create(HostAvailability.all());
        var linkage = HostLinkage.production(host);

        assertThrows(IllegalArgumentException.class,
            () -> linkage.without("global:o").requireCompatible(host));
        assertThrows(IllegalArgumentException.class,
            () -> linkage.withAbiVersion(HostLinkage.ABI_VERSION + 1).requireCompatible(host));
        assertNotEquals(linkage.fingerprint(), linkage.withAbiVersion(HostLinkage.ABI_VERSION + 1).fingerprint());
    }
}
