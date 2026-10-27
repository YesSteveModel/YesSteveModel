package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlatSliderTest {
    @Test
    void displayedValueDoesNotInvokeMolangWriteback() {
        var slider = new FlatSlider(0, 0, Component.empty(), 0, null, 1, new ConfigActionRoute("mode", 0), 1, 0, 2);

        slider.setDisplayedValue(2);

        assertEquals(2, slider.getValue());
    }
}
