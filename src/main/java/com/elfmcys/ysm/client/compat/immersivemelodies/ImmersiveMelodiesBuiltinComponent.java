package com.elfmcys.ysm.client.compat.immersivemelodies;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.host.builtin.BuiltinComponent;
import com.elfmcys.ysm.expression.host.builtin.BuiltinFunction;
import net.minecraft.world.entity.LivingEntity;

@BuiltinComponent(value = "ctrl",
    dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "immersive_melodies"))
public final class ImmersiveMelodiesBuiltinComponent implements EntityComponent<LivingEntity> {
    private final LivingEntity subject;
    private final ImmersiveMelodiesCompat.ImmersiveMelodiesData reading =
        new ImmersiveMelodiesCompat.ImmersiveMelodiesData();

    public ImmersiveMelodiesBuiltinComponent(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {
        this.subject = owner.getEntity();
    }

    @Override
    public void update(float previousTime, float currentTime, float partialTicks) {
        ImmersiveMelodiesCompat.updateMelodyProgress(subject, reading);
    }

    @BuiltinFunction(value = "im_delta", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public float delta(MolangContextView<LivingEntity> context) {
        return (float)reading.delta;
    }

    @BuiltinFunction(value = "im_pitch", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public float pitch(MolangContextView<LivingEntity> context) {
        return reading.pitch;
    }

    @BuiltinFunction(value = "im_time", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT)
    public float time(MolangContextView<LivingEntity> context) {
        return (float)reading.time;
    }
}
