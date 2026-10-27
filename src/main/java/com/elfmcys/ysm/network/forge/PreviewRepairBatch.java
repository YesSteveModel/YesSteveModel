package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher;
import com.elfmcys.ysm.model.service.ModelWorkSummary;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.proto.network.PreviewRepairStatus;
import com.elfmcys.ysm.proto.network.ResourceFailureReason;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** The exact client connection owns selection, one current item, and finite terminal accounting. */
final class PreviewRepairBatch implements AutoCloseable {
    private final ClientModelSession session;
    private final ClientAssetTransfer transfer;
    private final BiFunction<ModelContent, RemoteChunkFetcher, CompletableFuture<PreviewStore.EncodedPreview>> images;
    private final Consumer<ModelWorkSummary.Item> itemNotice;
    private final Consumer<ModelWorkSummary> summaryNotice;
    private final Consumer<Throwable> queryFailure;
    private CompletableFuture<List<ClientAssetTransfer.MissingItem>> query;
    private List<ClientAssetTransfer.MissingItem> selection;
    private final List<ModelWorkSummary.Item> results = new ArrayList<>();
    private final java.util.Set<ModelFileIdentity> repaired = new java.util.HashSet<>();
    private final java.util.Map<ModelFileIdentity, ClientModelSession.PreviewObservation> observations =
        new java.util.HashMap<>();
    private int next;
    private ClientAssetTransfer.MissingItem current;
    private CompletableFuture<PreviewStore.EncodedPreview> image;
    private CompletableFuture<PreviewRepairStatus> upload;
    private boolean terminal;

    PreviewRepairBatch(ClientModelSession session, ClientAssetTransfer transfer,
        BiFunction<ModelContent, RemoteChunkFetcher, CompletableFuture<PreviewStore.EncodedPreview>> images,
        Consumer<ModelWorkSummary.Item> itemNotice, Consumer<ModelWorkSummary> summaryNotice,
        Consumer<Throwable> queryFailure) {
        this.session = session;
        this.transfer = transfer;
        this.images = images;
        this.itemNotice = itemNotice;
        this.summaryNotice = summaryNotice;
        this.queryFailure = queryFailure;
        query = transfer.queryMissingPreviews();
    }

    boolean terminal() {
        return terminal;
    }

    void tick() {
        if (terminal)
            return;
        if (session.state() != ClientModelSession.State.ACTIVE) {
            close();
            return;
        }
        if (selection == null) {
            if (!query.isDone())
                return;
            try {
                selection = query.join();
                selection.forEach(item -> session.observePreview(item.identity())
                    .ifPresent(observation -> observations.put(item.identity(), observation)));
                repaired.retainAll(observations.keySet());
            } catch (RuntimeException failure) {
                terminal = true;
                query = null;
                queryFailed(unwrap(failure));
                return;
            }
            query = null;
        }
        if (current != null) {
            if (image != null && image.isDone()) {
                try (var encoded = image.join()) {
                    image = null;
                    upload = transfer.uploadPreview(current.identity(), encoded);
                } catch (RuntimeException failure) {
                    finishFailure(failure);
                }
            }
            if (upload != null && upload.isDone()) {
                try {
                    var status = upload.join();
                    finish(status == PreviewRepairStatus.COMMITTED ? ModelWorkSummary.Outcome.SUCCESS
                        : ModelWorkSummary.Outcome.SKIPPED, status.getName());
                } catch (RuntimeException failure) {
                    finishFailure(failure);
                }
            }
            if (current != null)
                return;
        }
        if (next == selection.size()) {
            complete(false);
            return;
        }
        var item = selection.get(next);
        if (item.probeFailure() != ResourceFailureReason.UNSPECIFIED) {
            current = item;
            next++;
            finish(ModelWorkSummary.Outcome.FAILED, "Preview probe: " + item.probeFailure().getName());
            return;
        }
        if (repaired.remove(item.identity())) {
            current = item;
            next++;
            finish(ModelWorkSummary.Outcome.SKIPPED, "Preview is already available");
            return;
        }
        var published = session.remoteSnapshot().orElseThrow().entries().get(item.identity().modelId());
        if (published == null || !published.identity().equals(item.identity())
            || !session.previewObservationCurrent(observations.get(item.identity()))) {
            current = item;
            next++;
            finish(ModelWorkSummary.Outcome.SKIPPED, "Catalog identity retired");
            return;
        }
        var state = session.activationSnapshot().orElseThrow().entries().get(item.identity().modelId());
        if (state instanceof ActivationSnapshot.Pending)
            return;
        current = item;
        next++;
        if (!(state instanceof ActivationSnapshot.Ready ready)) {
            finish(ModelWorkSummary.Outcome.FAILED, "Model metadata is unavailable");
            return;
        }
        try {
            image = images.apply(ready.record().binding().content(), transfer::fetchManagement);
        } catch (RuntimeException failure) {
            finishFailure(failure);
        }
    }

    /** A delivered repair only retires unstarted work; it never creates another request. */
    void repaired(ModelFileIdentity identity) {
        if (terminal)
            return;
        repaired.removeIf(member -> session.observePreview(member).isEmpty());
        if ((selection == null || observations.containsKey(identity)) && session.observePreview(identity).isPresent())
            repaired.add(identity);
    }

    private void finishFailure(Throwable failure) {
        var cause = unwrap(failure);
        if (cause instanceof ClientAssetTransfer.Skipped skipped) {
            finish(ModelWorkSummary.Outcome.SKIPPED, skipped.status.getName());
        } else
            finish(ModelWorkSummary.Outcome.FAILED, String.valueOf(cause.getMessage()));
    }

    private void finish(ModelWorkSummary.Outcome outcome, String reason) {
        var result = new ModelWorkSummary.Item(current.identity(), current.path(), outcome, reason);
        results.add(result);
        current = null;
        image = null;
        upload = null;
        try {
            itemNotice.accept(result);
        } catch (RuntimeException failure) {
            com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Preview item feedback failed", failure);
        }
    }

    private void complete(boolean stopped) {
        terminal = true;
        var summary = new ModelWorkSummary(selection.size(), results, selection.size() - results.size(), stopped);
        selection = null;
        results.clear();
        repaired.clear();
        observations.clear();
        try {
            summaryNotice.accept(summary);
        } catch (RuntimeException failure) {
            com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Preview summary feedback failed", failure);
        }
    }

    @Override
    public void close() {
        if (terminal)
            return;
        if (query != null && query.isDone() && !query.isCompletedExceptionally()) {
            selection = query.join();
            query = null;
        }
        if (query != null) {
            query.cancel(false);
            query = null;
            terminal = true;
            repaired.clear();
            queryFailed(new java.util.concurrent.CancellationException("Connection ended before selection"));
            return;
        }
        if (image != null && !image.cancel(false) && !image.isCompletedExceptionally())
            image.join().close();
        if (upload != null && upload.isDone() && !upload.isCompletedExceptionally()) {
            var status = upload.join();
            finish(status == PreviewRepairStatus.COMMITTED ? ModelWorkSummary.Outcome.SUCCESS
                : ModelWorkSummary.Outcome.SKIPPED, status.getName());
        } else if (upload != null)
            upload.cancel(false);
        if (current != null)
            finish(ModelWorkSummary.Outcome.FAILED, "Connection ended");
        complete(true);
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null)
            failure = failure.getCause();
        return failure;
    }

    private void queryFailed(Throwable failure) {
        try {
            queryFailure.accept(failure);
        } catch (RuntimeException feedbackFailure) {
            com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Preview query feedback failed", feedbackFailure);
        }
    }
}
