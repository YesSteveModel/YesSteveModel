package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.expression.host.SubjectSample;
import com.elfmcys.ysm.expression.host.SubjectSampleAccess;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Entity.class)
public abstract class EntitySubjectSampleMixin implements SubjectSampleAccess {
    @Unique
    private volatile SubjectSample ysm$completedSubjectSample = SubjectSample.MISSING;

    public final SubjectSample ysm$subjectSample() {
        return ysm$completedSubjectSample;
    }

    public final void ysm$subjectSample(SubjectSample sample) {
        ysm$completedSubjectSample = sample;
    }
}
