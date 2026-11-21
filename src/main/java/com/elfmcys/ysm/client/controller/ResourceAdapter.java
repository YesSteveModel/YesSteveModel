package com.elfmcys.ysm.client.controller;

import com.elfmcys.ysm.client.animation.condition.ConditionArmor;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;

public interface ResourceAdapter<T> {
    Object2ReferenceMap<String, AnimationControllerData> getControllers(T model);

    ConditionArmor getArmorCondition(T model);
}
