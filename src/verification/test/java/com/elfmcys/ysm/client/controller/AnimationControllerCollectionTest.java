package com.elfmcys.ysm.client.controller;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationControllerCollectionTest {
    @Test
    void concurrentInitializationPublishesOneCompleteDiscoverySet() throws Exception {
        var collection = new AnimationControllerCollection<CustomPlayerEntity, Object>();
        var initializationEntered = new CountDownLatch(1);
        var releaseInitialization = new CountDownLatch(1);
        var initializations = new AtomicInteger();
        Runnable initializer = () -> {
            initializations.incrementAndGet();
            initializationEntered.countDown();
            try {
                assertTrue(releaseInitialization.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            collection.add((model, assets, animations) -> (animatable, consumer) -> {
            });
        };

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> collection.initialize(initializer));
            assertTrue(initializationEntered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> collection.initialize(initializer));
            assertFalse(second.isDone());
            releaseInitialization.countDown();

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, initializations.get());
            assertDoesNotThrow(
                () -> collection.build(new Object(), null, new com.elfmcys.ysm.model.resource.client.AnimationStore()));
        } finally {
            releaseInitialization.countDown();
            executor.shutdownNow();
        }
    }
}
