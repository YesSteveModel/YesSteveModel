package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.api.molang.PreparedEffect;
import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoBone;
import com.elfmcys.ysm.util.StringPool;
import net.minecraft.util.Mth;
import org.apache.commons.lang3.StringUtils;
import org.joml.Vector3d;
import net.minecraft.world.entity.Entity;

/** Core bone, physics, sound, particle, synchronization and input built-ins. */
@BuiltinComponent(value = "ysm")
public final class ModelBuiltins implements EntityComponent<Entity> {
    private final com.elfmcys.ysm.client.entity.AnimatableEntity<?> owner;
    public ModelBuiltins(com.elfmcys.ysm.client.entity.AnimatableEntity<Entity> owner) { this.owner = owner; }

    private AnimationEvent<?> event;
    @Override public void beginEval(AnimationEvent<?> event) { this.event = event; }
    @Override public void endEval() { event = null; }

    @BuiltinFunction(value = "bone_rot", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 1,
        maxArgs = 1)
    public MolangVector boneRotation(MolangContextView<Entity> context, String name) {
        var bone = owner.getBone(StringPool.computeIfAbsent(name));
        return bone == null ? new MolangVector(0, 0, 0) : new MolangVector(-(float)Math.toDegrees(bone.getRotationX()),
            -(float)Math.toDegrees(bone.getRotationY()), (float)Math.toDegrees(bone.getRotationZ()));
    }

    @BuiltinFunction(value = "bone_pivot_abs", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 1,
        maxArgs = 1)
    public MolangVector bonePivotAbsolute(MolangContextView<Entity> context, String name) {
        return new MolangVector(0, 0, 0);
    }

    @BuiltinFunction(value = "bone_glow", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 2,
        maxArgs = 2)
    public void boneGlow(MolangContextView<Entity> context, String name, float glow) {
        var bone = owner.getBone(StringPool.computeIfAbsent(name));
        var value = Mth.clamp(Math.round(glow), -1, 15);
        context.submit((PreparedEffect)() -> { if (bone instanceof AnimatedGeoBone target) target.setGlow(value); });
    }

    @BuiltinFunction(value = "bone_transparency", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 2,
        maxArgs = 2)
    public void boneTransparency(MolangContextView<Entity> context, String name, float alpha) {
        var bone = owner.getBone(StringPool.computeIfAbsent(name));
        var value = Mth.clamp(Math.round(alpha), 0, 255);
        context.submit((PreparedEffect)() -> { if (bone instanceof AnimatedGeoBone target) target.setTransparency(value); });
    }

    @BuiltinFunction(value = "bone_color", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 4,
        maxArgs = 4)
    public void boneColor(MolangContextView<Entity> context, String name, float red, float green, float blue) {
        var bone = owner.getBone(StringPool.computeIfAbsent(name));
        var r = Mth.clamp(Math.round(red), 0, 255);
        var g = Mth.clamp(Math.round(green), 0, 255);
        var b = Mth.clamp(Math.round(blue), 0, 255);
        context.submit((PreparedEffect)() -> { if (bone instanceof AnimatedGeoBone target) target.setColor(r, g, b); });
    }

    @BuiltinFunction(value = "first_order", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 2,
        maxArgs = 255)
    public float firstOrder(MolangContextView<Entity> context, int argc, String name, float input,
        float... coefficients) {
        return physics(name, input, true, coefficients);
    }

    @BuiltinFunction(value = "second_order", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 2,
        maxArgs = 255)
    public float secondOrder(MolangContextView<Entity> context, int argc, String name, float input,
        float... coefficients) {
        return physics(name, input, false, coefficients);
    }

    @BuiltinFunction(value = "play_sound", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 2,
        maxArgs = 5)
    public void playSound(MolangContextView<Entity> context, int argc, int id, String name,
        @com.elfmcys.ysm.api.annotation.DefaultValue("0") float mode,
        @com.elfmcys.ysm.api.annotation.DefaultValue("1") float volume,
        @com.elfmcys.ysm.api.annotation.DefaultValue("1") float pitch) {
        playSound(context, ModelSoundId.numeric(id), name, (int)mode, volume, pitch);
    }

