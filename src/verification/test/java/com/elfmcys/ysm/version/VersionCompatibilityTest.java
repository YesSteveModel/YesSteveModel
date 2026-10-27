package com.elfmcys.ysm.version;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionCompatibilityTest {
    @Test
    void developmentMarkerOnEitherSideRequiresExactText() {
        for (var marker : new String[] {"unstable", "dev", "snapshot"}) {
            var development = "1.2.3-" + marker;
            assertFalse(VersionCompatibility.isCompatible(development, "1.2.4", (current, candidate) -> true));
            assertFalse(VersionCompatibility.isCompatible("1.2.3", development, (current, candidate) -> true));
            assertTrue(VersionCompatibility.isCompatible(development, development, (current, candidate) -> false));
        }
    }

    @Test
    void developmentMarkersAreCaseInsensitiveSubstringsButTextEqualityIsNot() {
        assertFalse(VersionCompatibility.isCompatible("1.2.3-RC-DeV-build", "1.2.3-rc-dev-build",
            (current, candidate) -> true));
        assertFalse(
            VersionCompatibility.isCompatible("1.2.3", "1.2.3-preview-sNaPsHoT-2", (current, candidate) -> true));
        assertTrue(VersionCompatibility.isCompatible("1.2.3-RC-DeV-build", "1.2.3-RC-DeV-build",
            (current, candidate) -> false));
    }

    @Test
    void releaseVersionsDelegateToTheDomainPolicy() {
        assertTrue(VersionCompatibility.isCompatible("1.2.3", "1.2.4", (current, candidate) -> true));
        assertFalse(VersionCompatibility.isCompatible("1.2.3", "1.2.4", (current, candidate) -> false));
    }

    @Test
    void invalidVersionsFailClosedWithoutCallingTheDomainPolicy() {
        var called = new AtomicBoolean();
        var acceptingPolicy = (BiPredicate<String, String>)(current, candidate) -> {
            called.set(true);
            return true;
        };

        assertFalse(VersionCompatibility.isCompatible(null, "1.2.3", acceptingPolicy));
        assertFalse(VersionCompatibility.isCompatible("1.2.3", "not a version", acceptingPolicy));
        assertFalse(called.get());
    }
}
