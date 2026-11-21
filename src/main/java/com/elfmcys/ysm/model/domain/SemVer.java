package com.elfmcys.ysm.model.domain;

import java.util.regex.Pattern;

/** An immutable SemVer 2.0.0 value. Precedence excludes build metadata; equality preserves it. */
public final class SemVer implements Comparable<SemVer> {
    private static final String NUMBER = "(0|[1-9][0-9]*)";
    private static final Pattern FORMAT = Pattern.compile(NUMBER + "\\." + NUMBER + "\\." + NUMBER
        + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");
    private final String text;
    private final String[] core;
    private final String[] prerelease;

    private SemVer(String text, String[] core, String[] prerelease) {
        this.text = text;
        this.core = core;
        this.prerelease = prerelease;
    }

    public static SemVer parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 128) {
            throw new IllegalArgumentException("SemVer must contain 1 to 128 ASCII characters");
        }
        var match = FORMAT.matcher(text);
        if (!match.matches()) {
            throw new IllegalArgumentException("Invalid SemVer: " + text);
        }
        var prerelease = match.group(4) == null ? new String[0] : match.group(4).split("\\.");
        for (var identifier : prerelease) {
            if (numeric(identifier) && identifier.length() > 1 && identifier.charAt(0) == '0') {
                throw new IllegalArgumentException("Numeric prerelease identifiers cannot contain leading zeroes");
            }
        }
        return new SemVer(text, new String[] {match.group(1), match.group(2), match.group(3)}, prerelease);
    }

    @Override
    public int compareTo(SemVer other) {
        for (var index = 0; index < core.length; index++) {
            var result = compareNumber(core[index], other.core[index]);
            if (result != 0) {
                return result;
            }
        }
        if (prerelease.length == 0 || other.prerelease.length == 0) {
            return Integer.compare(prerelease.length == 0 ? 1 : 0, other.prerelease.length == 0 ? 1 : 0);
        }
        for (var index = 0; index < Math.min(prerelease.length, other.prerelease.length); index++) {
            var left = prerelease[index];
            var right = other.prerelease[index];
            var leftNumeric = numeric(left);
            var rightNumeric = numeric(right);
            var result = leftNumeric && rightNumeric ? compareNumber(left, right)
                : leftNumeric != rightNumeric ? (leftNumeric ? -1 : 1) : left.compareTo(right);
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(prerelease.length, other.prerelease.length);
    }

    private static int compareNumber(String left, String right) {
        var length = Integer.compare(left.length(), right.length());
        return length == 0 ? left.compareTo(right) : length;
    }

    private static boolean numeric(String value) {
        return value.chars().allMatch(character -> character >= '0' && character <= '9');
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof SemVer other && text.equals(other.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