    @BuiltinFunction(value = "play_sound", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 2,
        maxArgs = 5)
    public void playSound(MolangContextView<Entity> context, int argc, String id, String name,
        @com.elfmcys.ysm.api.annotation.DefaultValue("0") float mode,
        @com.elfmcys.ysm.api.annotation.DefaultValue("1") float volume,
        @com.elfmcys.ysm.api.annotation.DefaultValue("1") float pitch) {
        playSound(context, ModelSoundId.string(id), name, (int)mode, volume, pitch);
    }

    @BuiltinFunction(value = "stop_sound", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 1,
        maxArgs = 2)
    public void stopSound(MolangContextView<Entity> context, int argc, int id,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean global) {
        stopSound(context, ModelSoundId.numeric(id), global);
    }

    @BuiltinFunction(value = "stop_sound", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 1,
        maxArgs = 2)
    public void stopSound(MolangContextView<Entity> context, int argc, String id,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean global) {
        stopSound(context, ModelSoundId.string(id), global);
    }

    @BuiltinFunction(value = "stop_all_sounds", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 0,
        maxArgs = 1)
    public void stopAllSounds(MolangContextView<Entity> context, int argc,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean global) {
        var manager = soundManager(context, global);
        context.submit((PreparedEffect)manager::stopAllPlayingSounds);
    }

    @BuiltinFunction(value = "particle", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 1,
        maxArgs = 255)
    public void particle(MolangContextView<Entity> context, int argc, String name, float... values) {
        particle(context, name, values, false);
    }

    @BuiltinFunction(value = "abs_particle", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 1,
        maxArgs = 255)
    public void absoluteParticle(MolangContextView<Entity> context, int argc, String name, float... values) {
        particle(context, name, values, true);
    }

    @BuiltinFunction(value = "sync", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 0, maxArgs = 16)
    public void sync(MolangContextView<Entity> context, int argc, float... values) {
        var packed = EventArguments.sync(values);
        var target = owner;
        PreparedEffect effect = () -> {};
        if (owner instanceof com.elfmcys.ysm.capability.PlayerAnimatableCapability
            && com.elfmcys.ysm.network.NetworkHandler.isRemoteChannelPresent()) {
            if (owner.getEntity() instanceof net.minecraft.client.player.LocalPlayer)
                effect = () -> com.elfmcys.ysm.network.forge.ClientProtocolGateway.emitMolangSync(target, packed);
        } else if (owner instanceof com.elfmcys.ysm.client.entity.CustomPlayerEntity player) {
            var processor = player.getAnimationProcessor();
            effect = () -> processor.dispatchSync(packed);
        }
        context.submit(effect);
    }

    @BuiltinFunction(value = "defer", effect = com.elfmcys.ysm.api.molang.MolangEffect.ACTION, minArgs = 1,
        maxArgs = 255)
    public void defer(MolangContextView<Entity> context, int argc, float delay, float... values) {
        context.submit(new DeferredRequest(EventArguments.deferred(delay, values)));
    }

    @BuiltinFunction(value = "keyboard", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 1,
        maxArgs = 255)
    public boolean keyboard(MolangContextView<Entity> context, int argc, float... keys) {
        if (!com.elfmcys.ysm.util.InputCheckUtil.isInGame()) return false;
        for (float key : keys) {
            int index = (int)key;
            if (org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE <= index && index <= org.lwjgl.glfw.GLFW.GLFW_KEY_LAST
                && com.elfmcys.ysm.client.event.ModInputEvent.KEY_STATES[index]) return true;
        }
        return false;
    }

