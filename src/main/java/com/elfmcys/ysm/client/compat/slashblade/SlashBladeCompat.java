package com.elfmcys.ysm.client.compat.slashblade;

import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.jetbrains.annotations.Nullable;

import static com.elfmcys.ysm.client.compat.slashblade.SlashBladeAnimation.isSlashBlade;

public class SlashBladeCompat {
    private static final String SLASH_BLADE_ID = "slashblade";
    private static boolean IS_LOADED = false;
    private static boolean IS_RESHARPED = false;

    public static void init() {
        ModList.get().getModContainerById(SLASH_BLADE_ID).ifPresent(modContainer -> {
            IS_LOADED = true;
            try {
                ArtifactVersion modVersion = modContainer.getModInfo().getVersion();
                // 旧版拔刀剑最后是 0.1.2 版本
                VersionRange versionRange = VersionRange.createFromVersionSpec("(,0.1.2]");
                IS_RESHARPED = !versionRange.containsVersion(modVersion);
            } catch (InvalidVersionSpecificationException e) {
                e.fillInStackTrace();
            }
            if (!IS_RESHARPED) {
                SlashBladeUnsafe.initFiledOffset();
            }
        });
    }

    public static boolean isSlashBladeLoaded() {
        return IS_LOADED;
    }

    public static boolean isSlashBladeItem(ItemStack stack) {
        return isSlashBladeLoaded() && isSlashBlade(stack);
    }

    public static String getAnimationName(AnimationEvent<? extends CustomHumanoidEntity<?>> event) {
        if (isSlashBladeLoaded()) {
            return SlashBladeAnimation.getAnimationName(event);
        }
        return StringUtils.EMPTY;
    }

    public static String getAnimationName(LivingEntity entity) {
        return isSlashBladeLoaded() ? SlashBladeAnimation.getAnimationName(entity) : StringUtils.EMPTY;
    }

    /**
     * The SlashBlade animation name of one borrowed entity, or the empty string when this entity is not a living entity
     * or the adapter is unavailable.
     *
     * <p>
     * This is the capability-facing form: it owns the living-entity narrowing so a caller never has to reach the
     * optional adapter itself.
     */
    public static String animationName(net.minecraft.world.entity.Entity entity) {
        return entity instanceof LivingEntity living ? getAnimationName(living) : StringUtils.EMPTY;
    }

    @Nullable
    public static PlayState playMainAnimation(LivingEntity livingEntity,
        AnimationEvent<? extends CustomHumanoidEntity<?>> event, String animationName, LoopType loopType) {
        if (isSlashBladeLoaded() && isSlashBladeItem(livingEntity.getMainHandItem())) {
            return SlashBladeAnimation.playMainAnimation(event, animationName, loopType);
        }
        return null;
    }

    public static boolean isResharped() {
        return IS_RESHARPED;
    }

}
