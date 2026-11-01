package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.gui.GuiFrameTasks;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetBatch;
import com.elfmcys.ysm.model.resource.client.asset.ImageIdentity;
import com.elfmcys.ysm.model.resource.client.asset.PageImages;
import com.elfmcys.ysm.model.resource.client.asset.ResidentImages;
import com.elfmcys.ysm.util.ResponsiveExecutor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

/** Owns page acquisition; reservations and accepted jobs end inside one render call. */
public final class CatalogPageFrame implements ClientDemandCoordinator.PageAction {
    private final ClientDemandCoordinator.PageScope page;
    private final Supplier<ClientAssetBatch> batches;
    private final PageImages images;
    private final List<CatalogModelCardState> cards = new ArrayList<>();
    private PageImages previews;
    private ModelResourceDemand.Mode allowed = ModelResourceDemand.Mode.READY;
    private int cursor;
    private boolean closed;

    public CatalogPageFrame(ClientDemandCoordinator.PageScope page, Supplier<ClientAssetBatch> batches) {
        this.page = page;
        this.batches = batches;
        images = new PageImages(batches.get());
    }

    public PageImages images() { return images; }

    void register(CatalogModelCardState card) { cards.add(card); }

    @Override
    public void advance(ModelResourceDemand.Mode allowed) {
        if (closed || page.isClosed())
            return;
        this.allowed = allowed;
        var eager = page.eagerLoading();
        if (!eager && previews == null)
            previews = new PageImages(batches.get());
        for (var card : cards)
            card.previewPolicy(eager, eager ? null : previews);
        images.advanceAcquisition(allowed);
        if (!eager)
            previews.advanceAcquisition(allowed);
    }

    public void prepare(List<CatalogModelButton> buttons, List<PackButton> packs, float partialTick,
        int mouseX, int mouseY, long now) {
        page.advance();
        if (closed || page.isClosed())
            return;
        try (var frame = page.beginFrame()) {
            var states = buttons.stream().map(button -> button.prepareFrame(partialTick, mouseX, mouseY, now)).toList();
            var covers = packs.stream().map(PackButton::coverImage).toList();
            var jobs = plan(states, covers, ResponsiveExecutor.maximumPoolSize());
            var failure = GuiFrameTasks.execute(jobs, ResponsiveExecutor::execute);
            if (!page.isClosed()) {
                try { images.refreshResidents(); } catch (Throwable error) { failure = GuiFrameTasks.append(failure, error); }
                if (previews != null && !page.eagerLoading()) {
                    try { previews.refreshResidents(); } catch (Throwable error) { failure = GuiFrameTasks.append(failure, error); }
                }
            }
            if (failure instanceof Error error)
                throw error;
            if (failure != null)
                YesSteveModel.LOGGER.debug("GUI frame preparation failed", failure);
        }
    }

