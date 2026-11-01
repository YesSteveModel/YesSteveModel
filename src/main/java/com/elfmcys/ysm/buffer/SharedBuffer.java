package com.elfmcys.ysm.buffer;

import com.elfmcys.ysm.util.CleanerUtil;
import java.lang.ref.Reference;
import java.util.Objects;

/** Shared views retain one GC owner; borrowed slices require the caller to keep that owner alive. */
public abstract sealed class SharedBuffer implements UniBuffer permits SharedNativeBuffer, SharedArrayBuffer {
    private final Owner owner;

    SharedBuffer(BufferStorage storage) {
        owner = new Owner(storage);
    }

    SharedBuffer(SharedBuffer source) {
        owner = source.owner;
    }

    final BufferStorage storage() {
        return owner.storage;
    }

    public abstract SharedBuffer ownedSlice(int offset, int size);

    public static SharedBuffer copyOf(UniBuffer source) {
        return source.type() == BufferType.NATIVE ? SharedNativeBuffer.copyOf(source)
            : SharedArrayBuffer.copyOf(source);
    }

    /** Consumes the whole allocation, including on construction or Cleaner registration failure. */
    public static SharedBuffer adopt(BufferStorage storage) {
        Objects.requireNonNull(storage);
        SharedBuffer result = null;
        try {
            if (storage instanceof NativeStorage nativeStorage)
                result = new SharedNativeBuffer(nativeStorage);
            else if (storage instanceof ArrayStorage arrayStorage)
                result = new SharedArrayBuffer(arrayStorage);
            else
                throw new IllegalArgumentException("Unsupported storage representation");
            CleanerUtil.ref(result.owner, storage, BufferStorage::close);
            return result;
        } catch (RuntimeException | Error failure) {
            releaseOnFailure(storage, failure);
            throw failure;
        } finally {
            Reference.reachabilityFence(result);
        }
    }

    private static void releaseOnFailure(BufferStorage storage, Throwable failure) {
        try {
            storage.close();
        } catch (RuntimeException | Error cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static final class Owner {
        private final BufferStorage storage;

        private Owner(BufferStorage storage) {
            this.storage = storage;
        }
    }
}
