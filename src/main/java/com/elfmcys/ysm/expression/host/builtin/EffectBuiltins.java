package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.AnimationExecutionState;
import com.elfmcys.ysm.expression.host.ControllerExecutionState;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.expression.host.EventArguments;
import com.elfmcys.ysm.expression.host.ModelSoundId;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoBone;
import com.elfmcys.ysm.util.StringPool;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.apache.commons.lang3.StringUtils;
import org.joml.Vector3d;

@BuiltinComponent("ysm")
public final class EffectBuiltins implements EntityComponent<Entity> {
    private final AnimatableEntity<?> owner;

    public EffectBuiltins(AnimatableEntity<Entity> owner) {
        this.owner = owner;
    }

    @BuiltinFunction(value = "bone_glow", alwaysOrigin = true, action = true)
    public void boneGlow(MolangContextView<Entity> context, String name, float glow) {
        var bone = owner.getBone(StringPool.computeIfAbsent(name));
        var value = Mth.clamp(Math.round(glow), -1, 15);
        if (bone instanceof AnimatedGeoBone target)
            target.setGlow(value);
    }

    @BuiltinFunction(value = "bone_transparency", alwaysOrigin = true, action = true)
    public void boneTransparency(MolangContextView<Entity> context, String name, float alpha) {
        var bone = owner.getBone(StringPool.computeIfAbsent(name));
        var value = Mth.clamp(Math.round(alpha), 0, 255);
        if (bone instanceof AnimatedGeoBone target)
            target.setTransparency(value);
    }

    @BuiltinFunction(value = "bone_color", alwaysOrigin = true, action = true)
    public void boneColor(MolangContextView<Entity> context, String name, float red, float green, float blue) {
        var bone = owner.getBone(StringPool.computeIfAbsent(name));
        var r = Mth.clamp(Math.round(red), 0, 255);
        var g = Mth.clamp(Math.round(green), 0, 255);
        var b = Mth.clamp(Math.round(blue), 0, 255);
        if (bone instanceof AnimatedGeoBone target)
            target.setColor(r, g, b);
    }

    @BuiltinFunction(value = "play_sound", alwaysOrigin = true, action = true)
    public void playSound(MolangContextView<Entity> context, int argc, int id, String name,
        @com.elfmcys.ysm.api.annotation.DefaultValue("0") float mode,
        @com.elfmcys.ysm.api.annotation.DefaultValue("1") float volume,
        @com.elfmcys.ysm.api.annotation.DefaultValue("1") float pitch) {
        playSound(context, ModelSoundId.numeric(id), name, (int)mode, volume, pitch);
    }

    @BuiltinFunction(value = "play_sound", alwaysOrigin = true, action = true)
    public void playSound(MolangContextView<Entity> context, int argc, String id, String name,
        @com.elfmcys.ysm.api.annotation.DefaultValue("0") float mode,
        @com.elfmcys.ysm.api.annotation.DefaultValue("1") float volume,
        @com.elfmcys.ysm.api.annotation.DefaultValue("1") float pitch) {
        playSound(context, ModelSoundId.string(id), name, (int)mode, volume, pitch);
    }

    // An untyped null must keep selecting the empty string ID on an overload tie.
    @BuiltinFunction(value = "stop_sound", alwaysOrigin = true, action = true)
    public void stopSound(MolangContextView<Entity> context, int argc, String id,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean global) {
        stopSound(context, ModelSoundId.string(id), global);
    }

    @BuiltinFunction(value = "stop_sound", alwaysOrigin = true, action = true)
    public void stopSound(MolangContextView<Entity> context, int argc, int id,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean global) {
        stopSound(context, ModelSoundId.numeric(id), global);
    }

    @BuiltinFunction(value = "stop_all_sounds", alwaysOrigin = true, action = true)
    public void stopAllSounds(MolangContextView<Entity> context, int argc,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean global) {
        var manager = soundManager(context, global);
        manager.stopAllPlayingSounds();
    }

