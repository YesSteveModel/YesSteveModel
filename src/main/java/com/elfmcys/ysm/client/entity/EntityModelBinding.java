package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.expression.host.ExecutionFailure;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.service.ClientModelService;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.LongSupplier;

/** Owns complete installations and exact installation failures, never resource acquisition. */
final class EntityModelBinding implements AutoCloseable {
    @FunctionalInterface
    interface ResourceFactory {
        @Nullable
        AnimatableEntity.ResourceHolder create(ResourceLease lease, boolean fallback);
    }

    interface ModelAccess {
        @Nullable
        ModelContent content(Hash256 modelId);

        ResourceRequest resourceRequest(Hash256 modelId, String targetId, String textureName);

        ResourceRequest defaultResourceRequest(String targetId);

        default ResourceRequest resourceRequest(Hash256 modelId, String targetId, String textureName,
            ModelResourcePurpose purpose, Class<?> subjectType) {
            var request = resourceRequest(modelId, targetId, textureName);
            return new ResourceRequest(request.modelId(), request.targetId(), request.requestedTexture(),
                request.bakeProfile(), request.expressionBinding(), purpose);
        }

        default ResourceRequest defaultResourceRequest(String targetId, ModelResourcePurpose purpose,
            Class<?> subjectType) {
            return defaultResourceRequest(targetId);
        }

        void reportActiveModelFailure(ModelContent content, boolean fallbackAvailable);

        default @Nullable ClientDemandCoordinator coordinator(LongSupplier clock) {
            return ClientModelService.current().map(ClientModelService::demands).orElse(null);
        }
    }

    private final ModelAccess models;
    private final LongSupplier clock;
    private volatile boolean assigned;
    private ModelIntent intent;
    private AnimatableEntity.ResourceHolder resourceHolder;
    private ResourceRequest installedRequest;
    private ModelContent installedContent;
    private boolean installedFull;
    private final Map<ResourceRequest, ContentFailure> rejectedInstallations = new HashMap<>();
    private Throwable installationFailure;
    private ModelResourcePurpose purpose = ModelResourcePurpose.FULL;
    private Class<?> subjectType;

    EntityModelBinding() {
        this(new ClientModelAccess());
    }

    EntityModelBinding(ModelAccess models) {
        this(models, () -> System.nanoTime() / 1_000_000L);
    }

    EntityModelBinding(ModelAccess models, LongSupplier clock) {
        this.models = Objects.requireNonNull(models);
        this.clock = Objects.requireNonNull(clock);
    }

    @Nullable
    ClientDemandCoordinator coordinator() {
        return models.coordinator(clock);
    }

    void updateModelHash(@Nullable Hash256 hash) {
        updateModelHash(hash, "player", "");
    }

    void updateModelHash(@Nullable Hash256 hash, String targetId, String texture) {
        assigned = true;
        var next = new ModelIntent(hash, targetId, texture);
        if (!next.equals(intent)) {
            intent = next;
            installationFailure = null;
        }
    }

    boolean isAssigned() {
        return assigned;
    }

    void revokeAssignment() {
        assigned = false;
        intent = null;
    }

    @Nullable
    Hash256 modelHash() {
        return intent == null ? null : intent.modelId();
    }

    @Nullable
    AnimatableEntity.ResourceHolder resourceHolder() {
        return resourceHolder;
    }

    ClientDemandCoordinator.Assignment assignment(String target, String texture, String fallback,
        ModelResourceDemand.Mode mode, ModelResourcePurpose purpose, Class<?> subject) {
        this.purpose = purpose;
        this.subjectType = subject;
        var defaultRequest =
            fallback == null || fallback.isBlank() ? null : models.defaultResourceRequest(fallback, purpose, subject);
        var hash = modelHash();
        var request = intrinsicDefaultOnly() || hash == null ? defaultRequest
            : target == null || target.isBlank() || models.content(hash) == null ? null
                : models.resourceRequest(hash, target, texture, purpose, subject);
        return new ClientDemandCoordinator.Assignment(request, defaultRequest, mode, assigned);
    }

    boolean matches(ResourceRequest request, boolean full) {
        return resourceHolder != null && (!full || installedFull) && resourceHolder.lease().isCurrent(request);
    }

