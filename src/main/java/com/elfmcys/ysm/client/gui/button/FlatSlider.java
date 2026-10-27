package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.widget.ForgeSlider;

import java.text.DecimalFormat;

@SuppressWarnings("removal")
public class FlatSlider extends ForgeSlider implements IConfigFormsButton {
    private static final ResourceLocation BUTTON_TEXTURE =
        new ResourceLocation(YesSteveModel.MOD_ID, "texture/roulette.png");
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.##");
    private final AnimatableEntity<?> animatableEntity;
    private final long expressionGeneration;
    private final ConfigActionRoute route;

    public FlatSlider(int x, int y, Component prefix, double currentValue, AnimatableEntity<?> animatableEntity,
        long expressionGeneration, ConfigActionRoute route, double step, double min, double max) {
        super(x, y, 115, 15, prefix, Component.empty(), min, max, currentValue, step, 0, true);
        this.animatableEntity = animatableEntity;
        this.expressionGeneration = expressionGeneration;
        this.route = route;
    }

    @Override
    protected void applyValue() {
        var value = (float)getValue();
        try {
            ClientProtocolGateway.submitConfigFloat(animatableEntity, expressionGeneration, route, value);
        } catch (RuntimeException exception) {
            YesSteveModel.LOGGER.error(exception);
        }
    }

    @Override
    public String getValueString() {
        return DECIMAL_FORMAT.format(this.getValue());
    }

    public void setDisplayedValue(double value) {
        // Projection refresh must not write the observed value back to Molang.
        this.setValue(value);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        final Minecraft mc = Minecraft.getInstance();
        guiGraphics.blitWithBorder(BUTTON_TEXTURE, this.getX(), this.getY(), 0, getTextureY() + 24, this.width,
            this.height, 200, 15, 2, 3, 2, 2);
        guiGraphics.blitWithBorder(BUTTON_TEXTURE, this.getX() + (int)(this.value * (double)(this.width - 8)),
            this.getY(), 0, getHandleTextureY() + 24, 8, this.height, 200, 15, 2, 3, 2, 2);
        renderScrollingString(guiGraphics, mc.font, 2, getFGColor() | Mth.ceil(this.alpha * 255.0F) << 24);
    }
}
