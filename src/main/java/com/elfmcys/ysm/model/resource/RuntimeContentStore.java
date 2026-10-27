package com.elfmcys.ysm.model.resource;

import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.storage.ManagedContainer;

import java.io.IOException;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** Owns exact local-source read instances and commits their failure facts on its tick. */
public final class RuntimeContentStore {
    private final WeakHashMap<ModelContent, ExactContent> current = new WeakHashMap<>();
    private final ConcurrentLinkedQueue<CorruptionReport> reports = new ConcurrentLinkedQueue<>();
    private final Consumer<IOException> corruptionReporter;

    public RuntimeContentStore(Consumer<IOException> corruptionReporter) {
        this.corruptionReporter = Objects.requireNonNull(corruptionReporter, "corruptionReporter");
    }

    public synchronized ModelContent exact(ModelContent content) {
        Objects.requireNonNull(content, "content");
        if (!(content instanceof ManagedContainer)) {
            return content;
        }
        return current.computeIfAbsent(content, value -> new ExactContent(value, reports));
    }

    /** Commits reports without changing any catalog, completed resource, or source path. */
    public void tick() {
        for (CorruptionReport report; (report = reports.poll()) != null;) {
            if (report.content.markCorrupted()) {
                corruptionReporter.accept(report.failure);
            }
        }
    }

    private static final class ExactContent implements ModelContent {
        private final ModelRepresentation representation;
        private final ContentStorageBinding storageBinding;
        private final ChunkDataSource chunks;
        private volatile boolean corrupted;

        private ExactContent(ModelContent source, ConcurrentLinkedQueue<CorruptionReport> reports) {
            storageBinding = source.storageBinding();
            representation = source.representation();
            chunks = new ExactChunks(this, source.chunks(), reports);
        }

        @Override
        public ModelRepresentation representation() {
            return representation;
        }

        @Override
        public ChunkDataSource chunks() {
            return chunks;
        }

        @Override
        public ContentStorageBinding storageBinding() {
            return storageBinding;
        }

        private synchronized void beginRead() throws AssetLoadException {
            if (corrupted) {
                throw AssetLoadException.content("The exact model source instance is corrupted");
            }
        }

        private synchronized boolean markCorrupted() {
            if (corrupted) {
                return false;
            }
            corrupted = true;
            return true;
        }
    }

    private record CorruptionReport(ExactContent content, IOException failure) {
    }

    private static class ExactChunks implements ChunkDataSource {
        private final ExactContent content;
        private final ChunkDataSource source;
        private final ConcurrentLinkedQueue<CorruptionReport> reports;

        private ExactChunks(ExactContent content, ChunkDataSource source,
            ConcurrentLinkedQueue<CorruptionReport> reports) {
            this.content = content;
            this.source = source;
            this.reports = reports;
        }

        @Override
        public ReadPhase openReadPhase() throws IOException {
            content.beginRead();
            ReadPhase phase = null;
            try {
                phase = source.openReadPhase();
                return new ExactReadPhase(content, phase, reports);
            } catch (IOException | RuntimeException | Error failure) {
                if (phase != null) {
                    try {
                        phase.close();
                    } catch (IOException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                if (failure instanceof IOException io)
                    reportContentFailure(io);
                throw failure;
            }
        }

        @Override
        public java.util.Map<String, UniBuffer> readPayloads(java.util.function.BooleanSupplier cancelled,
            java.util.Collection<AssetContainerView.ChunkInfo> chunks, BufferType type) throws IOException {
            content.beginRead();
            try {
                return source.readPayloads(cancelled, chunks, type);
            } catch (IOException failure) {
                reportContentFailure(failure);
                throw failure;
            }
        }

        @Override
        public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
            content.beginRead();
            try {
                return source.readPayload(chunk, bufferType);
            } catch (IOException failure) {
                reportContentFailure(failure);
                throw failure;
            }
        }

        @Override
        public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
            throws IOException {
            content.beginRead();
            try {
                return source.readStoredVerified(chunk, bufferType);
            } catch (IOException failure) {
                reportContentFailure(failure);
                throw failure;
            }
        }

        private void reportContentFailure(IOException failure) {
            if (failure instanceof AssetLoadException asset && asset.reason() == AssetLoadException.Reason.CONTENT) {
                reports.add(new CorruptionReport(content, failure));
            }
        }
    }

    private static final class ExactReadPhase extends ExactChunks implements ChunkDataSource.ReadPhase {
        private final ChunkDataSource.ReadPhase owner;

        private ExactReadPhase(ExactContent content, ChunkDataSource.ReadPhase source,
            ConcurrentLinkedQueue<CorruptionReport> reports) {
            super(content, source, reports);
            owner = source;
        }

        @Override
        public void close() throws IOException {
            owner.close();
        }
    }
}
