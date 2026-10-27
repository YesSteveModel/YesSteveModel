package com.elfmcys.ysm.model.resource.client.render;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.compatibility.AbiVersion;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BakedAnimationCacheTest {
    @Test
    void allowsSameAnimationNameInDifferentAnimationSets() {
        var main = animationFile("main");
        var firstPerson = animationFile("fp_arm");

        assertEquals("parallel0", assertDoesNotThrow(() -> BakedAnimationCache.flatten(List.of(main))).get(0).name());
        assertEquals("parallel0",
            assertDoesNotThrow(() -> BakedAnimationCache.flatten(List.of(firstPerson))).get(0).name());
    }

    @Test
    void duplicateNamesUseTheLastAnimationInSourceOrder() throws Exception {
        assertEquals(1, BakedAnimationCache.flatten(List.of(animationFile("main"), animationFile("extra"))).size());
    }

    @Test
    void usesUnstableBakeCacheSuffixes() {
        assertEquals(".geo.ysm-cache", BakedModelCache.CACHE_SUFFIX);
        assertEquals(".anim.ysm-cache", BakedAnimationCache.CACHE_SUFFIX);
        assertEquals("geometry-" + AbiVersion.CACHE_GEOMETRY, BakedModelCache.CACHE_ABI);
        assertEquals("animation-" + AbiVersion.CACHE_ANIMATION + "/" + dev.semaweave.EngineAbi.current().packageName(),
            BakedAnimationCache.CACHE_ABI);
    }

    @Test
    void preservesSourceOrder() throws Exception {
        assertEquals(List.of("walk", "idle", "jump"),
            BakedAnimationCache
                .flatten(List.of(animationFile("first", "walk", "idle"), animationFile("second", "jump"))).stream()
                .map(AnimationProto::name).toList());
    }

    @Test
    void geometryBakeHashAndPathIncludeContainerResourceTextureAndOptions(@TempDir Path temp) {
        var containerId = container(1);
        var textureHash = hash(2).bytes();
        var base = BakedModelCache.bakeHash(containerId, "player/main", textureHash);

        assertEquals(base, BakedModelCache.bakeHash(containerId, "player/main", textureHash));
        assertNotEquals(base, BakedModelCache.bakeHash(container(3), "player/main", textureHash));
        assertNotEquals(base, BakedModelCache.bakeHash(containerId, "player/arm", textureHash));
        assertNotEquals(base, BakedModelCache.bakeHash(containerId, "player/main", hash(4).bytes()));

        var cacheRoot = temp.resolve("cache");
        var cache = new BakedModelCache(cacheRoot, track(new AtomicSharedCache(cacheRoot)));
        assertEquals(containerId.toString(), cache.path(containerId, base).getParent().getFileName().toString());
        assertNotEquals(cache.path(containerId, base),
            cache.path(container(3), BakedModelCache.bakeHash(container(3), "player/main", textureHash)));
    }

    private static Map.Entry<String, AnimationFileProto> animationFile(String key) {
        return animationFile(key, "parallel0");
    }

    private static Map.Entry<String, AnimationFileProto> animationFile(String key, String... names) {
        var file = AnimationFileProto.newBuilder();
        for (var name : names) {
            file.addAnimations(
                AnimationProto.newBuilder().setName(name).setLength(0).setLoop(LoopType.PLAY_ONCE).build());
        }
        return Map.entry(key, file.build());
    }

    private static Hash256 hash(int value) {
        var bytes = new byte[Hash256.SIZE];
        Arrays.fill(bytes, (byte)value);
        return new Hash256(bytes);
    }

    private static Hash256 container(int value) {
        return hash(value);
    }
}
