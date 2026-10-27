package com.elfmcys.ysm.client.compat.bettercombat;

import com.elfmcys.ysm.client.compat.bettercombat.event.PlayerAttackEvent;
import com.elfmcys.ysm.util.ReflectionUtil;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import net.bettercombat.api.client.BetterCombatClientEvents;
import net.bettercombat.client.animation.AttackAnimationSubStack;
import net.minecraft.client.player.AbstractClientPlayer;
import org.apache.commons.lang3.StringUtils;

import java.lang.invoke.VarHandle;
import java.util.HashMap;

public class BetterCombatCompatInner {
    private static VarHandle FIELD_ATTACK_ANIM;

    static void innerInit() {
        ReflectionUtil.getField(AbstractClientPlayer.class, "attackAnimation", AttackAnimationSubStack.class)
            .ifPresent(field -> {
                FIELD_ATTACK_ANIM = field;
            });
        if (FIELD_ATTACK_ANIM == null) {
            return;
        }
        BetterCombatClientEvents.ATTACK_START.register(new PlayerAttackEvent());
    }

    private static AttackAnimationSubStack getAnimStack(AbstractClientPlayer player) {
        return (AttackAnimationSubStack)FIELD_ATTACK_ANIM.get(player);
    }

    static String getAttackAnimation(AbstractClientPlayer player) {
        AttackAnimationSubStack animStack = getAnimStack(player);
        if (animStack == null) {
            return StringUtils.EMPTY;
        }
        IAnimation animation = animStack.base.getAnimation();
        if (animation == null) {
            return StringUtils.EMPTY;
        }
        if (!animation.isActive()) {
            return StringUtils.EMPTY;
        }
        if (!(animation instanceof KeyframeAnimationPlayer keyframe)) {
            return StringUtils.EMPTY;
        }
        HashMap<String, Object> extraData = keyframe.getData().extraData;
        Object name = extraData.getOrDefault("name", StringUtils.EMPTY);
        if (!(name instanceof String strName)) {
            return StringUtils.EMPTY;
        }
        return strName;
    }
}
