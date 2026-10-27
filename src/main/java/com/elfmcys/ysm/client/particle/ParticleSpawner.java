package com.elfmcys.ysm.client.particle;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.commands.arguments.ParticleArgument;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.commons.lang3.StringUtils;
import org.joml.Vector3d;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@OnlyIn(Dist.CLIENT)
public class ParticleSpawner {
    private static final Cache<String, ParticleOptions> PARTICLE_OPTIONS_CACHE =
        CacheBuilder.newBuilder().expireAfterAccess(60, TimeUnit.SECONDS).build();

    /**
     * Validates and freezes an AOT particle effect before its logical transaction commits. The returned action performs
     * only the already-admitted client mutation.
     */
    public static Runnable prepareSpawnParticle(Entity entity, String id, Vector3d pos, Vector3d delta,
        double particleSpeed, int count, int particleLifeTime, boolean absPos, RandomSource random)
        throws ExecutionException, CommandSyntaxException {
        if (StringUtils.isBlank(id)) {
            return () -> {
            };
        }
        ParticleOptions particleOptions = particleOptions(id);
        var frozenPos = new Vector3d(pos);
        var frozenDelta = new Vector3d(delta);
        return () -> spawnParticle(entity, particleOptions, frozenPos, frozenDelta, particleSpeed, count,
            particleLifeTime, absPos, random);
    }

    private static ParticleOptions particleOptions(String id) throws ExecutionException, CommandSyntaxException {
        return PARTICLE_OPTIONS_CACHE.get(id,
            () -> ParticleArgument.readParticle(new StringReader(id), BuiltInRegistries.PARTICLE_TYPE.asLookup()));
    }

    private static void spawnParticle(Entity entity, ParticleOptions particleOptions, Vector3d pos, Vector3d delta,
        double particleSpeed, int count, int particleLifeTime, boolean absPos, RandomSource random) {
        if (particleOptions == null) {
            return;
        }
        ParticleEngine particleEngine = Minecraft.getInstance().particleEngine;
        if (count == 0) {
            // 单个粒子准确在指定位置生成
            Vec3 offset = new Vec3(pos.x(), pos.y(), pos.z());
            if (!absPos) {
                if (entity instanceof Player player) {
                    offset = offset.yRot(-player.yBodyRot * Mth.DEG_TO_RAD);
                } else {
                    offset = offset.yRot(-entity.getYRot() * Mth.DEG_TO_RAD);
                }
            }

            double xPos = entity.getX() + offset.x();
            double yPos = entity.getY() + offset.y();
            double zPos = entity.getZ() + offset.z();

            double xSpeed = particleSpeed * delta.x();
            double ySpeed = particleSpeed * delta.y();
            double zSpeed = particleSpeed * delta.z();

            Minecraft.getInstance().execute(() -> {
                Particle result =
                    particleEngine.createParticle(particleOptions, xPos, yPos, zPos, xSpeed, ySpeed, zSpeed);
                if (result != null) {
                    result.setLifetime(particleLifeTime);
                }
            });
        } else {
            // 多个粒子就需要加点随机了
            for (int i = 0; i < count; ++i) {
                createParticle(entity, pos, delta, particleSpeed, particleLifeTime, particleEngine, particleOptions,
                    absPos, random);
            }
        }
    }

    private static void createParticle(Entity entity, Vector3d pos, Vector3d delta, double particleSpeed,
        int particleLifeTime, ParticleEngine particleEngine, ParticleOptions particleOptions, boolean absPos,
        RandomSource random) {
        double offsetX = random.nextGaussian() * delta.x();
        double offsetY = random.nextGaussian() * delta.y();
        double offsetZ = random.nextGaussian() * delta.z();

        double xSpeed = random.nextGaussian() * particleSpeed;
        double ySpeed = random.nextGaussian() * particleSpeed;
        double zSpeed = random.nextGaussian() * particleSpeed;

        Vec3 offset = new Vec3(pos.x() + offsetX, pos.y() + offsetY, pos.z() + offsetZ);
        if (!absPos) {
            offset = offset.yRot(-entity.getYRot() * Mth.DEG_TO_RAD);
        }

        double posX = entity.getX() + offset.x();
        double posY = entity.getY() + offset.y();
        double posZ = entity.getZ() + offset.z();

        Minecraft.getInstance().execute(() -> {
            Particle result = particleEngine.createParticle(particleOptions, posX, posY, posZ, xSpeed, ySpeed, zSpeed);
            if (result != null) {
                result.setLifetime(particleLifeTime);
            }
        });
    }
}
