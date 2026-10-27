package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.client.animation.AnimationRegister;
import com.elfmcys.ysm.client.gui.PreviewAnimationInfo;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatalogModelPreviewAnimationStateTest {
    @Test
    void appliesPreviewHoverFadeoutAndFocusAnimations() {
        var fadeoutLoads = new AtomicInteger();
        var state = new CatalogModelPreviewAnimationState();
        var target = new PreviewAnimationInfo();
        state.configure("custom_preview", true, true, () -> {
            fadeoutLoads.incrementAndGet();
            return 150;
        }, true);

        state.apply(target, true, true, 100);
        assertEquals("custom_preview", target.getPreview());
        assertEquals(AnimationRegister.HOVER, target.getHover());
        assertEquals(AnimationRegister.FOCUS, target.getFocus());
        assertEquals(0, fadeoutLoads.get());

        state.apply(target, false, false, 120);
        assertEquals(AnimationRegister.HOVER_FADEOUT, target.getHover());
        assertEquals(AnimationRegister.EMPTY, target.getFocus());
        assertEquals(1, fadeoutLoads.get());

        state.apply(target, false, false, 249);
        assertEquals(AnimationRegister.HOVER_FADEOUT, target.getHover());
        assertEquals(1, fadeoutLoads.get());

        state.apply(target, false, false, 250);
        assertEquals(AnimationRegister.EMPTY, target.getHover());
    }

    @Test
    void fallsBackToIdleAndClearsMissingSpecialAnimations() {
        var state = new CatalogModelPreviewAnimationState();
        var target = new PreviewAnimationInfo();
        target.setPreview("stale");
        target.setHover("stale");
        target.setFocus("stale");
        state.configure("", false, false, () -> 100, false);

        state.apply(target, true, true, 100);

        assertEquals(AnimationRegister.IDLE, target.getPreview());
        assertEquals(AnimationRegister.EMPTY, target.getHover());
        assertEquals(AnimationRegister.EMPTY, target.getFocus());
    }

    @Test
    void reappliesPreviewAfterEntityStateIsReset() {
        var state = new CatalogModelPreviewAnimationState();
        var target = new PreviewAnimationInfo();
        state.configure("custom_preview", false, false, () -> 0, false);
        state.apply(target, false, false, 100);
        target.setPreview("");

        state.apply(target, false, false, 101);

        assertEquals("custom_preview", target.getPreview());
    }
}
