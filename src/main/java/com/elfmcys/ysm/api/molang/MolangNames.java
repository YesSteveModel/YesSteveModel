package com.elfmcys.ysm.api.molang;

import java.util.Locale;
import java.util.Set;

/** Shared source spelling rules for declarations and the frontend. */
public final class MolangNames {
    private static final String ROAMING_SLOT_PREFIX = "__ysm_roaming_";
    public static final Set<String> RESERVED_ROOTS =
        Set.of("math", "query", "q", "variable", "v", "temp", "t", "context", "c", "roaming", "r", "fn", "ysm", "ctrl",
            "args", "this", "loop", "for_each", "true", "false", "return", "break", "continue");

    private MolangNames() {}

    public static String roamingSlot(String member) {
        return ROAMING_SLOT_PREFIX + identifier(member);
    }

    public static boolean isReservedRoamingSlot(String member) {
        return member.startsWith(ROAMING_SLOT_PREFIX);
    }

    public static String roamingMember(String slot) {
        if (!isReservedRoamingSlot(slot)) {
            throw new IllegalArgumentException("not a roaming storage slot: " + slot);
        }
        return identifier(slot.substring(ROAMING_SLOT_PREFIX.length()));
    }

    public static String identifier(String value) {
        String canonical = value.toLowerCase(Locale.ROOT);
        if (!canonical.matches("[a-z_][a-z0-9_]{0,127}")) {
            throw new IllegalArgumentException("invalid Molang identifier: " + value);
        }
        return canonical;
    }

    public static String namespace(String value) {
        if (!value.matches("[a-z][a-z0-9_]{1,63}") || RESERVED_ROOTS.contains(value)) {
            throw new IllegalArgumentException("invalid or reserved Molang mod id: " + value);
        }
        return value;
    }
}
