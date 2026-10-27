package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.gui.button.ConfigCheckBox;
import com.elfmcys.ysm.client.gui.button.FlatColorButton;
import com.elfmcys.ysm.client.gui.button.PositionButton;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.config.ExtraPlayerScreenConfig;
import com.elfmcys.ysm.config.LoadingStateScreenConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.widget.ForgeSlider;
import org.jetbrains.annotations.Nullable;

public class ConfigScreen extends ModelScreen {
    @Nullable
    private final PlayerModelScreen parent;

    public ConfigScreen(@Nullable PlayerModelScreen parent) {
        super(Component.literal("YSM Config GUI"), parent);
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = (width - 420) / 2;
        int y = (height - 265) / 2;

        addRenderableWidget(new FlatColorButton(x + 5, y + 2, 80, 18,
            Component.translatable("gui.yes_steve_model.model.return"), (b) -> showScreen(parent)));

        addRenderableWidget(
            new ForgeSlider(x + 5, y + 24, 320, 18, Component.translatable("gui.yes_steve_model.config.sound_volume"),
                Component.literal("%"), 0, 100, ClientConfig.SOUND_VOLUME.get(), true) {
                @Override
                protected void applyValue() {
                    ClientConfig.SOUND_VOLUME.set(this.getValue());
                }
            });

        addRenderableWidget(new ConfigCheckBox(x + 5, y + 45, "disable_self_model", ClientConfig.DISABLE_SELF_MODEL));
        addRenderableWidget(new ConfigCheckBox(x + 5, y + 67, "disable_other_model", ClientConfig.DISABLE_OTHER_MODEL));
        addRenderableWidget(new ConfigCheckBox(x + 5, y + 89, "print_animation_roulette_msg",
            ClientConfig.PRINT_ANIMATION_ROULETTE_MSG));
        addRenderableWidget(new ConfigCheckBox(x + 5, y + 111, "disable_self_hands", ClientConfig.DISABLE_SELF_HANDS));
        addRenderableWidget(
            new ConfigCheckBox(x + 5, y + 133, "disable_player_render", ExtraPlayerScreenConfig.DISABLE_PLAYER_RENDER));
        addRenderableWidget(
            new ConfigCheckBox(x + 5, y + 155, "disable_projectile_model", ClientConfig.DISABLE_PROJECTILE_MODEL));
        addRenderableWidget(
            new ConfigCheckBox(x + 5, y + 177, "disable_vehicle_model", ClientConfig.DISABLE_VEHICLE_MODEL));
        addRenderableWidget(new ConfigCheckBox(x + 5, y + 199, "disable_external_first_person_anim",
            ClientConfig.DISABLE_EXTERNAL_FIRST_PERSON_ANIM));
        addRenderableWidget(new ConfigCheckBox(x + 5, y + 221, "disable_loading_state_screen",
            LoadingStateScreenConfig.DISABLE_LOADING_STATE_SCREEN));
        addRenderableWidget(
            new ConfigCheckBox(x + 5, y + 243, "use_compatibility_renderer", ClientConfig.USE_COMPATIBILITY_RENDERER));

        addRenderableWidget(new PositionButton(x + 5, y + 264));
    }

    @Override
    public void render(GuiGraphics graphics, int pMouseX, int pMouseY, float pPartialTick) {
        renderBackground(graphics);
        super.render(graphics, pMouseX, pMouseY, pPartialTick);
    }
}
