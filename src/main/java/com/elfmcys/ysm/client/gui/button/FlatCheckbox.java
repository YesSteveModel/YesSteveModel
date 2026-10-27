package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.StateSwitchingButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.function.Consumer;

@OnlyIn(Dist.CLIENT)
public class FlatCheckbox extends StateSwitchingButton implements IConfigFormsButton {
    private static final ResourceLocation BUTTON_TEXTURE =
        new ResourceLocation(YesSteveModel.MOD_ID, "texture/roulette.png");
    private final Consumer<Boolean> onClick;
    private final Component name;

    public FlatCheckbox(int xIn, int yIn, int width, Component name, Consumer<Boolean> onClick) {
        super(xIn, yIn, width, 12, false);
        this.name = name;
        this.onClick = onClick;
        this.initTextureValues(0, 0, 128, 12, BUTTON_TEXTURE);
    }

    public FlatCheckbox(int xIn, int yIn, Component name, Consumer<Boolean> onClick) {
        this(xIn, yIn, 115, name, onClick);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.renderWidget(graphics, mouseX, mouseY, partialTicks);
        graphics.drawString(Minecraft.getInstance().font, name, this.getX() + 14, this.getY() + 2, 0xffffffff, false);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.isStateTriggered = !this.isStateTriggered;
        onClick.accept(this.isStateTriggered);
    }
}