    boolean matchesSelection(ClientDemandCoordinator.Target target) {
        var request =
            target.modelId() == null ? models.defaultResourceRequest("player", ModelResourcePurpose.FULL, null)
                : models.resourceRequest(target.modelId(), "player", target.texture(), ModelResourcePurpose.FULL,
                    net.minecraft.client.player.AbstractClientPlayer.class);
        return matches(request, true);
    }

    @Nullable
    Throwable failure(ResourceRequest request) {
        var rejected = rejectedInstallations.get(request);
        if (rejected == null)
            return null;
        if (rejected.content().get() == models.content(request.modelId()))
            return rejected.cause();
        rejectedInstallations.remove(request);
        return null;
    }

    ClientDemandCoordinator.InstallationResult accept(ResourceRequest request, ResourceLease lease, boolean fallback,
        boolean full, ResourceFactory factory) {
        if (!assigned || !lease.isCurrent(request)) {
            lease.close();
            return ClientDemandCoordinator.InstallationResult.STALE;
        }
        var preparedFull = lease.purpose() == ModelResourcePurpose.FULL;
        if (full && !preparedFull) {
            lease.close();
            return ClientDemandCoordinator.InstallationResult.STALE;
        }
        var content = models.content(request.modelId());
        if (failure(request) != null) {
            lease.close();
            return ClientDemandCoordinator.InstallationResult.REJECTED;
        }
        var previous = resourceHolder;
        try {
            if (!installReady(lease, request, content, fallback, factory)) {
                installationFailure = new IllegalStateException("Resource installation was rejected");
                rejectedInstallations.put(request,
                    new ContentFailure(new WeakReference<>(content), installationFailure));
                return ClientDemandCoordinator.InstallationResult.REJECTED;
            }
            installedFull = preparedFull;
            installationFailure = null;
            return ClientDemandCoordinator.InstallationResult.INSTALLED;
        } catch (RuntimeException | OutOfMemoryError failure) {
            if (failure instanceof java.util.concurrent.CancellationException)
                return ClientDemandCoordinator.InstallationResult.STALE;
            // Publication is the commit; a later observer failure cannot turn it into a rejection.
            if (resourceHolder != previous && resourceHolder != null && resourceHolder.lease() == lease) {
                installedFull = preparedFull;
                return ClientDemandCoordinator.InstallationResult.INSTALLED;
            }
            installationFailure = failure;
            if (failure instanceof OutOfMemoryError) {
                if (request.modelId().equals(models.defaultResourceRequest("player").modelId()))
                    throw failure;
                return ClientDemandCoordinator.InstallationResult.REJECTED;
            }
            rejectedInstallations.put(request, new ContentFailure(new WeakReference<>(content), failure));
            if (request.modelId().equals(models.defaultResourceRequest("player").modelId()))
                throw failure;
            return ClientDemandCoordinator.InstallationResult.REJECTED;
        }
    }

    void failExpression(ExecutionFailure failure, @Nullable String fallback, ResourceFactory factory) {
        if (failure.reason() != ExecutionFailure.Reason.NON_NULL_RETURN || resourceHolder == null
            || isIntrinsicDefault())
            throw failure;
        var notice = rejectActiveInstallation(resourceHolder, failure);
        reportFailure(notice, fallback != null);
        replaceResourceHolder(null, null, null);
    }

    @Nullable
    FailureNotice rejectActiveInstallation(AnimatableEntity.ResourceHolder exact, RuntimeException failure) {
        if (exact == null || resourceHolder != exact)
            return null;
        var notice = new FailureNotice(failure, installedRequest, installedContent, isIntrinsicDefault());
        installationFailure = failure;
        if (installedRequest != null)
            rejectedInstallations.put(installedRequest,
                new ContentFailure(new WeakReference<>(installedContent), failure));
        return notice;
    }

    void reportFailure(@Nullable FailureNotice notice, boolean fallbackAvailable) {
        if (notice != null && notice.content() != null && !notice.intrinsicDefault())
            models.reportActiveModelFailure(notice.content(), fallbackAvailable);
    }

