package com.elfmcys.ysm.client.compat.immersivemelodies;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import net.minecraft.world.entity.LivingEntity;

@BuiltinComponent(value = "ctrl",
    dependencies = @com.elfmcys.ysm.api.annotation.MolangDependency(modId = "immersive_melodies"))
public final class ImmersiveMelodiesBuiltinComponent implements EntityComponent<LivingEntity> {
    private final LivingEntity subject;
    private final com.elfmcys.ysm.expression.host.SubjectSample.Draft sample;
    private boolean sampled;
    private final ImmersiveMelodiesCompat.ImmersiveMelodiesData reading =
        new ImmersiveMelodiesCompat.ImmersiveMelodiesData();

    public ImmersiveMelodiesBuiltinComponent(com.elfmcys.ysm.client.entity.AnimatableEntity<LivingEntity> owner) {
        this.subject = owner.getEntity();
        this.sample = owner.subjectSampleDraft();
    }

    @Override
    public void beginEval(com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent<?> event) {
        if (sampled)
            writeSample();
    }

    @Override
    public void update(float previousTime, float currentTime, float partialTicks) {
        ImmersiveMelodiesCompat.updateMelodyProgress(subject, reading);
        sampled = true;
        writeSample();
    }

    private void writeSample() {
        sample.melody(reading.delta, reading.pitch, reading.volume, reading.current, reading.time);
    }

    @BuiltinFunction("im_delta")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float delta(MolangContextView<LivingEntity> context) {
        return context.subjectSample().melodyDelta();
    }

    @BuiltinFunction("im_pitch")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float pitch(MolangContextView<LivingEntity> context) {
        return context.subjectSample().melodyPitch();
    }

    @BuiltinFunction("im_volume")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float volume(MolangContextView<LivingEntity> context) {
        return context.subjectSample().melodyVolume();
    }

    @BuiltinFunction("im_current")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float current(MolangContextView<LivingEntity> context) {
        return context.subjectSample().melodyCurrent();
    }

    @BuiltinFunction("im_time")
    @com.elfmcys.ysm.api.annotation.MolangNullable
    public static float time(MolangContextView<LivingEntity> context) {
        return context.subjectSample().melodyTime();
    }
}
