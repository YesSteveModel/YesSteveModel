package com.elfmcys.ysm.api.internal;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** Shared build/startup parser for the annotation's Java 17 scalar literal subset. */
public final class MolangDefaultValues {
    private static final String DECIMAL_DIGITS = "[0-9](?:[0-9_]*[0-9])?";
    private static final String HEX_DIGITS = "[0-9a-fA-F](?:[0-9a-fA-F_]*[0-9a-fA-F])?";
    private static final String BINARY_DIGITS = "[01](?:[01_]*[01])?";
    private static final Pattern DECIMAL_INTEGER = Pattern.compile("(?:0|[1-9](?:[0-9_]*[0-9])?)[lL]?");
    private static final Pattern OCTAL_INTEGER = Pattern.compile("0(?:_*[0-7](?:[0-7_]*[0-7])?)?[lL]?");
    private static final Pattern HEX_INTEGER = Pattern.compile("0[xX]" + HEX_DIGITS + "[lL]?");
    private static final Pattern BINARY_INTEGER = Pattern.compile("0[bB]" + BINARY_DIGITS + "[lL]?");
    private static final Pattern DECIMAL_FLOAT = Pattern.compile("(?:(?:" + DECIMAL_DIGITS + "\\.(?:" + DECIMAL_DIGITS
        + ")?" + "|\\." + DECIMAL_DIGITS + ")" + "(?:[eE][+-]?" + DECIMAL_DIGITS + ")?[fFdD]?" + "|" + DECIMAL_DIGITS
        + "[eE][+-]?" + DECIMAL_DIGITS + "[fFdD]?" + "|" + DECIMAL_DIGITS + "[fFdD])");
    private static final Pattern HEX_FLOAT = Pattern.compile("0[xX](?:(?:" + HEX_DIGITS + "(?:\\.(?:" + HEX_DIGITS
        + ")?)?)" + "|(?:" + HEX_DIGITS + ")?\\." + HEX_DIGITS + ")" + "[pP][+-]?" + DECIMAL_DIGITS + "[fFdD]?");

    private MolangDefaultValues() {}

    public static Object parse(String kind, String literal) {
        if (literal == null)
            throw new IllegalArgumentException("default literal is null");
        return switch (kind) {
            case "STRING" -> literal;
            case "BOOLEAN" -> parseBoolean(literal);
            case "INT" -> parseInteger(literal);
            case "FLOAT" -> parseFloat(literal);
            default -> throw new IllegalArgumentException("default requires a scalar carrier");
        };
    }

    private static boolean parseBoolean(String literal) {
        if (literal.equals("true"))
            return true;
        if (literal.equals("false"))
            return false;
        throw new IllegalArgumentException("invalid boolean literal");
    }

    private static int parseInteger(String literal) {
        Number value = parseNumber(literal);
        if (!(value instanceof Integer || value instanceof Long) || value.longValue() < Integer.MIN_VALUE
            || value.longValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid int literal");
        }
        return value.intValue();
    }

    private static float parseFloat(String literal) {
        Number value = parseNumber(literal);
        float parsed = value.floatValue();
        if (!Float.isFinite(parsed))
            throw new IllegalArgumentException("non-finite float literal");
        if (parsed == 0.0f && value.doubleValue() != 0.0d) {
            throw new IllegalArgumentException("float literal underflow");
        }
        return parsed;
    }

