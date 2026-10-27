package com.elfmcys.ysm.model.catalog.content;

import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Stable compatibility identity for one default-model animation. */
public record DefaultAnimationKey(String domain, String name) implements Comparable<DefaultAnimationKey> {
    public DefaultAnimationKey {
        if (Objects.requireNonNull(domain, "domain").isBlank()) {
            throw new IllegalArgumentException("Animation domain is empty");
        }
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("Animation name is empty");
        }
    }

    public static String domain(RenderTargetProto target, String animationSet) {
        return domain(target.kind(), target.match(), animationSet);
    }

    public static String domain(RenderTargetKind kind, Collection<String> targetMatches, String animationSet) {
        if (kind == RenderTargetKind.PLAYER) {
            return animationSet.equals("fp_arm") ? "player/first-person" : "player/main";
        }
        var matches = new ArrayList<>(targetMatches);
        matches.sort(Comparator.naturalOrder());
        var canonical = String.join("\0", matches);
        var suffix = HexFormat.of().formatHex(sha256(canonical.getBytes(StandardCharsets.UTF_8)), 0, 8);
        return kind.name().toLowerCase(Locale.ROOT) + "/" + suffix + "/main";
    }

    @Override
    public int compareTo(DefaultAnimationKey other) {
        var order = domain.compareTo(other.domain);
        return order != 0 ? order : name.compareTo(other.name);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
