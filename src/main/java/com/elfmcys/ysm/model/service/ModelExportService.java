package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.model.storage.PreviewStorage;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;

import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.catalog.source.CatalogAddressResolver;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.util.Closeable;
import net.minecraft.commands.CommandSourceStack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Integrated-environment admission and effect authority for complete model exports.
 *
 * <p>Seam: implements [export identity and commit authority](docs/architecture/asset-pipeline/overview.md#导出批次与提交).
 */
public final class ModelExportService {
    private ModelExportService() {}

    public static Environment environment(CommandSourceStack source) {
        if (!source.hasPermission(2) || source.getServer().isDedicatedServer()) {
            throw new IllegalStateException("Export requires integrated server permission level 2");
        }
        var environment = ServerModelService.instance().exportEnvironment();
        environment.require(source.getServer());
        return environment;
    }

    public static CompletableFuture<Path> export(CommandSourceStack source, String path, String extra) {
        try {
            return environment(source).export(path, extra);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Claims the request's source once and reopens its exact representation on a worker.
     *
     * <p>Ensures: the result may already contain a committed path when an existing preview was usable.
     * Otherwise the caller must obtain a preview and call finish.
     *
     * <p>Ownership: the caller must close the returned PreparedExport on every exit path. Failure closes
     * resources acquired by this call and propagates; it does not make the request reusable.
     */
    public static PreparedExport prepare(Request request) throws Exception {
        request.requireActive();
        var system = ModelRuntime.system();
        return prepare(request, system.previews(), null);
    }

    static PreparedExport prepare(Request request, PreviewStorage previews, Path output) throws Exception {
        var operation = openExact(request.claimSource());
        PreviewStore.EncodedPreview preview = null;
        try {
            request.requireActive();
            preview = embedded(operation);
            if (preview == null)
                preview = previews.forContent(operation).load(operation.representation().containerId()).orElse(null);
            var prepared = new PreparedExport(operation,
                output == null ? outputPath(operation.location().value()) : output, request.extra, request, preview);
            if (preview != null)
                prepared.completed = ModelExporter.export(operation, preview, prepared.output, request.extra,
                    replacement -> request.commit(prepared.output, replacement));
            return prepared;
        } catch (Throwable failure) {
            failure = closeResource(preview, failure);
            failure = closeResource(operation.representation(), failure);
            if (failure instanceof Error error)
                throw error;
            if (failure instanceof Exception exception)
                throw exception;
            throw new RuntimeException(failure);
        }
    }

    /**
     * Commits an incomplete preparation using a borrowed generated preview. Runs synchronous I/O on a worker.
     * The caller keeps both arguments alive until return and closes them afterwards, including on failure.
     */
    public static Path finish(PreparedExport prepared, PreviewStore.EncodedPreview generated) throws IOException {
        return ModelExporter.export(prepared.source, generated, prepared.output, prepared.extra,
            replacement -> prepared.request.commit(prepared.output, replacement));
    }

    private static ManagedContainer openExact(ManagedContainer source) throws IOException {
        var opened = ManagedContainer.openIndexed(new CatalogIndexEntry(source.representation().identity(),
            source.location(), source.file(), source.storageBinding()));
        if (!opened.representation().identity().equals(source.representation().identity())) {
            opened.representation().close();
            throw new IOException("Export source representation changed");
        }
        return opened;
    }

    private static Path outputPath(String address) throws IOException {
        return outputPath(ModelRuntime.paths().exportRoot(), address);
    }

    static Path outputPath(Path root, String address) throws IOException {
        var location = CatalogLocation.parse(address);
        var directory = switch (location.root()) {
            case GLOBAL_CUSTOM -> "global/custom";
            case GLOBAL_AUTH -> "global/auth";
            case LOCAL_CUSTOM -> "local/custom";
            case LOCAL_AUTH -> "local/auth";
            case BUILTIN -> throw new IOException("Builtin models cannot be exported");
        };
        var path = directory + "/" + location.entry();
        var output = root.resolve(withDirectSuffix(path)).normalize();
        if (!output.startsWith(root))
            throw new IOException("Export path escapes its root");
        return output;
    }

    static String withDirectSuffix(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".mxc") ? path : path + ".mxc";
    }

    private static PreviewStore.EncodedPreview embedded(ManagedContainer source) throws IOException {
        var view = source.modelFile();
        var chunk = view.getFileView().getAssetView().getChunkInfo(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
        var image = view.thumbnailSource(source.chunks());
        if (image == null)
            return null;
        if (chunk == null)
            throw new IOException("Model preview chunk is unavailable");
        return PreviewStore.read(image, chunk.size());
    }

    @FunctionalInterface
    public interface ExportRunner {
        CompletableFuture<Path> export(Request request);
    }

    public record Item(String path, ModelFileIdentity identity) {
    }

    /**
     * Owns export admission and commit authority for one exact integrated-server instance.
     *
     * <p>Execution: the monitor orders admission, cancellation and replacement. Runner and notice callbacks can
     * run inline while it is held; they must not wait for work that needs the same monitor. Notice RuntimeExceptions
     * are logged and isolated. Owner checks are observations, not permits for later work; commit checks again.
     */
    public static final class Environment implements Closeable {
        private final Object owner;
        private final BooleanSupplier available;
        private final Supplier<ServerCatalog> catalog;
        private final ExportRunner runner;
        private final Set<Request> requests = new HashSet<>();
        private boolean closed;
        private int workers;
        private Batch batch;

        public Environment(Object owner, BooleanSupplier available, Supplier<ServerCatalog> catalog,
            ExportRunner runner) {
            this.owner = Objects.requireNonNull(owner);
            this.available = Objects.requireNonNull(available);
            this.catalog = Objects.requireNonNull(catalog);
            this.runner = Objects.requireNonNull(runner);
        }

        public synchronized void require(Object expectedOwner) {
            if (closed || owner != expectedOwner || !available.getAsBoolean())
                throw new IllegalStateException("Export requires the current integrated environment");
        }

        public synchronized CompletableFuture<Path> export(String path, String extra) {
            require(owner);
            var current = catalog.get();
            var resolution = current.resolvePath(path);
            if (resolution.status() != CatalogAddressResolver.Status.FOUND)
                return CompletableFuture.failedFuture(new CatalogAddressResolver.Failure(resolution.status()));
            var source = resolution.found().flatMap(current::find).orElse(null);
            if (source == null)
                return CompletableFuture
                    .failedFuture(new CatalogAddressResolver.Failure(CatalogAddressResolver.Status.NOT_FOUND));
            if (source.location().rootKind() == CatalogRootKind.BUILTIN)
                return CompletableFuture.failedFuture(new IOException("Builtin models cannot be exported"));
            return start(source.location().value(), source, extra);
        }

        private CompletableFuture<Path> start(String path, ManagedContainer source, String extra) {
            require(owner);
            if (requests.size() >= 48)
                return CompletableFuture.failedFuture(new IOException("Export is BUSY"));
            var request = new Request(this, path, source, extra);
            requests.add(request);
            try {
                request.running = Objects.requireNonNull(runner.export(request));
                request.running.whenComplete((output, failure) -> {
                    synchronized (this) {
                        requests.remove(request);
                        request.source = null;
                        request.running = null;
                        if (request.committed != null)
                            request.output.complete(request.committed);
                        else if (failure == null)
                            request.output.complete(output);
                        else
                            request.output.completeExceptionally(unwrap(failure));
                    }
                });
            } catch (Throwable failure) {
                requests.remove(request);
                request.output.completeExceptionally(failure);
            }
            return request.output;
        }

        /**
         * Captures a fixed selection of non-builtin entries for serial progress through tick.
         * Each item is checked against its captured representation before starting.
         *
         * @return false if another batch is active; no second batch is admitted
         */
        public synchronized boolean exportAll(String pattern, Consumer<ModelWorkSummary.Item> itemNotice,
            Consumer<ModelWorkSummary> summaryNotice) {
            require(owner);
            if (batch != null)
                return false;
            var selected = catalog.get().catalog().byModelId().values().stream()
                .filter(record -> record.location().rootKind() != CatalogRootKind.BUILTIN)
                .filter(record -> pattern == null || CatalogGlob.matches(pattern, record.entry().path().value()))
                .map(record -> new Item(record.entry().path().value(),
                    record.binding().content().representation().identity()))
                .toList();
            batch = new Batch(selected, itemNotice, summaryNotice);
            return true;
        }

        public synchronized void tick() {
            if (closed || batch == null)
                return;
            if (batch.running != null) {
                if (!batch.running.isDone())
                    return;
                try {
                    batch.finish(ModelWorkSummary.Outcome.SUCCESS, batch.running.join().toString());
                } catch (RuntimeException failure) {
                    batch.finish(ModelWorkSummary.Outcome.FAILED, message(failure));
                }
            }
            if (batch.next == batch.selection.size()) {
                batch.complete(false);
                batch = null;
                return;
            }
            var item = batch.selection.get(batch.next++);
            batch.current = item;
            var source = catalog.get().findPath(item.path).orElse(null);
            if (source == null || source.location().rootKind() == CatalogRootKind.BUILTIN
                || !source.representation().identity().equals(item.identity)) {
                batch.finish(ModelWorkSummary.Outcome.SKIPPED, "Catalog identity retired");
                return;
            }
            try {
                batch.running = start(item.path, source, null);
            } catch (RuntimeException failure) {
                batch.finish(ModelWorkSummary.Outcome.FAILED, message(failure));
            }
        }

        /**
         * Seals admission, cancels requests and waits for registered workers before completing batch feedback.
         * Calls after the first seal return immediately; they are not additional join operations.
         * Must not run from a registered worker. Interrupted waits restore interruption after workers leave.
         */
        @Override
        public void close() {
            List<Request> active;
            synchronized (this) {
                if (closed)
                    return;
                closed = true;
                active = List.copyOf(requests);
            }
            active.forEach(Request::cancel);
            synchronized (this) {
                boolean interrupted = false;
                while (workers != 0) {
                    try {
                        wait();
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
                if (interrupted)
                    Thread.currentThread().interrupt();
                if (batch != null) {
                    if (batch.current != null) {
                        if (batch.running.isDone() && !batch.running.isCompletedExceptionally())
                            batch.finish(ModelWorkSummary.Outcome.SUCCESS, batch.running.join().toString());
                        else
                            batch.finish(ModelWorkSummary.Outcome.FAILED, "Integrated environment ended");
                    }
                    batch.complete(true);
                    batch = null;
                }
                requests.clear();
            }
        }

        private final class Batch {
            private final List<Item> selection;
            private final Consumer<ModelWorkSummary.Item> itemNotice;
            private final Consumer<ModelWorkSummary> summaryNotice;
            private final List<ModelWorkSummary.Item> results = new ArrayList<>();
            private int next;
            private Item current;
            private CompletableFuture<Path> running;

            private Batch(List<Item> selection, Consumer<ModelWorkSummary.Item> itemNotice,
                Consumer<ModelWorkSummary> summaryNotice) {
                this.selection = selection;
                this.itemNotice = itemNotice;
                this.summaryNotice = summaryNotice;
            }

            private void finish(ModelWorkSummary.Outcome outcome, String reason) {
                var item = new ModelWorkSummary.Item(current.identity, current.path, outcome, reason);
                results.add(item);
                current = null;
                running = null;
                notice(() -> itemNotice.accept(item));
            }

            private void complete(boolean stopped) {
                var summary =
                    new ModelWorkSummary(selection.size(), results, selection.size() - results.size(), stopped);
                notice(() -> summaryNotice.accept(summary));
            }
        }
    }

    /**
     * Exact input and effect capability captured before source work is admitted.
     *
     * <p>Execution: cancellation and commit are ordered by the environment monitor. Cancellation before commit
     * prevents replacement; cancellation after commit preserves the committed result. Cancelling a future does
     * not mean that a registered worker has ended its borrow.
     */
    public static final class Request implements AtomicSharedCache.CommitGate {
        private final Environment environment;
        private final String path;
        private ManagedContainer source;
        private final String extra;
        private final CompletableFuture<Path> output = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterrupt) {
                return Request.this.cancel();
            }
        };
        private CompletableFuture<Path> running;
        private boolean cancelled;
        private boolean sourceClaimed;
        private Path committed;

        private Request(Environment environment, String path, ManagedContainer source, String extra) {
            this.environment = environment;
            this.path = path;
            this.source = Objects.requireNonNull(source);
            this.extra = extra;
        }

        public String path() {
            return path;
        }

        public String extra() {
            return extra;
        }

        private ManagedContainer claimSource() {
            synchronized (environment) {
                requireActive();
                if (sourceClaimed)
                    throw new IllegalStateException("Export source was already claimed");
                sourceClaimed = true;
                var exact = source;
                source = null;
                return exact;
            }
        }

        public void requireActive() {
            synchronized (environment) {
                environment.require(environment.owner);
                if (cancelled)
                    throw new CancellationException("Export operation cancelled");
            }
        }

        /**
         * @return true if a worker was registered; the caller must pair it with exactly one endWorker in finally
         */
        public boolean beginWorker() {
            synchronized (environment) {
                if (environment.closed || cancelled)
                    return false;
                environment.workers++;
                return true;
            }
        }

        /** Ends one successful beginWorker registration after all worker-owned borrows have ended. */
        public void endWorker() {
            synchronized (environment) {
                environment.workers--;
                environment.notifyAll();
            }
        }

        @Override
        public void commit(AtomicSharedCache.AtomicReplacement replacement) throws IOException {
            commit(outputPath(path), replacement);
        }

        public void commit(Path output, AtomicSharedCache.AtomicReplacement replacement) throws IOException {
            synchronized (environment) {
                requireActive();
                replacement.run();
                committed = output;
            }
        }

        private boolean cancel() {
            CompletableFuture<Path> operation;
            boolean changed;
            synchronized (environment) {
                cancelled = true;
                if (committed != null) {
                    output.complete(committed);
                    changed = false;
                } else
                    changed = output.completeExceptionally(new CancellationException("Export environment ended"));
                operation = running;
            }
            if (operation != null)
                operation.cancel(false);
            return changed;
        }
    }

    /**
     * Owns a reopened source and any existing preview until closeOwned. The worker must finish all uses before
     * closing; synchronized cleanup does not permit concurrent use and close. content() is a borrowed view.
     */
    public static final class PreparedExport {
        private final ManagedContainer source;
        private final Path output;
        private final String extra;
        private final Request request;
        private final PreviewStore.EncodedPreview preview;
        private Path completed;
        private boolean closed;

        private PreparedExport(ManagedContainer source, Path output, String extra, Request request,
            PreviewStore.EncodedPreview preview) {
            this.source = source;
            this.output = output;
            this.extra = extra;
            this.request = request;
            this.preview = preview;
        }

        public ModelContent content() {
            return source;
        }

        /** @return the committed path, or null when the preparation still needs a generated preview */
        public Path completed() {
            return completed;
        }

        public synchronized Throwable closeOwned(Throwable failure) {
            if (!closed) {
                closed = true;
                failure = closeResource(preview, failure);
                failure = closeResource(source.representation(), failure);
            }
            return failure;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null)
            failure = failure.getCause();
        return failure;
    }

    private static Throwable closeResource(AutoCloseable resource, Throwable failure) {
        if (resource == null)
            return failure;
        try {
            resource.close();
        } catch (Throwable closeFailure) {
            if (failure == null)
                return closeFailure;
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private static String message(Throwable failure) {
        var cause = unwrap(failure);
        com.elfmcys.ysm.YesSteveModel.LOGGER.error("Model export failed", cause);
        return com.elfmcys.ysm.model.domain.PublicFailure.summary(cause);
    }

    private static void notice(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException failure) {
            com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Export feedback failed", failure);
        }
    }
}
