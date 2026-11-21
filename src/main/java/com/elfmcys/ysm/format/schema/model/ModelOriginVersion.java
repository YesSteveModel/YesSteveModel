package com.elfmcys.ysm.format.schema.model;

import it.unimi.dsi.fastutil.objects.*;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.util.Collections;
import java.util.stream.IntStream;

/** Maps source release versions to the historical Java behavior levels. */
public final class ModelOriginVersion {
    public static final String CURRENT_RELEASE = "3.0.0";
    private static final ReferenceList<String> RELEASES =
        ReferenceLists.unmodifiable(ReferenceArrayList.wrap(new String[] {"0.0.0", "1.2.0", "2.2.1-snapshot",
            "2.2.1-snapshot-3", "2.2.1", "2.2.2-snapshot-3", "2.2.2-snapshot-11", "2.2.2-snapshot-14",
            "2.2.2-snapshot-19", "2.2.2", "2.2.3-snapshot", "2.2.3-snapshot-6", "2.2.3-snapshot-8", "2.2.3-snapshot-11",
            "2.2.3-snapshot-28", "2.3.0", "2.4.2-snapshot-1", "2.4.2-snapshot-5", "2.4.2-snapshot-21",
            "2.4.2-snapshot-32", "2.4.2-snapshot-35", "2.4.2-snapshot-37", "2.4.2-snapshot-39", "2.4.2-snapshot-40",
            "2.4.2-snapshot-41", "2.4.2-snapshot-45", "2.5.0", "2.5.2-snapshot-2", "2.5.2", "2.5.4-snapshot-2",
            "2.5.4-snapshot-3", "2.6.0", "2.7.0", CURRENT_RELEASE}));
    private static final ReferenceList<DefaultArtifactVersion> RELEASE_VERSIONS =
        ReferenceLists.unmodifiable(ReferenceArrayList
            .wrap(RELEASES.stream().map(DefaultArtifactVersion::new).toArray(DefaultArtifactVersion[]::new)));

    public static final int LEGACY = 0;
    public static final int CURRENT = RELEASES.size() - 1;

    public static final int FILE_HAND_LOOP = 19;

    private static final Object2IntMap<String> REVERSE_MAP = Object2IntMaps.unmodifiable(
        new Object2IntOpenHashMap<>(RELEASES.toArray(new String[0]), IntStream.range(0, RELEASES.size()).toArray()));

    private static final java.util.regex.Pattern RELEASE_TEXT =
        java.util.regex.Pattern.compile("[0-9]+(?:\\.[0-9]+)*(?:-[A-Za-z0-9._-]+)?(?:\\+[A-Za-z0-9._-]+)?");

    private ModelOriginVersion() {}

    /** Selects the source behavior independently of historical level rounding. */
    public static boolean usesDivZeroFallback(String version) {
        if (version == null || !RELEASE_TEXT.matcher(version).matches())
            return false;
        int end = 0;
        while (end < version.length() && version.charAt(end) >= '0' && version.charAt(end) <= '9')
            end++;
        int significant = 0;
        while (significant < end && version.charAt(significant) == '0')
            significant++;
        return significant == end || end - significant == 1 && version.charAt(significant) < '3';
    }

    public static boolean usesFileHandLoop(int originVer, boolean copiedFromDefault) {
        return originVer >= FILE_HAND_LOOP || copiedFromDefault;
    }

    public static String toModVersion(int originVer) {
        if (originVer < 0)
            throw new IllegalArgumentException("Unknown historical format version: " + originVer);
        if (originVer >= RELEASES.size())
            return CURRENT_RELEASE;
        return RELEASES.get(originVer);
    }

    public static int fromModVersion(String version) {
        if (version.isBlank())
            return CURRENT;
        var exact = REVERSE_MAP.getOrDefault(version, -1);
        if (exact >= 0)
            return exact;
        var parsed = new DefaultArtifactVersion(version.split("\\+", 2)[0]);
        var index = Collections.binarySearch(RELEASE_VERSIONS, parsed);
        return index >= 0 ? index : Math.max(LEGACY, -index - 2);
    }
}
