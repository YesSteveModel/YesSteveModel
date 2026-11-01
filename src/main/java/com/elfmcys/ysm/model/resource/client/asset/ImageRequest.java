package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.natives.image.ImageSource;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** A new use must pass authority independently of its content proof. */
public final class ImageRequest {
    private final Function<ImageSource, ImageIdentity> identity;
    private final BooleanSupplier allowed;
    private final Predicate<ImageSource> current;
    private final Supplier<CompletableFuture<PreviewResult>> acquisition;

    ImageRequest(Function<ImageSource, ImageIdentity> identity, BooleanSupplier allowed,
        Predicate<ImageSource> current, Supplier<CompletableFuture<PreviewResult>> acquisition) {
        this.identity = identity;
        this.allowed = allowed;
        this.current = current;
        this.acquisition = acquisition;
    }

    public ImageIdentity identity() {
        return identity.apply(null);
    }

    ImageIdentity identity(ImageSource source) {
        return identity.apply(source);
    }

    public boolean allowed() {
        return allowed.getAsBoolean();
    }

    boolean current(ImageSource source) {
        return allowed() && current.test(source);
    }

    CompletableFuture<PreviewResult> acquire() {
        if (!allowed())
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("Image use expired"));
        return acquisition.get();
    }
}