    record FailureNotice(RuntimeException cause, @Nullable ResourceRequest request,
        @Nullable ModelContent content, boolean intrinsicDefault) {}

    void installReadyForPreview(ResourceRequest request, ResourceLease lease, ResourceFactory factory) {
        updateModelHash(request.modelId(), request.targetId(), request.requestedTexture());
        if (accept(request, lease, false, request.purpose() == ModelResourcePurpose.FULL,
            factory) != ClientDemandCoordinator.InstallationResult.INSTALLED)
            throw new IllegalArgumentException("Preview resource lease was rejected");
    }

    private boolean installReady(ResourceLease lease, ResourceRequest request, @Nullable ModelContent content,
        boolean fallback, ResourceFactory factory) {
        final Object revision = intent;
        final AnimatableEntity.ResourceHolder next;
        try {
            next = factory.create(lease, fallback);
        } catch (RuntimeException | Error error) {
            try {
                lease.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if ((cleanupFailure instanceof VirtualMachineError && !(cleanupFailure instanceof OutOfMemoryError)
                    || cleanupFailure instanceof ThreadDeath)
                    && !(error instanceof VirtualMachineError || error instanceof ThreadDeath)) {
                    cleanupFailure.addSuppressed(error);
                    throw cleanupFailure;
                }
                if (cleanupFailure != error)
                    error.addSuppressed(cleanupFailure);
            }
            throw error;
        }
        if (next == null) {
            lease.close();
            return false;
        }
        if (next.lease() != lease) {
            next.close();
            lease.close();
            throw new IllegalStateException("Resource factory did not transfer the supplied lease");
        }
        if (revision != intent || !lease.isCurrent(request)) {
            next.close();
            throw new java.util.concurrent.CancellationException("Installation candidate is no longer current");
        }
        var previous = resourceHolder;
        var previousRequest = installedRequest;
        var previousContent = contentIdentity(installedContent);
        var candidateContent = contentIdentity(content);
        try {
            next.activate(() -> {
                resourceHolder = next;
                installedRequest = request;
                installedContent = content;
            }, () -> revision == intent && lease.isCurrent(request));
        } catch (RuntimeException | Error failure) {
            try {
                if (next.sharedStateActivationStarted()) {
                    if (!(failure instanceof java.util.concurrent.CancellationException)) {
                        failure.addSuppressed(new ActivationProvenance(previousRequest, previousContent, request,
                            candidateContent));
                        installationFailure = failure;
                        rejectedInstallations.put(request, new ContentFailure(new WeakReference<>(content), failure));
                    }
                    next.abortSharedStateActivation();
                }
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure)
                    failure.addSuppressed(cleanupFailure);
            }
            if (resourceHolder != next) {
                try {
                    next.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != failure)
                        failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        if (previous != null && previous != next) {
            try {
                previous.close();
            } catch (RuntimeException | LinkageError failure) {
                com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Previous entity installation cleanup failed", failure);
            }
        }
        return true;
    }

    private static @Nullable ModelFileIdentity contentIdentity(@Nullable ModelContent content) {
        var representation = content == null ? null : content.representation();
        return representation == null ? null : representation.identity();
    }

    static final class ActivationProvenance extends RuntimeException {
        private final ResourceRequest previousRequest;
        private final ModelFileIdentity previousContent;
        private final ResourceRequest candidateRequest;
        private final ModelFileIdentity candidateContent;

        private ActivationProvenance(@Nullable ResourceRequest previousRequest,
            @Nullable ModelFileIdentity previousContent, ResourceRequest candidateRequest,
            @Nullable ModelFileIdentity candidateContent) {
            super("Shared installation activation failed: previousRequest=" + previousRequest + " previousContent="
                + previousContent + " candidateRequest=" + candidateRequest + " candidateContent=" + candidateContent,
                null, false, false);
            this.previousRequest = previousRequest;
            this.previousContent = previousContent;
            this.candidateRequest = candidateRequest;
            this.candidateContent = candidateContent;
        }

        @Nullable ResourceRequest previousRequest() { return previousRequest; }
        @Nullable ModelFileIdentity previousContent() { return previousContent; }
        ResourceRequest candidateRequest() { return candidateRequest; }
        @Nullable ModelFileIdentity candidateContent() { return candidateContent; }
    }

    private void replaceResourceHolder(@Nullable AnimatableEntity.ResourceHolder next,
        @Nullable ResourceRequest request, @Nullable ModelContent content) {
        var previous = resourceHolder;
        resourceHolder = next;
        installedRequest = request;
        installedContent = content;
        installedFull = false;
        if (previous != null && previous != next)
            previous.close();
    }

    void releaseRenderTarget() {
        replaceResourceHolder(null, null, null);
    }

    void retryRejectedInstallations() {
        rejectedInstallations.clear();
        installationFailure = null;
    }

    @Nullable
    Throwable failure() {
        if (intent != null && intent.modelId() != null && !intent.targetId().isBlank()) {
            var request =
                models.resourceRequest(intent.modelId(), intent.targetId(), intent.textureName(), purpose, subjectType);
            var rejected = failure(request);
            if (rejected != null)
                return rejected;
        }
        return installationFailure;
    }

    boolean permitsCurrentUse() {
        return resourceHolder != null && (!intrinsicDefaultOnly() || isIntrinsicDefault());
    }

    static boolean intrinsicDefaultOnly() {
        return com.elfmcys.ysm.network.forge.ClientSessionRuntime.state()
            .orElse(null) == com.elfmcys.ysm.model.session.client.ClientModelSession.State.INTRINSIC_DEFAULT_ONLY;
    }

    private boolean isIntrinsicDefault() {
        return installedRequest != null
            && installedRequest.modelId().equals(models.defaultResourceRequest("player").modelId());
    }

    AnimatableEntity.InstallationRelation relation(String target, String texture) {
        return relation(target, texture, purpose, subjectType);
    }

    AnimatableEntity.InstallationRelation relation(String target, String texture, ModelResourcePurpose purpose,
        Class<?> subject) {
        if (resourceHolder == null)
            return AnimatableEntity.InstallationRelation.UNBOUND;
        var request = modelHash() == null ? models.defaultResourceRequest(target, purpose, subject)
            : target == null ? null : models.resourceRequest(modelHash(), target, texture, purpose, subject);
        if (request != null && matches(request, false))
            return AnimatableEntity.InstallationRelation.MATCHING;
        return isIntrinsicDefault() ? AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE
            : AnimatableEntity.InstallationRelation.RETAINED;
    }

    @Override
    public void close() {
        revokeAssignment();
        releaseRenderTarget();
        rejectedInstallations.clear();
    }

    private record ContentFailure(WeakReference<ModelContent> content, Throwable cause) {
    }

    private record ModelIntent(@Nullable Hash256 modelId, String targetId, String textureName) {
        private ModelIntent {
            targetId = Objects.requireNonNullElse(targetId, "");
            textureName = Objects.requireNonNullElse(textureName, "");
        }
    }

    private static final class ClientModelAccess implements ModelAccess {
        private ClientModelService service() {
            return ClientModelService.instance();
        }

        @Override
        public @Nullable ModelContent content(Hash256 modelId) {
            return service().catalog().find(modelId).map(entry -> entry.content()).orElse(null);
        }

        @Override
        public ResourceRequest resourceRequest(Hash256 modelId, String targetId, String textureName) {
            return service().resourceRequest(modelId, targetId, textureName);
        }

        @Override
        public ResourceRequest defaultResourceRequest(String targetId) {
            return service().defaultResourceRequest(targetId);
        }

        @Override
        public ResourceRequest resourceRequest(Hash256 modelId, String targetId, String textureName,
            ModelResourcePurpose purpose, Class<?> subjectType) {
            return service().resourceRequest(modelId, targetId, textureName, purpose, subjectType);
        }

        @Override
        public ResourceRequest defaultResourceRequest(String targetId, ModelResourcePurpose purpose,
            Class<?> subjectType) {
            return service().defaultResourceRequest(targetId, purpose, subjectType);
        }

        @Override
        public void reportActiveModelFailure(ModelContent content, boolean fallbackAvailable) {
            service().reportActiveModelFailure(content, fallbackAvailable);
        }
    }
}
