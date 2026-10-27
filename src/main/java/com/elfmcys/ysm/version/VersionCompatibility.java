package com.elfmcys.ysm.version;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.util.Locale;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/** Applies the non-negotiable development-version boundary before a domain policy. */
public final class VersionCompatibility {
    private static final Pattern VERSION_TEXT = Pattern.compile("[0-9][0-9A-Za-z._+\\-]*");
    private static final String[] DEVELOPMENT_MARKERS = {"unstable", "dev", "snapshot"};

    private VersionCompatibility() {}

    public static boolean isCompatible(String currentText, String candidateText,
        BiPredicate<String, String> releasePolicy) {
        Objects.requireNonNull(releasePolicy, "releasePolicy");
        var current = parse(currentText);
        var candidate = parse(candidateText);
        if (current == null || candidate == null) {
            return false;
        }
        if (isDevelopment(current) || isDevelopment(candidate)) {
            return currentText.equals(candidateText);
        }
        return releasePolicy.test(currentText, candidateText);
    }

    private static DefaultArtifactVersion parse(String text) {
        if (text == null || !VERSION_TEXT.matcher(text).matches()) {
            return null;
        }
        try {
            return new DefaultArtifactVersion(text);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean isDevelopment(DefaultArtifactVersion version) {
        var qualifier = version.getQualifier();
        if (qualifier == null) {
            return false;
        }
        var normalized = qualifier.toLowerCase(Locale.ROOT);
        for (var marker : DEVELOPMENT_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
