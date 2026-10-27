package com.elfmcys.ysm.client.texture;

import java.util.Optional;

final class TextureRegistrationState<I> {
    private final I id;
    private Token current;
    private boolean active;
    private boolean registered;
    private boolean releasePending;
    private int delayTicks;
    private int remainingRemovalTicks;

    TextureRegistrationState(I id) {
        this.id = id;
    }

    I id() {
        return id;
    }

    Activation activate(int delayTicks) {
        current = new Token();
        active = true;
        releasePending = false;
        this.delayTicks = delayTicks;
        remainingRemovalTicks = 0;
        return new Activation(current, registered);
    }

    boolean isActive(Token token) {
        return active && current == token;
    }

    boolean markRegistered(Token token) {
        if (!isActive(token) || registered) {
            return false;
        }
        registered = true;
        return true;
    }

    ReleaseResult releaseCurrent() {
        return release(current);
    }

    ReleaseResult release(Token token) {
        if (!isActive(token)) {
            return ReleaseResult.IGNORED;
        }
        active = false;
        if (!registered) {
            return ReleaseResult.DISCARD;
        }
        remainingRemovalTicks = delayTicks;
        return ReleaseResult.DELAYED;
    }

    Optional<Token> tickRemoval() {
        if (active || !registered || releasePending) {
            return Optional.empty();
        }
        if (remainingRemovalTicks > 0) {
            remainingRemovalTicks--;
            return Optional.empty();
        }
        releasePending = true;
        return Optional.of(current);
    }

    boolean shouldRelease(Token token) {
        return !active && registered && releasePending && current == token;
    }

    boolean markReleased(Token token) {
        if (!shouldRelease(token)) {
            return false;
        }
        registered = false;
        releasePending = false;
        current = null;
        return true;
    }

    enum ReleaseResult {
        IGNORED, DISCARD, DELAYED
    }

    record Activation(Token token, boolean registered) {
    }

    static final class Token {
        private Token() {}
    }
}