    List<GuiFrameTasks.Job> plan(List<CatalogModelCardState> states, List<PageImages.Image> covers,
        int maximumPoolSize) {
        var pending = new boolean[covers.size() + states.size()];
        for (int index = 0; index < covers.size(); index++) {
            var cover = covers.get(index);
            pending[index] = allowed != ModelResourceDemand.Mode.READY && cover != null && cover.cpuReady();
        }
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            var scope = state.scope();
            pending[covers.size() + index] = scope != null && scope.hasReadyInstallation()
                || allowed != ModelResourceDemand.Mode.READY && state.images().stream().anyMatch(PageImages.Image::cpuReady);
        }
        var admission = GuiFrameTasks.admit(pending, cursor, maximumPoolSize);
        cursor = admission.nextCursor();
        var assigned = new HashSet<ImageIdentity>();
        var jobs = new ArrayList<GuiFrameTasks.Job>(pending.length);
        Throwable failure = null;
        try {
            for (int index = 0; index < covers.size(); index++) {
                var cover = covers.get(index);
                if (admission.admitted()[index] && cover != null) {
                    var job = new CardWork(null, List.of(cover), true, assigned);
                    if (job.hasWork()) jobs.add(job);
                }
            }
            for (int index = 0; index < states.size(); index++) {
                var state = states.get(index);
                var admitted = admission.admitted()[covers.size() + index];
                if (admitted || state.renderTarget() != null) {
                    var job = new CardWork(state, state.images(), admitted, assigned);
                    if (job.hasWork()) jobs.add(job);
                }
            }
        } catch (Throwable error) {
            failure = error;
            for (var job : jobs) {
                try { job.reject(); } catch (Throwable cleanup) { failure = GuiFrameTasks.append(failure, cleanup); }
                try { job.finish(); } catch (Throwable cleanup) { failure = GuiFrameTasks.append(failure, cleanup); }
            }
            throwFailure(failure);
        }
        return jobs;
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        Throwable failure = null;
        try { images.close(); } catch (Throwable error) { failure = error; }
        if (previews != null) {
            try { previews.close(); } catch (Throwable error) { failure = GuiFrameTasks.append(failure, error); }
        }
        cards.clear();
        throwFailure(failure);
    }

    private final class CardWork implements GuiFrameTasks.Job {
        private final CatalogModelCardState state;
        private final List<ImageWork> imageWork = new ArrayList<>(3);
        private ClientDemandCoordinator.EntityScope.ReadyInstallation ready;
        private ClientDemandCoordinator.InstallationResult installation;
        private AnimatableEntity.GuiTask<Void> entityTask;
        private FutureTask<Void> imageTask;
        private boolean rejected;

        private CardWork(CatalogModelCardState state, List<PageImages.Image> slots, boolean admitted,
            Set<ImageIdentity> assigned) {
            this.state = state;
            try {
                if (admitted) {
                    if (state != null && state.scope() != null)
                        ready = state.scope().claimReadyInstallation();
                    if (allowed != ModelResourceDemand.Mode.READY) {
                        for (var slot : slots) {
                            var identity = slot.identity();
                            if (identity == null || assigned.contains(identity))
                                continue;
                            var preparation = slot.claimPreparation();
                            if (preparation != null) {
                                assigned.add(identity);
                                imageWork.add(new ImageWork(slot, preparation));
                            }
                        }
                    }
                }
                if (state != null && (ready != null || state.renderTarget() != null)) {
                    entityTask = state.entity().reserveGuiTask(execution -> {
                        withPreparationCleanup(() -> {
                            try (var ignored = state.frameInput().apply()) {
                                prepareImages();
                                if (ready != null) {
                                    var lease = ready.takeLease();
                                    installation = execution.install(ready.request(), lease, ready.fallback(), false);
                                }
                                state.updatePreviewAnimations(state.frameInput());
                                state.prepared(execution.update(state.frameInput().partialTick(), state.frameInput().context()));
                            }
                        });
                        return null;
                    });
                } else if (!imageWork.isEmpty()) {
                    imageTask = new FutureTask<>(() -> {
                        withPreparationCleanup(this::prepareImages);
                        return null;
                    });
                }
                if (hasWork() && state != null)
                    state.borrow();
            } catch (Throwable error) {
                try { reject(); } catch (Throwable cleanup) { error = GuiFrameTasks.append(error, cleanup); }
                try { finish(); } catch (Throwable cleanup) { error = GuiFrameTasks.append(error, cleanup); }
                throwFailure(error);
            }
        }

        private boolean hasWork() { return entityTask != null || imageTask != null; }

        private void withPreparationCleanup(Runnable body) {
            Throwable failure = null;
            try {
                body.run();
            } catch (RuntimeException | Error error) {
                failure = error;
                throw error;
            } finally {
                try { closePreparations(); }
                catch (RuntimeException | Error cleanup) {
                    var combined = GuiFrameTasks.append(failure, cleanup);
                    if (combined != failure) throwFailure(combined);
                }
            }
        }

        private void prepareImages() {
            for (var image : imageWork) {
                try { image.prepared = image.preparation.prepare(); }
                catch (Exception error) { image.failure = error; }
            }
        }

        private void closePreparations() {
            Throwable failure = null;
            for (var image : imageWork) {
                try { image.preparation.close(); } catch (Throwable error) { failure = GuiFrameTasks.append(failure, error); }
            }
            throwFailure(failure);
        }

        @Override
        public void run() {
            if (entityTask != null) entityTask.run(); else imageTask.run();
        }

        @Override
        public void join() {
            if (entityTask != null) {
                entityTask.join();
                return;
            }
            boolean interrupted = false;
            try {
                while (true) {
                    try { imageTask.get(); return; }
                    catch (InterruptedException ignored) { interrupted = true; }
                    catch (ExecutionException error) { throwFailure(error.getCause()); return; }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        @Override
        public void reject() {
            rejected = true;
            if (entityTask != null)
                entityTask.reject();
        }

        @Override
        public void publish() {
            Throwable failure = null;
            for (var image : imageWork) {
                if (image.failure != null && !page.isClosed())
                    image.slot.failed(image.failure);
                var prepared = image.prepared;
                image.prepared = null;
                if (prepared == null)
                    continue;
                try {
                    if (page.isClosed() || state != null && state.closed()) prepared.close();
                    else image.slot.publish(prepared);
                } catch (Throwable error) { failure = GuiFrameTasks.append(failure, error); }
            }
            throwFailure(failure);
        }

        @Override
        public void finish() {
            Throwable failure = null;
            if (ready != null) {
                try {
                    if (rejected || installation == null) ready.close();
                    else ready.complete(installation);
                } catch (Throwable error) { failure = error; }
            }
            for (var image : imageWork) {
                try {
                    if (image.prepared != null) {
                        var prepared = image.prepared;
                        image.prepared = null;
                        prepared.close();
                    }
                } catch (Throwable error) { failure = GuiFrameTasks.append(failure, error); }
                try { image.preparation.close(); } catch (Throwable error) { failure = GuiFrameTasks.append(failure, error); }
            }
            if (state != null) {
                try { state.returned(); } catch (Throwable error) { failure = GuiFrameTasks.append(failure, error); }
            }
            throwFailure(failure);
        }
    }

    private static final class ImageWork {
        private final PageImages.Image slot;
        private final ResidentImages.Preparation preparation;
        private ResidentImages.Prepared prepared;
        private Throwable failure;

        private ImageWork(PageImages.Image slot, ResidentImages.Preparation preparation) {
            this.slot = slot;
            this.preparation = preparation;
        }
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
        if (failure != null) throw new java.util.concurrent.CompletionException(failure);
    }
}
