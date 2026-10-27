package com.elfmcys.ysm.geckolib3.model;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

public class EntityStateTracker<T extends Entity> {
    protected T entity;

    private final IntOpenHashSet entityTickStates;

    private String mainAnimationCache;
    protected float renderTickDelta;

    public EntityStateTracker(T entity) {
        this.entity = entity;
        this.entityTickStates = new IntOpenHashSet();
    }

    public void reset() {
        resetAnimationHistory();
    }

    public final void resetAnimationHistory() {
        entityTickStates.clear();
        mainAnimationCache = null;
        renderTickDelta = 0;
    }

    public final void tick(int previousTick, int currentTick) {
        updateEntityTickData(previousTick, currentTick);
    }

    public final void update(float previousTime, float currentTime, float partialTicks) {
        updateRenderTickData(previousTime, currentTime, partialTicks);
    }

    public void setEntity(T entity) {
        this.entity = entity;
    }

    protected void updateRenderTickData(float previousTime, float currentTime, float partialTicks) {
        renderTickDelta = currentTime - previousTime;
        mainAnimationCache = null;
    }

    protected void updateEntityTickData(int previousTick, int currentTick) {
        entityTickStates.clear();
    }

    /**
     * 在每个 entityTick 开始时都会清空的状态， 在同一个 entityTick 内的所有 renderTick 可共享这些状态
     */
    public boolean setEntityTickState(int name) {
        return entityTickStates.add(name);
    }

    public boolean hasEntityTickState(int name) {
        return entityTickStates.contains(name);
    }

    @Nullable
    public String getMainAnimationCache() {
        return mainAnimationCache;
    }

    public void setMainAnimationCache(String mainAnimation) {
        this.mainAnimationCache = mainAnimation;
    }

    public float getRenderTickDelta() {
        return renderTickDelta;
    }
}
