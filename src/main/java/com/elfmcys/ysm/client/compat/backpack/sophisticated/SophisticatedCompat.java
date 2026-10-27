package com.elfmcys.ysm.client.compat.backpack.sophisticated;

import com.elfmcys.ysm.client.compat.curios.CuriosCompat;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.config.ClientConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.LoadingModList;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class SophisticatedCompat {
    private static final ArtifactVersion MIN_VERSION = new DefaultArtifactVersion("3.24.25");
    private static final String MOD_ID = "sophisticatedbackpacks";
    private static boolean INSTALLED;
    private static boolean INCOMPATIBLE;

    /**
     * 需要在资源加载后初始化
     */
    public static void init() {
        if (!ClientConfig.ENABLE_SOPHISTICATED_BACKPACK_COMPAT.get()) {
            return;
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
            return Optional.of(Pair.of("Sophisticated Backpacks", MIN_VERSION.toString()));
        }
        return Optional.empty();
    }

    public static void addLayer() {
        if (INSTALLED) {
            RegisterEntityRenderersEvent.getPlayerRenderer().addLayer(new SophisticatedBackpackLayerRenderer());
        }
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    /**
     * Whether one borrowed entity currently has a Sophisticated Backpacks backpack, or {@code false} when this entity
     * is not a living entity or the adapter is unavailable.
     *
     * <p>
     * This is the capability-facing form: it owns the living-entity narrowing so a caller never has to reach the
     * optional adapter itself.
     */
    public static boolean hasBackpack(net.minecraft.world.entity.Entity entity) {
        return entity instanceof LivingEntity living && isInstalled() && getBackpackItemStack(living) != null;
    }

    @Nullable
    public static ItemStack getBackpackItemStack(LivingEntity livingEntity) {
        // 先尝试获取 curios 的背包
        if (CuriosCompat.isInstalled()) {
            var backpack = BackpackCuriosCompat.getCuriosBackpack(livingEntity);
            if (backpack != null) {
                return backpack;
            }
        }
        // 然后才是玩家护甲栏的背包
        if (livingEntity instanceof Player player) {
            return PlayerInventoryProvider.get().getBackpackFromRendered(player).map(backpackRenderInfo -> {
                var itemStack = backpackRenderInfo.getBackpack();
                if (!itemStack.isEmpty()) {
                    return itemStack;
                }
                return null;
            }).orElse(null);
        }
        return null;
    }
}