    @BuiltinFunction(value = "particle", alwaysOrigin = true, action = true)
    public void particle(MolangContextView<Entity> context, int argc, String name, float... values) {
        particle(context, name, values, false);
    }

    @BuiltinFunction(value = "abs_particle", alwaysOrigin = true, action = true)
    public void absoluteParticle(MolangContextView<Entity> context, int argc, String name, float... values) {
        particle(context, name, values, true);
    }

    @BuiltinFunction(value = "sync", alwaysOrigin = true, action = true, maxArgs = 16)
    public void sync(MolangContextView<Entity> context, int argc, float... values) {
        var packed = EventArguments.sync(values);
        if (owner instanceof com.elfmcys.ysm.client.entity.CustomPlayerEntity player
            && (player.isLocalPlayer() || player.isFakePlayer())) {
            owner.getExpressionRuntime().enqueueLocalSync(packed, player.getAnimationProcessor());
            if (player.isLocalPlayer() && com.elfmcys.ysm.network.NetworkHandler.isRemoteChannelPresent())
                com.elfmcys.ysm.network.forge.ClientProtocolGateway.emitMolangSync(owner, packed);
        }
    }

    @BuiltinFunction(value = "defer", alwaysOrigin = true, action = true)
    public void defer(MolangContextView<Entity> context, int argc, float delay, float... values) {
        context.defer(delay, values);
    }

    private SoundInstanceManager soundManager(MolangContextView<Entity> context, boolean global) {
        var inputs = context.inputs();
        if (!global) {
            var animation = AnimationExecutionState.resolve(inputs.animation());
            if (animation != null)
                return animation.soundManager();
            var controller = ControllerExecutionState.resolve(inputs.controller());
            if (controller != null) {
                var manager = controller.soundManager();
                if (manager != null)
                    return manager;
            }
        }
        return owner.getAnimationProcessor().globalSoundManager();
    }

    private void playSound(MolangContextView<Entity> context, ModelSoundId id, String name, int mode, float volume,
        float pitch) {
        var manager = soundManager(context, (mode & 2) != 0);
        var target = owner;
        var fake = owner.isFakePlayer();
        var valid = id.playable() && !StringUtils.isBlank(name) && mode >= 0 && mode <= 7;
        if (valid)
            manager.playSound(target, id, name, (mode & 1) != 0, instance -> {
                if (instance != null) {
                    instance.setLooping((mode & 4) != 0);
                    instance.setConfiguredVolume(Mth.clamp(volume, 0.001f, 1000f));
                    instance.setPitch(Mth.clamp(pitch, 0.001f, 1000f));
                    if (fake)
                        instance.setAsUI();
                }
            });
    }

    private void stopSound(MolangContextView<Entity> context, ModelSoundId id, boolean global) {
        var manager = soundManager(context, global);
        if (id.playable())
            manager.stopPlayingSound(id);
    }

    private void particle(MolangContextView<Entity> context, String id, float[] values, boolean absolute) {
        if (values.length > 254)
            throw new IllegalArgumentException("particle accepts at most 254 values");
        for (float value : values)
            if (!Float.isFinite(value))
                throw new IllegalArgumentException("non-finite particle argument");
        if (owner.isFakePlayer())
            return;
        var pos = new Vector3d(argument(values, 0, 0), argument(values, 1, 0), argument(values, 2, 0));
        var delta = new Vector3d(argument(values, 3, 0), argument(values, 4, 0), argument(values, 5, 0));
        com.elfmcys.ysm.client.particle.ParticleSpawner.spawnParticle(owner.getEntity(), id, pos, delta,
            argument(values, 6, 0), Math.max((int)argument(values, 7, 0), 0), Math.max((int)argument(values, 8, 20), 1),
            absolute, owner.getAnimationProcessor().randomSource());
    }

    private static float argument(float[] values, int index, float fallback) {
        return index < values.length ? values[index] : fallback;
    }
}
