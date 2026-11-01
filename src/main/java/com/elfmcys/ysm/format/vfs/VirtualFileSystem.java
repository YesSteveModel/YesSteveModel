package com.elfmcys.ysm.format.vfs;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.annotation.Borrowed;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Raw-source access borrowed by parsing and capture.
 *
 * <p>Execution: callers serialize access to a source and keep its owner open. A listing is an
 * observation, not a guarantee that a later read succeeds or sees the same bytes.
 *
 * <p>Ownership: file buffers are borrowed, not independently owned. Without a stronger implementation
 * guarantee, consume or copy a buffer before the next source read and before source close.
 *
 * <p>Seam: supplies inputs for [raw capture](docs/architecture/asset-pipeline/overview.md#冻结输入与单向转换); capture establishes its own retained parse closure.
 */
public interface VirtualFileSystem {
    /** Lists immediate file names; null denotes the root directory. */
    @NotNull
    String[] listFiles(@Nullable String path);

    /** Lists immediate child directory names; null denotes the root directory. */
    @NotNull
    String[] listDirectories(@Nullable String path);

    @NotNull
    default String[] listFiles() {
        return listFiles(null);
    }

    @NotNull
    default String[] listDirectories() {
        return listDirectories(null);
    }

    boolean hasFile(String fileName);

    default VirtualFileSystem directoryView(String dir) {
        return new DirectoryView(this, dir);
    }

    /** Returns borrowed bytes, or null for an absent file; access and decoding failures are not absence. */
    @Borrowed
    @Nullable
    NativeBuffer getFile(String fileName);

    // 有个格式拿不到解压后的 size，所以没有这个接口
}
