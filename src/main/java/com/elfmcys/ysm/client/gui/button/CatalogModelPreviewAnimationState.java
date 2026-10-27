package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.client.animation.AnimationRegister;
import com.elfmcys.ysm.client.gui.PreviewAnimationInfo;

import java.util.Objects;
import java.util.function.DoubleSupplier;

final class CatalogModelPreviewAnimationState {
    private static final DoubleSupplier NO_FADEOUT = () -> 0;

    private String previewAnimation = AnimationRegister.IDLE;
    private String hoverAnimation = AnimationRegister.EMPTY;
    private String hoverFadeoutAnimation = AnimationRegister.EMPTY;
    private String focusAnimation = AnimationRegister.EMPTY;
    private DoubleSupplier hoverFadeoutMillis = NO_FADEOUT;
    private long lastHoverTime = -1;
    private double activeFadeoutMillis;
    private boolean wasHovered;

    void configure(String previewAnimation, boolean hasHover, boolean hasHoverFadeout,
        DoubleSupplier hoverFadeoutMillis, boolean hasFocus) {
        this.previewAnimation =
            previewAnimation == null || previewAnimation.isEmpty() ? AnimationRegister.IDLE : previewAnimation;
        this.hoverAnimation = hasHover ? AnimationRegister.HOVER : AnimationRegister.EMPTY;
        this.hoverFadeoutAnimation = hasHoverFadeout ? AnimationRegister.HOVER_FADEOUT : AnimationRegister.EMPTY;
        this.focusAnimation = hasFocus ? AnimationRegister.FOCUS : AnimationRegister.EMPTY;
        this.hoverFadeoutMillis =
            hasHoverFadeout ? Objects.requireNonNull(hoverFadeoutMillis, "hoverFadeoutMillis") : NO_FADEOUT;
        resetInteraction();
    }

    void apply(PreviewAnimationInfo target, boolean hovered, boolean focused, long now) {
        if (!target.hasPreview(previewAnimation)) {
            target.setPreview(previewAnimation);
        }
        if (hovered) {
            lastHoverTime = now;
            wasHovered = true;
            target.setHover(hoverAnimation);
        } else {
            if (wasHovered) {
                activeFadeoutMillis = Math.max(0, hoverFadeoutMillis.getAsDouble());
                wasHovered = false;
            }
            if (lastHoverTime >= 0 && now - lastHoverTime < activeFadeoutMillis) {
                target.setHover(hoverFadeoutAnimation);
            } else {
                target.setHover(AnimationRegister.EMPTY);
            }
        }
        target.setFocus(focused ? focusAnimation : AnimationRegister.EMPTY);
    }

    void reset() {
        previewAnimation = AnimationRegister.IDLE;
        hoverAnimation = AnimationRegister.EMPTY;
        hoverFadeoutAnimation = AnimationRegister.EMPTY;
        focusAnimation = AnimationRegister.EMPTY;
        hoverFadeoutMillis = NO_FADEOUT;
        resetInteraction();
    }

    private void resetInteraction() {
        lastHoverTime = -1;
        activeFadeoutMillis = 0;
        wasHovered = false;
    }
}
