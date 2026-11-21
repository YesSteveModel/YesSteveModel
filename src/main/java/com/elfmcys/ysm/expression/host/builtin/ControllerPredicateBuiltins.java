package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.animation.condition.InnerClassify;
import com.elfmcys.ysm.client.compat.parcool.ParCoolCompat;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.expression.host.SubjectSample;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.util.EquipmentUtil;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.StringUtils;

@BuiltinComponent("ctrl")
public final class ControllerPredicateBuiltins implements EntityComponent<Entity> {
    private static final float MIN_CONTROL_SPEED = 0.05f;

    private final AnimatableEntity<?> owner;
    private final Entity subject;
    private final SubjectSample.Draft sample;

    public ControllerPredicateBuiltins(AnimatableEntity<Entity> owner) {
        this.owner = owner;
        subject = owner.getEntity();
        sample = owner.subjectSampleDraft();
    }

    @Override
    public void beginEval(AnimationEvent<?> event) {
        writeControl(event.getPartialTick());
    }

    @Override
    public void update(float previousTime, float currentTime, float partialTicks) {
        writeControl(partialTicks);
    }

    private void writeControl(float partialTicks) {
        sample.control(subject instanceof LivingEntity living ? classify(living, partialTicks) : null);
    }

    @BuiltinFunction("attacked")
    public static Boolean attacked(MolangContextView<Entity> context) {
        return control(context, "attacked");
    }

    @BuiltinFunction("death")
    public static Boolean death(MolangContextView<Entity> context) {
        return control(context, "death");
    }

    @BuiltinFunction("riptide")
    public static Boolean riptide(MolangContextView<Entity> context) {
        return control(context, "riptide");
    }

    @BuiltinFunction("climb")
    public static Boolean climb(MolangContextView<Entity> context) {
        return control(context, "climb");
    }

    @BuiltinFunction("climbing")
    public static Boolean climbing(MolangContextView<Entity> context) {
        return control(context, "climbing");
    }

    @BuiltinFunction("ride")
    public static boolean ride(MolangContextView<LivingEntity> context, int argc, String type, String filter,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean unused) {
        Entity subject = context.subject();
        Entity target = switch (type) {
            case "vehicle" -> subject.getVehicle();
            case "passenger" -> subject.getFirstPassenger();
            default -> null;
        };
        if (target == null || !target.isAlive() || filter.length() < 2)
            return false;
        if (filter.charAt(0) == '$') {
            var id = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
            return id != null && filter.substring(1).equals(id.toString());
        }
        var id = ResourceLocation.tryParse(filter.substring(1));
        if (id == null)
            return false;
        var tags = ForgeRegistries.ENTITY_TYPES.tags();
        return filter.charAt(0) == '#' && tags != null && target.getType().is(tags.createTagKey(id));
    }

    @BuiltinFunction("elytra_fly")
    public static Boolean elytraFly(MolangContextView<Entity> context) {
        return control(context, "elytra_fly");
    }

    @BuiltinFunction("fly")
    public static Boolean fly(MolangContextView<Entity> context) {
        return control(context, "fly");
    }

    @BuiltinFunction("idle")
    public static Boolean idle(MolangContextView<Entity> context) {
        return control(context, "idle");
    }

    @BuiltinFunction("jump")
    public static Boolean jump(MolangContextView<Entity> context) {
        return control(context, "jump");
    }

    @BuiltinFunction("ladder_down")
    public static Boolean ladderDown(MolangContextView<Entity> context) {
        return control(context, "ladder_down");
    }

    @BuiltinFunction("ladder_stillness")
    public static Boolean ladderStillness(MolangContextView<Entity> context) {
        return control(context, "ladder_stillness");
    }

    @BuiltinFunction("ladder_up")
    public static Boolean ladderUp(MolangContextView<Entity> context) {
        return control(context, "ladder_up");
    }

    @BuiltinFunction("run")
    public static Boolean run(MolangContextView<Entity> context) {
        return control(context, "run");
    }

    @BuiltinFunction("sleep")
    public static Boolean sleep(MolangContextView<Entity> context) {
        return control(context, "sleep");
    }

    @BuiltinFunction("sneak")
    public static Boolean sneak(MolangContextView<Entity> context) {
        return control(context, "sneak");
    }

    @BuiltinFunction("sneaking")
    public static Boolean sneaking(MolangContextView<Entity> context) {
        return control(context, "sneaking");
    }

    @BuiltinFunction("swim")
    public static Boolean swim(MolangContextView<Entity> context) {
        return control(context, "swim");
    }

    @BuiltinFunction("swim_stand")
    public static Boolean swimStand(MolangContextView<Entity> context) {
        return control(context, "swim_stand");
    }

    @BuiltinFunction("walk")
    public static Boolean walk(MolangContextView<Entity> context) {
        return control(context, "walk");
    }

    @BuiltinFunction("armor")
    public static Boolean armor(MolangContextView<Entity> context, int argc, String slot, String filter,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean useTag) {
        return itemCheck(context, slot, filter, ItemCheck.ARMOR);
    }

    @BuiltinFunction("hold")
    public static Boolean hold(MolangContextView<Entity> context, int argc, String slot, String filter,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean useTag) {
        return itemCheck(context, slot, filter, ItemCheck.HOLD);
    }

