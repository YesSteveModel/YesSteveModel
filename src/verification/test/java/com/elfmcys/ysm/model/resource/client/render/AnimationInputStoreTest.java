package com.elfmcys.ysm.model.resource.client.render;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import dev.semaweave.compilation.CompilerOptions;
import dev.semaweave.program.ProgramStructuralException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnimationInputStoreTest {
    private static final Hash256 CONTAINER = hash(1);
    private static final SettingsProto SETTINGS = SettingsProto.newBuilder().build();

    @Test
    void coldMemoryAndDiskInputsRemainUsableAfterCacheReferencesAreCleared(@TempDir Path root) throws Exception {
        var shared = track(new AtomicSharedCache(root));
        var store = new AnimationInputStore(root, shared, 1);
        var request = request(CONTAINER, definition(), null, CompilerOptions.DEFAULT);
        assertFalse(store.mayHaveInputs(CONTAINER));

        var cold = store.acquire(request, false);
        assertEquals(AnimationInputStore.Source.COLD, cold.source());
        var sample = cold.animations().descriptors().stream()
            .filter(value -> value.location().role() == ProgramRole.ANIMATION_SAMPLE).findFirst().orElseThrow();
        assertEquals(sample.id(),
            cold.animations().source().load("main", "idle").blendWeight().orElseThrow().programId());
        assertEquals(request.programs().get(sample.id()), cold.animations().loadProgram(sample.id()));

        var memory = store.acquire(request, true);
        assertEquals(AnimationInputStore.Source.MEMORY, memory.source());
        assertSame(cold.animations(), memory.animations());
        assertTrue(store.mayHaveInputs(CONTAINER));
        store.awaitPersistence();
        assertEquals(1, shared.derivedWork().snapshot().succeeded());
        assertTrue(Files.isRegularFile(store.path(cold.identity())));
        assertTrue(shared.bakeIndex().find(AnimationInputStore.indexKey(cold.identity())).isPresent());

        store.clearMemory();
        var disk = store.acquire(request, true);
        assertNotNull(disk);
        assertEquals(AnimationInputStore.Source.DISK, disk.source());
        assertEquals(cold.identity(), disk.identity());
        assertNotSame(cold.animations(), disk.animations());
        store.clearMemory();
        try (var phase = disk.animations().source().openReadPhase()) {
            assertEquals("idle", phase.load("main", "idle").name());
            assertEquals(request.programs().get(sample.id()), phase.loadProgram(sample.id()));
        }
        assertEquals("idle", cold.animations().source().load("main", "idle").name());
    }

    @Test
    void uncalledInvalidFunctionInputDoesNotPreventAnimationAcquisitionOrPersistence(@TempDir Path root)
        throws Exception {
        var emptyBody = ProgramProto.newBuilder().build();
        var strings = StringDataProto.newBuilder()
            .addUserFunctions(UserFunctionProto.newBuilder().setName("broken").setBody(emptyBody).build()).build();
        var request = request(CONTAINER, definition(), strings, CompilerOptions.DEFAULT);
        var function = request.descriptors().stream()
            .filter(value -> value.location().role() == ProgramRole.USER_FUNCTION).findFirst().orElseThrow();
        var shared = track(new AtomicSharedCache(root));
        var store = new AnimationInputStore(root, shared);
        var cold = store.acquire(request, false);
        assertEquals("idle", cold.animations().source().load("main", "idle").name());
        assertEquals(emptyBody, cold.animations().loadProgram(function.id()));
        assertThrows(ProgramStructuralException.class,
            () -> EncodedProgramMapper.fromProto(cold.animations().loadProgram(function.id())));

        store.awaitPersistence();
        assertEquals(1, shared.derivedWork().snapshot().succeeded());
        store.clearMemory();
        var warm = store.acquire(request, true);
        assertEquals(AnimationInputStore.Source.DISK, warm.source());
        assertEquals("idle", warm.animations().source().load("main", "idle").name());
        assertEquals(emptyBody, warm.animations().loadProgram(function.id()));
    }

    @Test
    void receivingOptionsAndExactDefaultOriginHaveIndependentInputIdentities() throws Exception {
        var model = definition();
        var first = request(CONTAINER, model, null, CompilerOptions.DEFAULT);
        assertEquals(first.identity(), request(CONTAINER, model, null, CompilerOptions.DEFAULT).identity());
        assertNotEquals(first.identity(), request(CONTAINER, model, null, new CompilerOptions(true)).identity());
        var body = program("7");
        var local = functions("common", body);
        var defaultA = functions("fallback/" + "a".repeat(64) + "/player/main/common", body);
        var defaultB = functions("fallback/" + "b".repeat(64) + "/player/main/common", body);
        var localRequest = request(CONTAINER, model, local, CompilerOptions.DEFAULT);
        var fromA = request(CONTAINER, model, defaultA, CompilerOptions.DEFAULT);
        var fromB = request(CONTAINER, model, defaultB, CompilerOptions.DEFAULT);
        assertNotEquals(localRequest.identity(), fromA.identity());
        assertNotEquals(fromA.identity(), fromB.identity());
        assertEquals(
            fromA.descriptors().stream().filter(value -> value.location().role() == ProgramRole.USER_FUNCTION)
                .findFirst().orElseThrow().contentHash(),
            fromB.descriptors().stream().filter(value -> value.location().role() == ProgramRole.USER_FUNCTION)
                .findFirst().orElseThrow().contentHash());
        assertNotEquals(fromA.descriptors(), fromB.descriptors());
    }

    @Test
    void simultaneousColdRequestsKeepIndependentInputsWhenOneRequesterCancels(@TempDir Path root) throws Exception {
        var enteredWork = new CountDownLatch(1);
        var releaseWork = new CountDownLatch(1);
        var shared = track(new AtomicSharedCache(root));
        assertNotNull(shared.derivedWork().submit(gate -> {
            enteredWork.countDown();
            await(releaseWork);
            gate.commit(() -> {
            });
        }));
        assertTrue(enteredWork.await(10, TimeUnit.SECONDS));
        var firstStore = new AnimationInputStore(root, shared);
        var secondStore = new AnimationInputStore(root, shared);
        var request = request(CONTAINER, definition(), null, CompilerOptions.DEFAULT);
        var cancelled = new AtomicBoolean();
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                await(start);
                return firstStore.acquire(request, false, cancelled::get);
            });
            var second = workers.submit(() -> {
                await(start);
                return secondStore.acquire(request, false);
            });
            start.countDown();
            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            assertEquals(AnimationInputStore.Source.COLD, firstResult.source());
            assertEquals(AnimationInputStore.Source.COLD, secondResult.source());
            assertEquals(firstResult.identity(), secondResult.identity());
            assertNotSame(firstResult.animations(), secondResult.animations());

            cancelled.set(true);
            firstStore.clearMemory();
            assertThrows(CancellationException.class, () -> firstStore.acquire(request, false, cancelled::get));
            assertEquals("idle", firstResult.animations().source().load("main", "idle").name());
            assertEquals("idle", secondResult.animations().source().load("main", "idle").name());
            releaseWork.countDown();
            shared.derivedWork().awaitIdle();
            assertEquals(3, shared.derivedWork().snapshot().succeeded());
            assertEquals(0, shared.derivedWork().snapshot().failed());
            secondStore.clearMemory();
            var persisted = secondStore.acquire(request, true);
            assertEquals(AnimationInputStore.Source.DISK, persisted.source());
            assertEquals("idle", persisted.animations().source().load("main", "idle").name());
        } finally {
            start.countDown();
            releaseWork.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedPersistencePreservesMemoryAndDoesNotPreventAnotherInputProducer(@TempDir Path root) throws Exception {
        var moves = new AtomicInteger();
        var failingCache = track(new AtomicSharedCache(root, replacement -> {
            moves.incrementAndGet();
            throw new IOException("Rejected animation input publication");
        }));
        var failingStore = new AnimationInputStore(root, failingCache);
        var request = request(CONTAINER, definition(), null, CompilerOptions.DEFAULT);
        var original = failingStore.acquire(request, false);
        failingStore.awaitPersistence();
        assertEquals(1, moves.get());
        assertEquals(1, failingCache.derivedWork().snapshot().failed());
        assertEquals(0, failingCache.derivedWork().snapshot().jobs());
        assertEquals(0, failingCache.derivedWork().snapshot().succeeded());
        assertThrows(java.nio.file.NoSuchFileException.class, () -> failingStore.path(original.identity()));
        assertSame(original.animations(), failingStore.acquire(request, true).animations());
        assertEquals("idle", original.animations().source().load("main", "idle").name());

        var otherCache = track(new AtomicSharedCache(root));
        var otherStore = new AnimationInputStore(root, otherCache);
        var rebuilt = otherStore.acquire(request, false);
        assertEquals(AnimationInputStore.Source.COLD, rebuilt.source());
        otherStore.awaitPersistence();
        assertEquals(1, otherCache.derivedWork().snapshot().succeeded());
        failingStore.clearMemory();
        var restored = failingStore.acquire(request, true);
        assertEquals(AnimationInputStore.Source.DISK, restored.source());
        assertEquals(original.identity(), restored.identity());
        assertEquals("idle", restored.animations().source().load("main", "idle").name());
        assertEquals("idle", original.animations().source().load("main", "idle").name());
    }

    @Test
    void cacheOnlyMissCreatesNoDerivedWorkOrPayloadAndDoesNotPoisonOrdinaryAcquisition(@TempDir Path root)
        throws Exception {
        var moves = new AtomicInteger();
        var shared = track(new AtomicSharedCache(root, replacement -> {
            moves.incrementAndGet();
            replacement.run();
        }));
        shared.coordinator().register();
        var before = files(root);
        var store = new AnimationInputStore(root, shared);
        var request = request(CONTAINER, definition(), null, CompilerOptions.DEFAULT);
        assertNull(store.acquire(request, true));
        assertFalse(store.mayHaveInputs(CONTAINER));
        assertEquals(before, files(root));
        assertFalse(Files.exists(root.resolve("f/b")));
        assertEquals(0, moves.get());
        assertEquals(0, shared.derivedWork().snapshot().jobs());
        assertEquals(0, shared.derivedWork().snapshot().succeeded());
        assertEquals(0, shared.derivedWork().snapshot().failed());

        var ordinary = store.acquire(request, false);
        assertEquals(AnimationInputStore.Source.COLD, ordinary.source());
        assertEquals("idle", ordinary.animations().source().load("main", "idle").name());
    }

    private static AnimationInputStore.Request request(Hash256 container, ModelDataProto definition,
        StringDataProto strings, CompilerOptions options) throws IOException {
        return AnimationInputStore.prepare(container, "player", ExpressionSet.MAIN, definition, strings, SETTINGS,
            options);
    }

    private static ModelDataProto definition() throws Exception {
        return ModelDataProto.newBuilder()
            .putAnimationFiles("main",
                AnimationFileProto.newBuilder().addAnimations(AnimationProto.newBuilder().setName("idle").setLength(20)
                    .setBlendWeight(
                        ExpressionValueProto.newBuilder().setProgram(program("query.anim_time + 1")).build())
                    .build()).build())
            .build();
    }

    private static ProgramProto program(String source) throws Exception {
        return EncodedProgramMapper.toProto(new MolangFrontend().compile(source, MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("animation-input-store-test")).program());
    }

    private static StringDataProto functions(String name, ProgramProto body) {
        return StringDataProto.newBuilder()
            .addUserFunctions(UserFunctionProto.newBuilder().setName(name).setBody(body).build()).build();
    }

    private static Hash256 hash(int value) {
        var bytes = new byte[Hash256.SIZE];
        java.util.Arrays.fill(bytes, (byte)value);
        return new Hash256(bytes);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new IOException("Timed out waiting for test release");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private static Set<Path> files(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).map(root::relativize).collect(Collectors.toSet());
        }
    }
}
