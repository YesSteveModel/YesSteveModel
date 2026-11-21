package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

@BuiltinComponent("ysm")
public final class RenderBuiltins implements EntityComponent<Entity> {
    private final AnimatableEntity<?> owner;
    private AnimationEvent<?> event;

    public RenderBuiltins(AnimatableEntity<Entity> owner) {
        this.owner = owner;
    }

    @Override
    public void beginEval(AnimationEvent<?> event) {
        this.event = event;
    }

    @Override
    public void endEval() {
        event = null;
    }

    static int personView(Entity subject, RenderContext render) {
        var minecraft = Minecraft.getInstance();
        if (subject == minecraft.player && (render.level() || render.irisShadow() || render.firstPersonMod()))
            return minecraft.options.getCameraType().ordinal();
        return CameraType.THIRD_PERSON_FRONT.ordinal();
    }

    @BuiltinFunction(value = "person_view", alwaysOrigin = true)
    public float personView(MolangContextView<Entity> context) {
        return personView(context.subject(), event.getRenderContext());
    }

    @BuiltinFunction(value = "rendering_in_paperdoll", alwaysOrigin = true)
    public boolean renderingInPaperdoll(MolangContextView<Entity> context) {
        return event.getRenderContext().paperDoll();
    }

    @BuiltinFunction(value = "rendering_in_inventory", alwaysOrigin = true)
    public boolean renderingInInventory(MolangContextView<Entity> context) {

        return com.elfmcys.ysm.util.PersonView.isInInventory(event);
    }

    @BuiltinFunction(value = "texture_name", alwaysOrigin = true)
    public String textureName(MolangContextView<Entity> context) {
        return owner instanceof com.elfmcys.ysm.client.entity.CustomHumanoidEntity<?> h ? h.getTextureName() : "";
    }

    @BuiltinFunction(value = "is_first_person", alwaysOrigin = true, namespace = "query")
    public boolean isFirstPerson(MolangContextView<Entity> context) {
        return personView(context.subject(), event.getRenderContext()) == CameraType.FIRST_PERSON.ordinal();
    }

    @BuiltinFunction(value = "rotation_to_camera", alwaysOrigin = true, namespace = "query")
    public static float rotationToCamera(MolangContextView<Entity> context, int axis) {
        if (axis < 0 || axis > 1)
            return 0;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        float result = axis == 0 ? -camera.getXRot() : 180 + camera.getYRot();
        return Float.isFinite(result) ? result : 0;
    }

    @BuiltinFunction(value = "distance_from_camera", namespace = "query")
    public static float distanceFromCamera(MolangContextView<Entity> context) {
        float result = (float)Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()
            .distanceTo(context.subject().position());
        return Float.isFinite(result) ? result : 0;
    }

    @BuiltinFunction("fps")
    public static float fps(MolangContextView<Entity> context) {

        return Minecraft.getInstance().getFps();
    }
}
