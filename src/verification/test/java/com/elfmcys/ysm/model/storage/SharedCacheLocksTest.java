package com.elfmcys.ysm.model.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SharedCacheLocksTest {
    @TempDir Path temp;

    @Test
    void parentAliasesShareTheLockDomainWithoutOpeningConsumerFiles() throws Exception {
        var parent = Files.createDirectory(temp.resolve("physical"));
        var alias = Files.createSymbolicLink(temp.resolve("alias"), parent);
        var consumer = parent.resolve("absent.consumer");
        var aliased = alias.resolve("absent.consumer");
        assertTrue(SharedCacheLocks.sameLockFile(consumer, aliased));
        assertFalse(SharedCacheLocks.sameLockFile(consumer, alias.resolve("different.consumer")));
        SharedCacheLocks.registerConsumer(consumer);
        try {
            assertTrue(SharedCacheLocks.isLocalConsumer(aliased));
            assertThrows(IllegalStateException.class, () -> SharedCacheLocks.registerConsumer(aliased));
            assertFalse(Files.exists(consumer));
        } finally { SharedCacheLocks.unregisterConsumer(consumer); }
        assertFalse(SharedCacheLocks.isLocalConsumer(aliased));
    }
}
