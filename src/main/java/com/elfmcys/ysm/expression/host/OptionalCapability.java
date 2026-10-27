package com.elfmcys.ysm.expression.host;

import java.util.List;

/** Optional host adapters whose availability is part of registry identity. */
public enum OptionalCapability {
    BETTER_COMBAT("bettercombat", null, "net.bettercombat.", "dev.kosmx.playerAnim."),
    CARRY_ON("carryon", null, "tschipp.carryon."), CURIOS("curios", null, "top.theillusivec4.curios."),
    IMMERSIVE_MELODIES("immersive_melodies", null, "immersive_melodies."),
    PARCOOL("parcool", "3.4.1", "com.alrex.parcool."), SLASH_BLADE("slashblade", null, "mods.flammpfeil.slashblade."),
    SOPHISTICATED_BACKPACKS("sophisticatedbackpacks", "3.24.25", "net.p3pp3rf1y.sophisticatedbackpacks."),
    TACZ("tacz", null, "com.tacz.guns.");

    private final String modId;
    private final String minimumVersion;
    private final List<String> externalPackages;

    OptionalCapability(String modId, String minimumVersion, String... externalPackages) {
        this.modId = modId;
        this.minimumVersion = minimumVersion;
        this.externalPackages = List.of(externalPackages);
    }

    public String modId() {
        return modId;
    }

    boolean ownsExternalClass(String binaryName) {
        return externalPackages.stream().anyMatch(binaryName::startsWith);
    }

    boolean supportsVersion(String version, boolean allowUnknownVersion) {
        if (minimumVersion == null) {
            return true;
        }
        if (version == null || version.isBlank() || !Character.isDigit(version.charAt(0))) {
            return allowUnknownVersion;
        }
        int numericLength = actualNumericLength(version);
        var actualNumeric = version.substring(0, numericLength).split("\\.", -1);
        var requiredNumeric = minimumVersion.split("\\.");
        int componentCount = Math.max(actualNumeric.length, requiredNumeric.length);
        for (int index = 0; index < componentCount; index++) {
            int actual;
            int required;
            try {
                actual = index < actualNumeric.length ? Integer.parseInt(actualNumeric[index]) : 0;
                required = index < requiredNumeric.length ? Integer.parseInt(requiredNumeric[index]) : 0;
            } catch (NumberFormatException failure) {
                return allowUnknownVersion;
            }
            if (actual != required) {
                return actual > required;
            }
        }
        return !version.substring(numericLength).startsWith("-");
    }

    String minimumVersion() {
        return minimumVersion;
    }

    private static int actualNumericLength(String version) {
        int qualifier = version.indexOf('-');
        int metadata = version.indexOf('+');
        if (qualifier < 0) {
            return metadata < 0 ? version.length() : metadata;
        }
        return metadata < 0 ? qualifier : Math.min(qualifier, metadata);
    }
}