    @BuiltinFunction("swing")
    public static Boolean swing(MolangContextView<Entity> context, int argc, String slot, String filter,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean useTag) {
        return itemCheck(context, slot, filter, ItemCheck.SWING);
    }

    @BuiltinFunction("use")
    public static Boolean use(MolangContextView<Entity> context, int argc, String slot, String filter,
        @com.elfmcys.ysm.api.annotation.DefaultValue("false") boolean useTag) {
        return itemCheck(context, slot, filter, ItemCheck.USE);
    }

    private static Boolean control(MolangContextView<Entity> context, String name) {
        var selected = context.subjectSample().selectedControl();
        return selected == null ? null : name.equals(selected);
    }

    private String classify(LivingEntity entity, float partialTick) {
        var animatable = owner;
        String selected = "";
        if (!(animatable instanceof IPreviewEntity) && !hasParcoolAnimation(entity)
            && (entity.getVehicle() == null || !entity.getVehicle().isAlive())) {
            if (entity.isDeadOrDying())
                selected = "death";
            else if (entity.isAutoSpinAttack())
                selected = "riptide";
            else if (entity.getPose() == Pose.SLEEPING)
                selected = "sleep";
            else if (entity.isSwimming())
                selected = "swim";
            else if (entity.getPose() == Pose.SWIMMING && isMoving(partialTick, entity)) {
                selected = "climb";
            } else if (entity.getPose() == Pose.SWIMMING)
                selected = "climbing";
            else if (entity.onClimbable() && verticalEntitySpeed(entity) > 0) {
                selected = "ladder_up";
            } else if (entity.onClimbable() && verticalEntitySpeed(entity) == 0) {
                selected = "ladder_stillness";
            } else if (entity.onClimbable())
                selected = "ladder_down";
            else if (isFlying(animatable, entity))
                selected = "fly";
            else if (entity.getPose() == Pose.FALL_FLYING && entity.isFallFlying()) {
                selected = "elytra_fly";
            } else if (entity.isInWater() && !entity.onGround())
                selected = "swim_stand";
            else if (entity.hurtTime > 0)
                selected = "attacked";
            else if (!entity.onGround() && !entity.isInWater())
                selected = "jump";
            else if (entity.onGround() && entity.getPose() == Pose.CROUCHING && isMoving(partialTick, entity))
                selected = "sneak";
            else if (entity.onGround() && entity.getPose() == Pose.CROUCHING) {
                selected = "sneaking";
            } else if (entity.onGround() && entity.isSprinting())
                selected = "run";
            else if (entity.onGround() && isMoving(partialTick, entity))
                selected = "walk";
            else
                selected = "idle";
        }
        return selected;
    }

    private static boolean isFlying(AnimatableEntity<?> animatable, LivingEntity entity) {
        if (animatable instanceof PlayerAnimatableCapability capability && !capability.isLocalPlayer()) {
            return capability.getStateTracker().isFlying();
        }
        return entity instanceof Player player && player.getAbilities().flying;
    }

    private static boolean hasParcoolAnimation(LivingEntity entity) {
        return entity instanceof Player player && ParCoolCompat.hasAnimation(player);
    }

    private static float verticalEntitySpeed(LivingEntity entity) {
        return 20 * (float)(entity.position().y - entity.yo);
    }

    private static boolean isMoving(float partialTick, LivingEntity entity) {
        return Math.abs(entity.walkAnimation.speed(partialTick)) > MIN_CONTROL_SPEED;
    }

    private static Boolean itemCheck(MolangContextView<Entity> context, String slotName, String input,
        ItemCheck operation) {

        if (!(context.subject() instanceof LivingEntity living)) {
            return null;
        }
        var slot = LivingBuiltins.slot(slotName);
        if (slot == null || operation == ItemCheck.ARMOR != slot.isArmor()) {
            return null;
        }
        if (StringUtils.isBlank(input)) {
            return false;
        }
        if (operation == ItemCheck.SWING && (!living.swinging || living.isSleeping())
            || operation == ItemCheck.USE && (!living.isUsingItem() || living.isSleeping())) {
            return false;
        }
        var tagId = input.startsWith("#") ? ResourceLocation.tryParse(input.substring(1)) : null;
        if (input.startsWith("#") && tagId == null)
            return null;
        var item = EquipmentUtil.getEquippedItem(living, slot);
        if (item.isEmpty() && input.equals("empty")) {
            return true;
        }
        if (input.length() < 2) {
            return false;
        }
        var value = input.substring(1);
        if (input.startsWith("$")) {
            var id = ForgeRegistries.ITEMS.getKey(item.getItem());
            return id != null && value.equals(id.toString());
        }
        if (input.startsWith("#")) {
            var tags = ForgeRegistries.ITEMS.tags();
            return tags != null && item.is(tags.createTagKey(tagId));
        }
        if (operation != ItemCheck.ARMOR && input.startsWith(":")) {
            var classify = InnerClassify.getClassify(item);
            return StringUtils.isNotBlank(classify) && classify.equals(value)
                || item.getUseAnimation().name().toLowerCase(Locale.ENGLISH).equals(value);
        }
        return false;
    }

    private enum ItemCheck {
        ARMOR, HOLD, SWING, USE
    }
}