    @BuiltinFunction(value = "mod_version", effect = com.elfmcys.ysm.api.molang.MolangEffect.CONTEXT, minArgs = 1,
        maxArgs = 1)
    public String modVersion(MolangContextView<Entity> context, String modId) {
        var mods = net.minecraftforge.fml.ModList.get();
        var container = mods == null ? null : mods.getModContainerById(modId).orElse(null);
        return container == null ? "" : net.minecraftforge.common.util.MavenVersionStringHelper
            .artifactVersionToString(container.getModInfo().getVersion());
    }

    private float physics(String name, float value, boolean first, float[] args) {
        if (name.isEmpty())
            return 0;
        int key = com.elfmcys.ysm.util.StringPool.computeIfAbsent(name);
        var manager = owner.getPhysicsManager(event);
        var current = manager.get(key);
        float a = args.length > 0 ? args[0] : 1, b = args.length > 1 ? args[1] : 1, c = args.length > 2 ? args[2] : 1;
        if (current == null) {
            current = first ? new com.elfmcys.ysm.client.animation.physics.FirstOrder(value, a)
                : new com.elfmcys.ysm.client.animation.physics.SecondOrder(value, a, b, c);
            manager.put(key, current);
            return value;
        }
        current.setArgs(value, a, b, c);
        return current.getValue();
    }
    private SoundInstanceManager soundManager(MolangContextView<Entity> context, boolean global) {
        var inputs = context.inputs();
        if (!global) {
            var animation = AnimationExecutionState.resolve(inputs.animation());
            if (animation != null) return animation.soundManager();
            var controller = ControllerExecutionState.resolve(inputs.controller());
            if (controller != null) {
                var manager = controller.soundManager();
                if (manager != null) return manager;
            }
        }
        return owner.getAnimationProcessor().globalSoundManager();
    }

    private void playSound(MolangContextView<Entity> context, ModelSoundId id, String name, int mode, float volume, float pitch) {
        var manager = soundManager(context, (mode & 2) != 0);
        var target = owner;
        var fake = owner.isFakePlayer();
        var valid = id.playable() && !StringUtils.isBlank(name) && mode >= 0 && mode <= 7;
        context.submit((PreparedEffect)() -> {
            if (valid) manager.playSound(target, id, name, (mode & 1) != 0, instance -> {
                if (instance != null) {
                    instance.setLooping((mode & 4) != 0);
                    instance.setConfiguredVolume(Mth.clamp(volume, 0.001f, 1000f));
                    instance.setPitch(Mth.clamp(pitch, 0.001f, 1000f));
                    if (fake) instance.setAsUI();
                }
            });
        });
    }

    private void stopSound(MolangContextView<Entity> context, ModelSoundId id, boolean global) {
        var manager = soundManager(context, global);
        context.submit((PreparedEffect)() -> { if (id.playable()) manager.stopPlayingSound(id); });
    }

    private void particle(MolangContextView<Entity> context, String id, float[] values, boolean absolute) {
        if (values.length > 254) throw new IllegalArgumentException("particle accepts at most 254 values");
        for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("non-finite particle argument");
        Runnable prepared = () -> {};
        if (!owner.isFakePlayer()) {
            var pos = new Vector3d(argument(values, 0, 0), argument(values, 1, 0), argument(values, 2, 0));
            var delta = new Vector3d(argument(values, 3, 0), argument(values, 4, 0), argument(values, 5, 0));
            try {
                prepared = com.elfmcys.ysm.client.particle.ParticleSpawner.prepareSpawnParticle(owner.getEntity(), id,
                    pos, delta, argument(values, 6, 0), Math.max((int)argument(values, 7, 0), 0),
                    Math.max((int)argument(values, 8, 20), 1), absolute, owner.getAnimationProcessor().randomSource());
            } catch (Exception failure) { throw new IllegalArgumentException("invalid particle effect " + id, failure); }
        }
        context.submit((PreparedEffect)prepared::run);
    }

    private static float argument(float[] values, int index, float fallback) {
        return index < values.length ? values[index] : fallback;
    }
}
