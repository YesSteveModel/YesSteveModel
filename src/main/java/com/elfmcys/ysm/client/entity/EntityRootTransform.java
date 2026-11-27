package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.animation.AnimationParallelTicker;
import com.elfmcys.ysm.client.animation.AnimationParallelTicker.PassengerTransform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/** The rigid model frame shared by world extraction and replacement drawing. */
public final class EntityRootTransform {
    private static final float MODEL_LIFT = 0.01F;

    private final Vec3 entityWorldPos;
    private final Vec3 renderRootPos;
    private final ModelPose modelPose;

    private EntityRootTransform(Vec3 entityWorldPos, Vec3 renderRootPos, ModelPose modelPose) {
        this.entityWorldPos = entityWorldPos;
        this.renderRootPos = renderRootPos;
        this.modelPose = modelPose;
    }

    public Vec3 entityWorldPos() {
        return entityWorldPos;
    }

    public Vec3 renderRootPos() {
        return renderRootPos;
    }

    public Quaternionfc renderRootQuat() {
        return modelPose.rotation();
    }

    /** Captures the world pose before render-layer scaling, using the invocation's published passenger transform. */
    public static EntityRootTransform capture(LivingEntity entity, float partialTick, float lerpBodyRot) {
        var passenger = AnimationParallelTicker.passengerTransform(entity);
        var model = modelPose(entity, lerpBodyRot, passenger);
        // The level renderer initializes the interpolation baseline for a newly visible entity after submission.
        var worldPos = entity.tickCount == 0 ? entity.position()
            : new Vec3(Mth.lerp((double)partialTick, entity.xOld, entity.getX()),
                Mth.lerp((double)partialTick, entity.yOld, entity.getY()),
                Mth.lerp((double)partialTick, entity.zOld, entity.getZ()));
        // The level dispatcher already translated the vanilla player stack before handing it to our renderer.
        double dispatcherY = entity instanceof AbstractClientPlayer && entity.isCrouching() ? -0.125 : 0;
        double passengerY = passenger == null ? 0 : passenger.yOffset();
        return new EntityRootTransform(worldPos, model.worldPosition(worldPos, dispatcherY + passengerY), model);
    }

    /** Applies the model pose after dispatcher and passenger translation. */
    public void applyModelPose(PoseStack poseStack) {
        modelPose.applyTo(poseStack);
    }

    /** Applies the same model pose for a draw with no sampled physics root. */
    public static void applyModelPose(LivingEntity entity, float lerpBodyRot, PoseStack poseStack) {
        modelPose(entity, lerpBodyRot, AnimationParallelTicker.passengerTransform(entity)).applyTo(poseStack);
    }

    private static ModelPose modelPose(LivingEntity entity, float bodyYaw, @Nullable PassengerTransform passenger) {
        if (!entity.isSpectator()) {
            // The vanilla query writes lastClimbablePos and the lazy feet-state cache.
            var level = entity.level();
            var feet = entity.blockPosition();
            var ladder = ForgeHooks.isLivingOnLadder(level.getBlockState(feet), level, feet, entity);
            if (ladder.isPresent()) {
                var facing = level.getBlockState(ladder.get()).getOptionalValue(HorizontalDirectionalBlock.FACING);
                if (facing.isPresent())
                    bodyYaw = facing.get().getOpposite().get2DDataValue() * 90;
            }
        }
        if (entity.isFullyFrozen())
            bodyYaw += (float)(Math.cos(entity.tickCount * 3.25) * Math.PI * 0.4F);

        boolean sleeping = entity.hasPose(Pose.SLEEPING);
        Direction bed = sleeping ? entity.getBedOrientation() : null;
        boolean upsideDown = !sleeping && LivingEntityRenderer.isEntityUpsideDown(entity);
        float eyeOffset = bed == null ? 0 : entity.getEyeHeight(Pose.STANDING) - 0.1F;
        return modelPose(bodyYaw, sleeping, bed, eyeOffset, upsideDown, upsideDown ? entity.getBbHeight() + 0.1F : 0,
            passenger);
    }

    static ModelPose modelPose(float bodyYaw, boolean sleeping, @Nullable Direction bed, float eyeOffset,
        boolean upsideDown, float flipHeight, @Nullable PassengerTransform passenger) {
        float x = 0;
        float z = 0;
        Quaternionf rotation;
        if (sleeping) {
            float bedYaw = bodyYaw;
            if (bed != null) {
                x = -bed.getStepX() * eyeOffset;
                z = -bed.getStepZ() * eyeOffset;
                bedYaw = switch (bed) {
                    case SOUTH -> 90.0F;
                    case NORTH -> 270.0F;
                    case EAST -> 180.0F;
                    default -> 0.0F;
                };
            }
            rotation = Axis.YP.rotationDegrees(bedYaw).mul(Axis.ZP.rotationDegrees(90.0F))
                .mul(Axis.YP.rotationDegrees(270.0F));
        } else {
            rotation = Axis.YP.rotationDegrees(180.0F - bodyYaw);
            if (upsideDown)
                rotation.mul(Axis.ZP.rotationDegrees(180.0F));
        }
        if (passenger != null && (passenger.pitch() != 0 || passenger.roll() != 0))
            rotation.mul(new Quaternionf().rotateZYX(passenger.roll(), 0, passenger.pitch()).invert());
        return new ModelPose(x, flipHeight, z, rotation);
    }

    record ModelPose(float x, float y, float z, Quaternionf rotation) {
        Vec3 worldPosition(Vec3 entityWorldPos, double baseYOffset) {
            var lift = rotation.transform(0, MODEL_LIFT, 0, new Vector3f());
            return entityWorldPos.add(x + lift.x, baseYOffset + y + lift.y, z + lift.z);
        }

        void applyTo(PoseStack stack) {
            stack.translate(x, y, z);
            stack.mulPose(rotation);
            stack.translate(0, MODEL_LIFT, 0);
        }
    }
}
