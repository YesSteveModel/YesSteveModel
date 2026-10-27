package com.elfmcys.ysm.util;

import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

public final class PersonView {
    public static boolean isFirstPersonView(AnimatableEntity<?> animatable) {
        Entity entity = animatable.getEntity();
        if (entity == Minecraft.getInstance().player && RenderUtil.isRenderingLevel()) {
            return Minecraft.getInstance().options.getCameraType().ordinal() == CameraType.FIRST_PERSON.ordinal();
        }
        return false;
    }

    public static boolean isInInventory(AnimationEvent<?> event) {
        return event.getAnimatableEntity() instanceof IPreviewEntity || event.getRenderContext().inventory();
    }
}
