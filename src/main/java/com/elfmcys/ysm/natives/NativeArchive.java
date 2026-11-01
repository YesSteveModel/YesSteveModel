package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.BorrowedNativeBuffer;
import com.elfmcys.ysm.buffer.annotation.Borrowed;
import com.elfmcys.ysm.format.vfs.VirtualFileSystem;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.function.Function;

// 目前支持 zip、7z、旧版 ysm
public final class NativeArchive extends NativeObject implements VirtualFileSystem {
    private static final Function<State, NativeArchive> ADOPT = NativeArchive::new;

    private NativeArchive(State state) {
        super(state);
    }

    public static NativeArchive open(String path) {
        var ptr = nCreate(path);
        if (ptr == 0) {
            throw new IllegalArgumentException("Could not open archive: " + path);
        }
        return adopt(ptr, ADOPT);
    }

    @Override
    public String[] listFiles(@Nullable String path) {
        final String[] result;
        try {
            result = nList(get(), path, 0);
        } finally {
            Reference.reachabilityFence(this);
        }
        if (result == null) {
            throw new IllegalStateException("Failed to list files");
        }
        return result;
    }

    @Override
    public String[] listDirectories(@Nullable String path) {
        final String[] result;
        try {
            result = nList(get(), path, 1);
        } finally {
            Reference.reachabilityFence(this);
        }
        if (result == null) {
            throw new IllegalStateException("Failed to list directories");
        }
        return result;
    }

    @Override
    public boolean hasFile(String fileName) {
        // 随便返回个东西
        try {
            return nGetFile(get(), fileName, true) != null;
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    @Borrowed
    @Override
    public @Nullable NativeBuffer getFile(String fileName) {
        final Object buf;
        try {
            buf = nGetFile(get(), fileName, false);
        } finally {
            Reference.reachabilityFence(this);
        }
        if (buf == null) {
            return null;
        }
        var data = (ByteBuffer)buf;
        return BorrowedNativeBuffer.of(this, data, 0, data.remaining());
    }

    private static native long nCreate(String path);

    private static native String[] nList(long ptr, @Nullable String path, int type);

    private static native Object nGetFile(long ptr, String fileName, boolean dryRun);
}
