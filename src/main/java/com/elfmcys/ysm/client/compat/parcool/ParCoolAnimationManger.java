package com.elfmcys.ysm.client.compat.parcool;

import com.alrex.parcool.client.animation.Animator;
import com.alrex.parcool.client.animation.impl.*;
import com.alrex.parcool.common.action.impl.*;
import com.alrex.parcool.common.capability.Animation;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.utilities.VectorUtil;
import com.elfmcys.ysm.mixin.client.parcool.*;
import com.google.common.collect.Maps;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

public class ParCoolAnimationManger {
    private static final HashMap<Class<? extends Animator>, String> INDEX_MAP = Maps.newHashMap();

    static boolean hasAnimation(Player player) {
        String animationName = getAnimation(player);
        return StringUtils.isNotBlank(animationName);
    }

    @Nullable
    static String getAnimation(Player player) {
        Animation animation = Animation.get(player);
        if (animation != null && animation.hasAnimator()) {
            Animator animator = ((AnimationAccessor)animation).getAnimator();
            Parkourability parkourability = Parkourability.get(player);
            if (parkourability == null) {
                return null;
            }

            // 爬行动画不需要，调用默认的即可
            if (animator instanceof CrawlAnimator) {
                return null;
            }

            // 如果动画已经 shouldRemoved 了，就不播放动画了
            if (animator.shouldRemoved(player, parkourability)) {
                return null;
            }

            // 垂挂动画
            if (animator instanceof ClingToCliffAnimator) {
                ClingToCliff.FacingDirection direction = parkourability.get(ClingToCliff.class).getFacingDirection();
                return switch (direction) {
                    case ToWall -> "parcool:cling_to_cliff";
                    case RightAgainstWall -> "parcool:cling_to_cliff_right";
                    case LeftAgainstWall -> "parcool:cling_to_cliff_left";
                };
            }

            // 滑铲
            if (animator instanceof DodgeAnimatorAccessor accessor) {
                Dodge.DodgeDirection direction = accessor.getDirection();
                return switch (direction) {
                    case Front -> "parcool:dodge_front";
                    case Back -> "parcool:dodge_back";
                    case Left -> "parcool:dodge_left";
                    case Right -> "parcool:dodge_right";
                };
            }

            // 翻滚
            if (animator instanceof FlippingAnimatorAccessor accessor) {
                Flipping.Direction direction = accessor.getDirection();
                return switch (direction) {
                    case Front -> "parcool:flipping_front";
                    case Back -> "parcool:flipping_back";
                };
            }

            // 跑墙
            if (animator instanceof HorizontalWallRunAnimatorAccessor accessor) {
                boolean wallIsRightSide = accessor.getWallIsRightSide();
                return wallIsRightSide ? "parcool:horizontal_wall_run_right" : "parcool:horizontal_wall_run_left";
            }

            // 悬挂
            if (animator instanceof HangAnimator) {
                HangDown hangDown = parkourability.get(HangDown.class);
                boolean orthogonalToBar = hangDown.isOrthogonalToBar();
                return orthogonalToBar ? "parcool:hang_vertical" : "parcool:hang";
            }

            // 落地缓冲
            if (animator instanceof RollAnimatorAccessor accessor) {
                Roll.Direction direction = accessor.getDirection();
                return switch (direction) {
                    case Front -> "parcool:roll_front";
                    case Back -> "parcool:roll_back";
                    case Left -> "parcool:roll_left";
                    case Right -> "parcool:roll_right";
                };
            }

            // 翻越动画需要分左右
            if (animator instanceof SpeedVaultAnimatorAccessor accessor) {
                SpeedVaultAnimator.Type type = accessor.getType();
                if (type == SpeedVaultAnimator.Type.Left) {
                    return "parcool:speed_vault_left";
                }
                return "parcool:speed_vault_right";
            }

            // 墙跳
            if (animator instanceof WallJumpAnimatorAccessor accessor) {
                boolean swingRightArm = accessor.isWallRightSide();
                return swingRightArm ? "parcool:wall_jump_right" : "parcool:wall_jump_left";
            }

            // 墙滑
            if (animator instanceof WallSlideAnimator) {
                Vec3 wall = parkourability.get(WallSlide.class).getLeanedWallDirection();
                if (wall == null) {
                    return "parcool:wall_slide_right";
                }
                Vec3 bodyVec = VectorUtil.fromYawDegree(player.yBodyRot);
                Vec3 vec = new Vec3(bodyVec.x, 0, bodyVec.z).normalize();
                Vec3 dividedVec =
                    new Vec3(vec.x * wall.x + vec.z * wall.z, 0, -vec.x * wall.z + vec.z * wall.x).normalize();
                if (dividedVec.z < 0) {
                    return "parcool:wall_slide_right";
                } else {
                    return "parcool:wall_slide_left";
                }
            }

            return getAnimationName(animator);
        }
        return null;
    }

    // 跑酷模组没有给这些动画命名，所以我们手动给命名吧
    private static String getAnimationName(Animator animator) {
        return INDEX_MAP.computeIfAbsent(animator.getClass(),
            clz -> getAnimationNameFromClassName(clz.getSimpleName()));
    }

    private static String getAnimationNameFromClassName(String name) {
        if (StringUtils.isBlank(name)) {
            return "";
        }
        if (name.endsWith("Animator")) {
            name = name.substring(0, name.length() - "Animator".length());
        }
        int len = name.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        sb.insert(0, "parcool:");
        return sb.toString();
    }
}
