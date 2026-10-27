package com.elfmcys.ysm.client.compat.parcool;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.geckolib3.core.controller.HybridAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.BiFunction;

public class ParCoolCompat {
    private static final ArtifactVersion MIN_VERSION = new DefaultArtifactVersion("3.4.1");
    private static final String MOD_ID = "parcool";
    private static boolean INSTALLED;
    private static boolean INCOMPATIBLE;

    public static void init() {
        if (ClientConfig.ENABLE_PARCOOL_COMPAT != null) {
            if (!ClientConfig.ENABLE_PARCOOL_COMPAT.get()) {
                INSTALLED = false;
                return;
            }
        }

        var modFile = LoadingModList.get().getModFileById(MOD_ID);
        if (modFile != null) {
            if (modFile.getMods().get(0).getVersion().compareTo(MIN_VERSION) >= 0) {
                INSTALLED = true;
            } else {
                INCOMPATIBLE = true;
            }
        }
    }

    public static Optional<Pair<String, String>> getCompatibilityWarning() {
        if (INCOMPATIBLE) {
            return Optional.of(Pair.of(MOD_ID, MIN_VERSION.toString()));
        }
        return Optional.empty();
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    /**
     * Whether one borrowed entity currently plays a ParCool animation.
     *
     * <p>
     * This is the capability-facing form used by generic control: it owns the player narrowing so the generic decision
     * never reaches the optional adapter itself.
     */
    public static boolean hasAnimation(net.minecraft.world.entity.Entity entity) {
        return entity instanceof Player player && hasAnimation(player);
    }

    /**
     * The ParCool animation name of one borrowed entity without its namespace, or the empty string when this entity has
     * no ParCool animation or the adapter is unavailable.
     */
    public static String animationName(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof Player player)) {
            return "";
        }
        var animation = getAnimation(player);
        if (animation == null) {
            return "";
        }
        return animation.startsWith("parcool:") ? animation.substring("parcool:".length()) : animation;
    }

    public static Optional<BiFunction<String, CustomPlayerEntity, IAnimationController<CustomPlayerEntity>>>
        animationPredicate() {
        if (INSTALLED) {
            return Optional
                .of((name, entity) -> new HybridAnimationController<>(entity, name, 0.1f, new ParCoolPredicate()));
        } else {
            return Optional.empty();
        }
    }

    public static boolean hasAnimation(Player player) {
        if (isInstalled()) {
            return ParCoolAnimationManger.hasAnimation(player);
        }
        return false;
    }

    @Nullable
    public static String getAnimation(Player player) {
        if (isInstalled()) {
            return ParCoolAnimationManger.getAnimation(player);
        }
        return null;
    }

}
