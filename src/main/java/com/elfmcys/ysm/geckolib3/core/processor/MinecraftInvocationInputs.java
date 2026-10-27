package com.elfmcys.ysm.geckolib3.core.processor;

import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.particle.ParticleSpawner;
import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;
import com.elfmcys.ysm.expression.host.EffectCommand;
import com.elfmcys.ysm.expression.host.HostSymbol;
import com.elfmcys.ysm.expression.host.InvocationProvenance;
import com.elfmcys.ysm.expression.host.OwnerEffectSink;
import com.elfmcys.ysm.expression.host.AnimationExecutionState;
import com.elfmcys.ysm.expression.host.ControllerExecutionState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoBone;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import com.elfmcys.ysm.util.StringPool;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** Direct invocation-scoped Minecraft adapter used by the persistent entity runtime. */
final class MinecraftInvocationInputs implements MinecraftRuntimeInputs<Entity>, OwnerEffectSink.Owner {
    private static final int LOOP_VALUE = 10;
    private static final int PLAY_ONCE_VALUE = 11;
    private static final int HOLD_ON_LAST_FRAME_VALUE = 12;

    private final AnimatableEntity<?> animatable;

    @Override
    public AnimatableEntity<?> animatable() {
        return animatable;
    }

    @Override
    public net.minecraft.world.entity.Entity entity() {
        return animatable.getEntity();
    }

    @Override
    public float partialTick() {
        return event.getPartialTick();
    }

    @Override
    public float renderTickDelta() {
        return animatable.getStateTracker().getRenderTickDelta();
    }

    private final AnimationEvent<?> event;
    private final RandomSource random;
    private final SoundInstanceManager sounds;
    private final PreparedEffects prepared;
    @Nullable
    private final ControllerExecutionState controller;
    @Nullable
    private final AnimationExecutionState animation;

    @Override
    public AnimationEvent<?> animationEvent() {
        return event;
    }

    @Override
    public RandomSource randomSource() {
        return random;
    }

    @Override
    public @Nullable ControllerExecutionState controllerState() {
        return controller;
    }

    @Override
    public @Nullable AnimationExecutionState animationState() {
        return animation;
    }

    MinecraftInvocationInputs(AnimatableEntity<?> animatable, AnimationEvent<?> event, RandomSource random,
        SoundInstanceManager sounds, @Nullable ControllerExecutionState controller,
        @Nullable AnimationExecutionState animation) {
        this(animatable, event, random, sounds, controller, animation, new PreparedEffects());
    }

    private MinecraftInvocationInputs(AnimatableEntity<?> animatable, AnimationEvent<?> event, RandomSource random,
        SoundInstanceManager sounds, @Nullable ControllerExecutionState controller,
        @Nullable AnimationExecutionState animation, PreparedEffects prepared) {
        this.animatable = Objects.requireNonNull(animatable, "animatable");
        this.event = Objects.requireNonNull(event, "event");
        this.random = Objects.requireNonNull(random, "random");
        this.sounds = Objects.requireNonNull(sounds, "sounds");
        this.controller = controller;
        this.animation = animation;
        this.prepared = prepared;
    }

    @Override
    public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {
        prepared.put(command, prepareEffect(command, scope));
    }

    @Override
    public void clearPreparedEffects() {
        prepared.clear();
    }

    @Override
    public OwnerEffectSink.PreparedEffects prepare(List<EffectCommand> commands, InvocationProvenance provenance) {
        var commits = new ArrayList<Runnable>(commands.size());
        for (var command : commands) {
            var commit = prepared.remove(command);
            if (commit == null) {
                throw new IllegalStateException("effect was not prevalidated: " + command);
            }
            commits.add(commit);
        }
        return () -> commits.forEach(Runnable::run);
    }

    private Runnable prepareEffect(EffectCommand command, MolangContext<?> scope) {
        if (command instanceof EffectCommand.Sound sound) {
            return prepareSound(sound, scope);
        }
        if (command instanceof EffectCommand.Particle particle) {
            return prepareParticle(particle);
        }
        if (command instanceof EffectCommand.Controller value) {
            return prepareController(value);
        }
        if (command instanceof EffectCommand.Sync value) {
            return prepareSync(value);
        }
        if (command instanceof EffectCommand.Deferred) {
            return () -> {
            };
        }
        throw new IllegalArgumentException("unsupported prevalidated effect " + command);
    }