    private static Number parseNumber(String literal) {
        if (literal.isBlank() || !literal.equals(literal.trim()) || literal.contains("/") || literal.contains(";")) {
            throw new IllegalArgumentException("invalid numeric literal");
        }
        int sign = 1;
        String token = literal;
        if (token.startsWith("+") || token.startsWith("-")) {
            sign = token.charAt(0) == '-' ? -1 : 1;
            token = token.substring(1);
        }
        if (token.isEmpty())
            throw new IllegalArgumentException("invalid numeric literal");
        boolean floating = DECIMAL_FLOAT.matcher(token).matches() || HEX_FLOAT.matcher(token).matches();
        if (floating)
            return parseFloating(token, sign);
        if (!DECIMAL_INTEGER.matcher(token).matches() && !OCTAL_INTEGER.matcher(token).matches()
            && !HEX_INTEGER.matcher(token).matches() && !BINARY_INTEGER.matcher(token).matches()) {
            throw new IllegalArgumentException("invalid numeric literal");
        }
        char suffix = token.charAt(token.length() - 1);
        boolean longSuffix = suffix == 'l' || suffix == 'L';
        if (longSuffix)
            token = token.substring(0, token.length() - 1);
        String normalized = token.replace("_", "");
        int radix;
        String digits;
        boolean decimal;
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            radix = 16;
            digits = normalized.substring(2);
            decimal = false;
        } else if (normalized.startsWith("0b") || normalized.startsWith("0B")) {
            radix = 2;
            digits = normalized.substring(2);
            decimal = false;
        } else if (normalized.length() > 1 && normalized.startsWith("0")) {
            radix = 8;
            digits = normalized.substring(1);
            decimal = false;
        } else {
            radix = 10;
            digits = normalized;
            decimal = true;
        }
        if (digits.isEmpty())
            digits = "0";
        BigInteger magnitude = new BigInteger(digits, radix);
        BigInteger intMaximum =
            decimal ? BigInteger.valueOf(Integer.MAX_VALUE) : BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        if (!longSuffix && magnitude.compareTo(intMaximum) > 0) {
            if (decimal && sign < 0 && magnitude.equals(BigInteger.ONE.shiftLeft(31))) {
                return Integer.valueOf(Integer.MIN_VALUE);
            }
            throw new IllegalArgumentException("integer literal overflow");
        }
        if (!longSuffix) {
            int value = magnitude.intValue();
            return Integer.valueOf(sign < 0 ? -value : value);
        }
        BigInteger longMaximum =
            decimal ? BigInteger.valueOf(Long.MAX_VALUE) : BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        if (magnitude.compareTo(longMaximum) > 0) {
            if (decimal && sign < 0 && magnitude.equals(BigInteger.ONE.shiftLeft(63))) {
                return Long.valueOf(Long.MIN_VALUE);
            }
            throw new IllegalArgumentException("integer literal overflow");
        }
        long value = magnitude.longValue();
        return Long.valueOf(sign < 0 ? -value : value);
    }

    private static Number parseFloating(String token, int sign) {
        char suffix = token.charAt(token.length() - 1);
        boolean floatSuffix = suffix == 'f' || suffix == 'F';
        boolean hasSuffix = floatSuffix || suffix == 'd' || suffix == 'D';
        String normalized = (hasSuffix ? token.substring(0, token.length() - 1) : token).replace("_", "");
        String signed = (sign < 0 ? "-" : "") + normalized;
        Number value =
            floatSuffix ? Float.valueOf(Float.parseFloat(signed)) : Double.valueOf(Double.parseDouble(signed));
        double parsed = value.doubleValue();
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException("non-finite float literal");
        }
        if (parsed == 0.0d && hasNonZeroSignificand(normalized)) {
            throw new IllegalArgumentException("floating literal underflow");
        }
        return value;
    }

    private static boolean hasNonZeroSignificand(String normalized) {
        int exponent = Math.max(normalized.indexOf('e'), normalized.indexOf('E'));
        exponent = Math.max(exponent, Math.max(normalized.indexOf('p'), normalized.indexOf('P')));
        String significand = exponent < 0 ? normalized : normalized.substring(0, exponent);
        for (int index = 0; index < significand.length(); index++) {
            char value = significand.charAt(index);
            if (value >= '1' && value <= '9' || value >= 'a' && value <= 'f' || value >= 'A' && value <= 'F')
                return true;
        }
        return false;
    }
}
