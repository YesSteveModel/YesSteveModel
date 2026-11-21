package com.elfmcys.ysm.format.schema.model;

import org.junit.jupiter.api.Test;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import static org.junit.jupiter.api.Assertions.*;

class ModelOriginVersionTest {
    @Test
    void divisionFallbackRequiresTheCompleteSourceReleaseAndComparesMajorWithoutOverflow() {
        for (String version : java.util.List.of("0.0.0", "2.6.5", "2.6.6", "2.7.0", "2.99.0-snapshot+build", "2",
            "00.7", "0002.999", "0".repeat(1000) + "2.0-dev+build"))
            assertTrue(ModelOriginVersion.usesDivZeroFallback(version), version);
        for (String version : java.util.List.of("3.0.0", "3.0-dev-forge+mc1.20.1", "4.0.0", "0003.0",
            "2".repeat(1000) + ".0", "", " ", "2.0 ", " 2.0", "-2.0", "+build", "snapshot", "2..0", "2.", "2-", "2+",
            "2+build-dev+other", "2.0\n", "2-dev/forge", "２.0"))
            assertFalse(ModelOriginVersion.usesDivZeroFallback(version), version);
        assertFalse(ModelOriginVersion.usesDivZeroFallback(null));
        for (int level = 0; level < ModelOriginVersion.CURRENT; level++)
            assertTrue(ModelOriginVersion.usesDivZeroFallback(ModelOriginVersion.toModVersion(level)));
        assertFalse(ModelOriginVersion.usesDivZeroFallback(ModelOriginVersion.CURRENT_RELEASE));
    }

    @Test
    void roundTripsEveryHistoricalLevelAndCurrentModels() {
        for (int level = 0; level <= ModelOriginVersion.CURRENT; level++)
            assertEquals(level, ModelOriginVersion.fromModVersion(ModelOriginVersion.toModVersion(level)),
                "level " + level);
        assertEquals(ModelOriginVersion.CURRENT_RELEASE, ModelOriginVersion.toModVersion(0xffff));
        assertThrows(IllegalArgumentException.class, () -> ModelOriginVersion.toModVersion(-1));
    }

    @Test
    void historicalLevelsUseTheirCorrespondingReleaseNames() {
        assertEquals("2.2.1", ModelOriginVersion.toModVersion(4));
        assertEquals("2.2.2", ModelOriginVersion.toModVersion(9));
        assertEquals("2.3.0", ModelOriginVersion.toModVersion(15));
        assertEquals("2.5.0", ModelOriginVersion.toModVersion(26));
        assertEquals("2.5.2", ModelOriginVersion.toModVersion(28));
        assertEquals("2.6.0", ModelOriginVersion.toModVersion(31));
        assertEquals(4, ModelOriginVersion.fromModVersion("2.2.1"));
        assertEquals(9, ModelOriginVersion.fromModVersion("2.2.2"));
        assertEquals(15, ModelOriginVersion.fromModVersion("2.3.0"));
        assertEquals(19, ModelOriginVersion.fromModVersion("2.4.2-snapshot-32"));
        assertEquals(26, ModelOriginVersion.fromModVersion("2.5.0"));
        assertEquals(28, ModelOriginVersion.fromModVersion("2.5.2"));
        assertEquals(31, ModelOriginVersion.fromModVersion("2.6.0"));
        assertEquals(ModelOriginVersion.CURRENT, ModelOriginVersion.fromModVersion("3.0-dev-forge+mc1.20.1"));
        assertEquals(ModelOriginVersion.CURRENT, ModelOriginVersion.fromModVersion(""));
    }

    @Test
    void unknownVersionsResolveToTheNearestLowerHistoricalVersion() {
        assertEquals(15, ModelOriginVersion.fromModVersion("2.4.1"));
        assertEquals(16, ModelOriginVersion.fromModVersion("2.4.2-snapshot-3"));
        assertEquals(18, ModelOriginVersion.fromModVersion("2.4.2-snapshot-31"));
        assertEquals(19, ModelOriginVersion.fromModVersion("2.4.2-snapshot-33"));
        assertEquals(26, ModelOriginVersion.fromModVersion("2.5.1"));
        assertEquals(27, ModelOriginVersion.fromModVersion("2.5.2-snapshot-3"));
        assertEquals(32, ModelOriginVersion.fromModVersion("3.0.0-rc.1"));
        assertEquals(ModelOriginVersion.LEGACY, ModelOriginVersion.fromModVersion("0.0.0-alpha"));
        assertEquals(ModelOriginVersion.CURRENT, ModelOriginVersion.fromModVersion("4.0.0"));
    }

    @Test
    void buildMetadataDoesNotChangeTheHistoricalVersion() {
        for (int level = 0; level <= ModelOriginVersion.CURRENT; level++)
            assertEquals(level,
                ModelOriginVersion.fromModVersion(ModelOriginVersion.toModVersion(level) + "+build.123"));
        assertEquals(18, ModelOriginVersion.fromModVersion("2.4.2-snapshot-31+build.123"));
    }

    @Test
    void historicalVersionsRemainSortedForBinarySearch() {
        for (int level = 1; level <= ModelOriginVersion.CURRENT; level++) {
            var previous = new DefaultArtifactVersion(ModelOriginVersion.toModVersion(level - 1));
            var current = new DefaultArtifactVersion(ModelOriginVersion.toModVersion(level));
            assertTrue(previous.compareTo(current) < 0, "level " + level);
        }
    }

    @Test
    void handLoopBoundaryPreservesTheDefaultAnimationException() {
        assertFalse(ModelOriginVersion.usesFileHandLoop(0, false));
        assertFalse(ModelOriginVersion.usesFileHandLoop(18, false));
        assertTrue(ModelOriginVersion.usesFileHandLoop(18, true));
        assertTrue(ModelOriginVersion.usesFileHandLoop(19, false));
        assertTrue(ModelOriginVersion.usesFileHandLoop(ModelOriginVersion.CURRENT, false));
    }
}