    private Runnable prepareController(EffectCommand.Controller value) {
        if (value.action() == HostSymbol.YSM_BONE_COLOR || value.action() == HostSymbol.YSM_BONE_GLOW
            || value.action() == HostSymbol.YSM_BONE_TRANSPARENCY) {
            var bone = animatable.getBone(StringPool.computeIfAbsent(value.name()));
            if (!(bone instanceof AnimatedGeoBone animatedBone)) {
                return () -> {
                };
            }
            return switch (value.action()) {
                case YSM_BONE_COLOR -> {
                    var red = roundedClamp(value.first(), 0, 255);
                    var green = roundedClamp(value.second(), 0, 255);
                    var blue = roundedClamp(value.third(), 0, 255);
                    yield () -> animatedBone.setColor(red, green, blue);
                }
                case YSM_BONE_GLOW -> {
                    var glow = roundedClamp(value.first(), -1, 15);
                    yield () -> animatedBone.setGlow(glow);
                }
                case YSM_BONE_TRANSPARENCY -> {
                    var alpha = roundedClamp(value.first(), 0, 255);
                    yield () -> animatedBone.setTransparency(alpha);
                }
                default -> throw new AssertionError(value.action());
            };
        }

        var coded = event.getCodedController();
        return switch (value.action()) {
            case CTRL_SET_ANIMATION -> {
                var name = value.name();
                var loop = loopType((int)value.first());
                yield () -> {
                    if (coded != null && StringUtils.isNotEmpty(name)) {
                        coded.setAnimation(name, loop);
                    }
                };
            }
            case CTRL_SET_BEGINNING_TRANSITION_LENGTH -> {
                var length = value.first();
                yield () -> {
                    if (coded != null && length >= 0) {
                        coded.setBeginningTransitionLength(length);
                    }
                };
            }
            case CTRL_RESET -> () -> {
                if (coded != null)
                    coded.reset();
            };
            case CTRL_INDICATE_RELOAD -> () -> {
                if (coded != null)
                    coded.indicateReload();
            };
            default -> throw new IllegalArgumentException("unsupported controller effect " + value.action());
        };
    }

    private Runnable prepareParticle(EffectCommand.Particle command) {
        if (animatable.isFakePlayer()) {
            return () -> {
            };
        }
        var id = command.name();
        var pos = new Vector3d(command.argument(0, 0), command.argument(1, 0), command.argument(2, 0));
        var delta = new Vector3d(command.argument(3, 0), command.argument(4, 0), command.argument(5, 0));
        var speed = command.argument(6, 0);
        var count = Math.max((int)command.argument(7, 0), 0);
        var lifetime = Math.max((int)command.argument(8, 20), 1);
        try {
            return ParticleSpawner.prepareSpawnParticle(animatable.getEntity(), id, pos, delta, speed, count, lifetime,
                command.absolute(), random);
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid particle effect " + id, failure);
        }
    }

    private Runnable prepareSync(EffectCommand.Sync value) {
        var packed = value.arguments();
        if (animatable instanceof PlayerAnimatableCapability) {
            if (NetworkHandler.isRemoteChannelPresent()) {
                return animatable.getEntity() instanceof LocalPlayer
                    ? () -> ClientProtocolGateway.emitMolangSync(animatable, packed) : () -> {
                    };
            }
        }
        return animatable instanceof CustomPlayerEntity player ? () -> player.dispatchSync(packed) : () -> {
        };
    }

    private Runnable prepareSound(EffectCommand.Sound command, MolangContext<?> scope) {
        return switch (command.action()) {
            case YSM_PLAY_SOUND -> {
                var id = command.soundId();
                var name = command.name();
                var mode = command.mode();
                if (!id.playable() || StringUtils.isBlank(name) || mode < 0 || mode > 7) {
                    yield () -> {
                    };
                }
                var volume = command.volume();
                var pitch = command.pitch();
                var manager = soundManager((mode & 2) != 0, scope);
                yield () -> {
                    if (manager != null) {
                        manager.playSound(animatable, id, name, (mode & 1) != 0, instance -> {
                            if (instance != null) {
                                instance.setLooping((mode & 4) != 0);
                                instance.setConfiguredVolume(Mth.clamp(volume, 0.001f, 1000f));
                                instance.setPitch(Mth.clamp(pitch, 0.001f, 1000f));
                                if (animatable.isFakePlayer())
                                    instance.setAsUI();
                            }
                        });
                    }
                };
            }
            case YSM_STOP_SOUND -> {
                var id = command.soundId();
                if (!id.playable()) {
                    yield () -> {
                    };
                }
                var manager = soundManager(command.global(), scope);
                yield () -> {
                    if (manager != null)
                        manager.stopPlayingSound(id);
                };
            }
            case YSM_STOP_ALL_SOUNDS -> {
                var manager = soundManager(command.global(), scope);
                yield () -> {
                    if (manager != null)
                        manager.stopAllPlayingSounds();
                };
            }
            default -> throw new IllegalArgumentException("unsupported sound effect " + command.action());
        };
    }

    @Nullable
    private SoundInstanceManager soundManager(boolean global, MolangContext<?> scope) {
        if (!global) {
            var animation = scope.animationState();
            if (animation != null) {
                return animation.soundManager();
            }
            var controller = scope.controllerState();
            if (controller != null) {
                var manager = controller.soundManager();
                if (manager != null) {
                    return manager;
                }
            }
        }
        return sounds;
    }

    private static int roundedClamp(float value, int minimum, int maximum) {
        return Mth.clamp(Math.round(value), minimum, maximum);
    }

    @Nullable
    private static LoopType loopType(int value) {
        return switch (value) {
            case LOOP_VALUE -> LoopType.LOOP;
            case PLAY_ONCE_VALUE -> LoopType.PLAY_ONCE;
            case HOLD_ON_LAST_FRAME_VALUE -> LoopType.HOLD_ON_LAST_FRAME;
            default -> null;
        };
    }

    private static final class PreparedEffects {
        private final IdentityHashMap<EffectCommand, Runnable> commits = new IdentityHashMap<>();

        private void put(EffectCommand command, Runnable commit) {
            if (commits.put(command, commit) != null) {
                throw new IllegalStateException("effect command was prevalidated twice");
            }
        }

        @Nullable
        private Runnable remove(EffectCommand command) {
            return commits.remove(command);
        }

        private void clear() {
            commits.clear();
        }
    }
}
