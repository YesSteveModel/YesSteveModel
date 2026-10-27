package com.elfmcys.ysm.expression.frontend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class MolangNames {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,127}");

    private MolangNames() {}

    public static String identifier(String value) {
        var canonical = Objects.requireNonNull(value, "value").toLowerCase(Locale.ROOT);
        if (!IDENTIFIER.matcher(canonical).matches()) {
            throw new IllegalArgumentException("Invalid Molang identifier: " + value);
        }
        return canonical;
    }

    public static String userFunctionSymbol(String value) {
        var canonical = Objects.requireNonNull(value, "value").toLowerCase(Locale.ROOT);
        if (IDENTIFIER.matcher(canonical).matches()) {
            return "ysm.user-function:" + canonical;
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "ysm.user-function:f_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
