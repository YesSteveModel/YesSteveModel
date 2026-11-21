package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.expression.host.ExecutionFailure;
import com.elfmcys.ysm.util.CleanupFailures;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.service.ClientModelService;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Owns complete installations and exact installation failures; candidates belong to the entity's demand owner.
 *
 * <p>
 * Execution: the caller holds the owner's installation window for acceptance, replacement, and release. This binding
 * does not join tasks or reserve that window for a later call.
 *
 * <p>
 * Ownership: an accepted attempt owns the supplied lease until a holder takes ownership or the attempt closes it.
 *
 * <p>
 * Seam: implements [complete installation and replacement](docs/architecture/animation/entity-lifecycle.md#模型使用与完整安装)
 * and [installation publication](docs/architecture/animation/entity-lifecycle.md#安装发布结果).
 */
final class EntityModelBinding implements AutoCloseable {
    @FunctionalInterface
    interface ResourceFactory {
        /**
         * Builds a private complete candidate using the supplied lease, without publishing the holder.
         *
         * <p>
         * Ownership: a non-null holder must own that exact lease. The acceptance operation closes the lease if creation
         * throws or returns null. Candidate cleanup must not close the previous installation.
         *
         * @return the prepared candidate, or null when preparation rejects the attempt
         */
        @Nullable
        AnimatableEntity.ResourceHolder create(ResourceLease lease, boolean fallback);
    }

    interface ModelAccess {
        /** Owns the fixed bake profile and subject bindings used by this access. */
        default Object requestOwner() {
            return this;
        }

        /** Returns current immutable content; a declaration change publishes a different content identity. */
        @Nullable
        ModelContent content(ModelId modelId);

        default @Nullable ModelContent content(PublishedModelId modelId) {
            return modelId instanceof PublishedModelId.Model model ? content(model.hash()) : null;
        }

        default @Nullable ModelId builtinDefaultModelId() {
            return null;
        }

        ResourceRequest resourceRequest(ModelId modelId, String targetId, String textureName);

        ResourceRequest defaultResourceRequest(String targetId);

        default ResourceRequest resourceRequest(ModelId modelId, String targetId, String textureName,
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
    private ModelIntent intent;
    private Object installedSelectionOperation;
    private AnimatableEntity.ResourceHolder resourceHolder;
    private ResourceRequest installedRequest;
    private ModelContent installedContent;
    private boolean installedFull;
    private final Map<ResourceRequest, ContentFailure> rejectedInstallations = new HashMap<>();
    private Throwable installationFailure;
    private ModelResourcePurpose purpose = ModelResourcePurpose.FULL;
    private Class<?> subjectType;
    // Content fixes the manifest; local mutations clear this projection, and Catalog lookup detects
    // replacement/removal.
    private RequestRelation requestRelation;

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

    void updateModelHash(@Nullable ModelId hash) {
        updateModelHash(hash, "player", "");
    }

    void updateModelHash(@Nullable ModelId hash, String targetId, String texture) {
        var operation = intent == null || !Objects.equals(intent.modelId(), hash) ? new Object() : intent.operation();
        select(hash, targetId, texture, operation);
    }

    void select(@Nullable ModelId hash, String targetId, String texture, Object operation) {
        var next = new ModelIntent(hash, targetId, texture, Objects.requireNonNull(operation));
        if (!next.equals(intent)) {
            requestRelation = null;
            intent = next;
            installationFailure = null;
        }
    }

    Object selectionOperation() {
        return intent == null ? null : intent.operation();
    }

    String selectedTexture() {
        return intent == null ? "" : intent.textureName();
    }

    boolean usageMatchesOperation() {
        return resourceHolder != null && installedSelectionOperation == selectionOperation();
    }

    void requestBuiltinDefault() {
        select(null, "player", "", new Object());
    }

    boolean isAssigned() {
        return intent != null;
    }

    void revokeAssignment() {
        requestRelation = null;
        intent = null;
    }

    @Nullable
    ModelId modelHash() {
        return intent == null ? null : intent.modelId();
    }

    @Nullable
    AnimatableEntity.ResourceHolder resourceHolder() {
        return resourceHolder;
    }

    @Nullable
    PublishedModelId installedModelId() {
        return installedRequest == null ? null : installedRequest.modelId();
    }

    @Nullable
    ResourceRequest installedRequest() {
        return installedRequest;
    }

    ClientDemandCoordinator.Assignment assignment(String target, String texture, String fallback,
        ModelResourceDemand.Mode mode, ModelResourcePurpose purpose, Class<?> subject) {
        this.purpose = purpose;
        this.subjectType = subject;
        var defaultRequest =
            fallback == null || fallback.isBlank() ? null : models.defaultResourceRequest(fallback, purpose, subject);
        var hash = modelHash();
        var request = intrinsicDefaultOnly() ? defaultRequest
            : hash == null
                ? target == null || target.isBlank() ? null : intrinsicRequest(target, texture, purpose, subject)
                : target == null || target.isBlank() || models.content(hash) == null ? null
                    : models.resourceRequest(hash, target, texture, purpose, subject);
        return new ClientDemandCoordinator.Assignment(request, defaultRequest, mode, isAssigned(),
            selectionOperation());
    }

    boolean matches(ResourceRequest request, boolean full) {
        return resourceHolder != null && installedSelectionOperation == selectionOperation() && (!full || installedFull)
            && resourceHolder.lease().isCurrent(request);
    }

    boolean installedForSelection(Object operation) {
        return resourceHolder != null && installedSelectionOperation == operation && intent != null
            && installedRequest != null && installedRequest.modelId().equals(intent.modelId() == null
                ? PublishedModelId.INTRINSIC_DEFAULT : PublishedModelId.model(intent.modelId()));
    }

    boolean matchesSelection(ClientDemandCoordinator.Target target) {
        var request =
            target.modelId() == null ? models.defaultResourceRequest("player", ModelResourcePurpose.FULL, null)
                : models.resourceRequest(target.modelId(), "player", target.texture(), ModelResourcePurpose.FULL,
                    net.minecraft.client.player.AbstractClientPlayer.class);
        return matches(request, true);
    }

    private ResourceRequest intrinsicRequest(String target, String texture, ModelResourcePurpose purpose,
        Class<?> subject) {
        var required = models.defaultResourceRequest(target, purpose, subject);
        return new ResourceRequest(required.modelId(), required.targetId(), texture, required.bakeProfile(),
            required.expressionBinding(), purpose);
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

    /**
     * Consumes one installation attempt inside an already established installation window.
     *
     * <p>
     * Ensures: INSTALLED follows holder publication, including a later observer failure. Private candidate failure
     * preserves the old holder. Failed activation of shared mutable state ends the old use without rollback.
     *
     * <p>
     * Failure: stale request or preparation identity returns STALE. Exact remembered failure or candidate rejection
     * returns REJECTED. Runtime installation failure records the exact request/content pair. Required-default failure
     * before publication propagates from this boundary.
     *
     * <p>
     * Ownership: all entered outcomes consume the supplied lease. The caller must not close or reuse that lease
     * afterward.
     *
     * @return the publication result; this method does not return BUSY
     */
    ClientDemandCoordinator.InstallationResult accept(ResourceRequest request, ResourceLease lease, boolean fallback,
        boolean full, ResourceFactory factory) {
        if (!isAssigned() || !lease.isCurrent(request)) {
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
                if (request.modelId().isIntrinsicDefault())
                    throw (IllegalStateException)installationFailure;
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
                if (request.modelId().isIntrinsicDefault())
                    throw failure;
                return ClientDemandCoordinator.InstallationResult.REJECTED;
            }
            rejectedInstallations.put(request, new ContentFailure(new WeakReference<>(content), failure));
            if (request.modelId().isIntrinsicDefault())
                throw failure;
            return ClientDemandCoordinator.InstallationResult.REJECTED;
        }
    }

    void failExpression(ExecutionFailure failure, @Nullable String fallback, Runnable endUse) {
        Throwable primary = null;
        try {
            if (failure.reason() != ExecutionFailure.Reason.INSTANCE_EXECUTION || resourceHolder == null
                || isIntrinsicDefault())
                throw failure;
            var notice = rejectActiveInstallation(resourceHolder, failure);
            reportFailure(notice, fallback != null);
        } catch (RuntimeException | Error rejection) {
            primary = EntityExecutionLifecycle.appendFailure(failure, rejection, false);
        }
        try {
            // The entity owns private execution and must release it before the target lease.
            endUse.run();
        } catch (RuntimeException | Error cleanup) {
            primary = EntityExecutionLifecycle.appendFailure(primary == null ? failure : primary, cleanup, false);
        }
        if (primary instanceof RuntimeException runtime)
            throw runtime;
        if (primary instanceof Error error)
            throw error;
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

    record FailureNotice(RuntimeException cause, @Nullable ResourceRequest request, @Nullable ModelContent content,
        boolean intrinsicDefault) {
    }

    void installReadyForPreview(ResourceRequest request, ResourceLease lease, ResourceFactory factory) {
        updateModelHash(request.modelId() instanceof PublishedModelId.Model model ? model.hash() : null,
            request.targetId(), request.requestedTexture());
        if (accept(request, lease, false, request.purpose() == ModelResourcePurpose.FULL,
            factory) != ClientDemandCoordinator.InstallationResult.INSTALLED)
            throw new IllegalArgumentException("Preview resource lease was rejected");
    }

    private boolean installReady(ResourceLease lease, ResourceRequest request, @Nullable ModelContent content,
        boolean fallback, ResourceFactory factory) {
        final Object revision = selectionOperation();
        final AnimatableEntity.ResourceHolder next;
        try {
            next = factory.create(lease, fallback);
        } catch (RuntimeException | Error error) {
            try {
                lease.close();
            } catch (RuntimeException | Error cleanupFailure) {
                var primary = CleanupFailures.merge(error, cleanupFailure);
                if (primary instanceof Error fatal)
                    throw fatal;
                throw (RuntimeException)primary;
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
        if (revision != selectionOperation() || !lease.isCurrent(request)) {
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
                installedSelectionOperation = revision;
                requestRelation = null;
            }, () -> revision == selectionOperation() && lease.isCurrent(request));
        } catch (RuntimeException | Error failure) {
            var sharedActivation = next.sharedStateActivationStarted();
            Throwable abortFailure = null;
            Throwable closeFailure = null;
            if (sharedActivation) {
                try {
                    next.abortSharedStateActivation();
                } catch (RuntimeException | Error cleanupFailure) {
                    abortFailure = cleanupFailure;
                }
            }
            if (resourceHolder != next) {
                try {
                    next.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    closeFailure = cleanupFailure;
                }
            }
            // Diagnostics can allocate; neither failed bookkeeping nor suppression may skip either cleanup.
            Throwable primary = CleanupFailures.merge(failure, abortFailure);
            primary = CleanupFailures.merge(primary, closeFailure);
            if (sharedActivation && !(failure instanceof java.util.concurrent.CancellationException)
                && !(failure instanceof OutOfMemoryError)) {
                try {
                    failure.addSuppressed(
                        new ActivationProvenance(previousRequest, previousContent, request, candidateContent));
                    installationFailure = failure;
                    rejectedInstallations.put(request, new ContentFailure(new WeakReference<>(content), failure));
                } catch (RuntimeException | Error diagnosticFailure) {
                    primary = CleanupFailures.merge(primary, diagnosticFailure);
                }
            }
            if (primary instanceof Error error)
                throw error;
            throw (RuntimeException)primary;
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

        @Nullable
        ResourceRequest previousRequest() {
            return previousRequest;
        }

        @Nullable
        ModelFileIdentity previousContent() {
            return previousContent;
        }

        ResourceRequest candidateRequest() {
            return candidateRequest;
        }

        @Nullable
        ModelFileIdentity candidateContent() {
            return candidateContent;
        }
    }

    private void replaceResourceHolder(@Nullable AnimatableEntity.ResourceHolder next,
        @Nullable ResourceRequest request, @Nullable ModelContent content) {
        var previous = resourceHolder;
        resourceHolder = next;
        installedRequest = request;
        installedContent = content;
        installedSelectionOperation = null;
        installedFull = false;
        requestRelation = null;
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
        return com.elfmcys.ysm.network.forge.ClientSessionRuntime.stateInReadWindow()
            .orElse(null) == com.elfmcys.ysm.model.session.client.ClientModelSession.State.INTRINSIC_DEFAULT_ONLY;
    }

    boolean isIntrinsicDefault() {
        return installedRequest != null && installedRequest.modelId().isIntrinsicDefault();
    }

    AnimatableEntity.InstallationRelation relation(String target, String texture) {
        return relation(target, texture, purpose, subjectType);
    }

    AnimatableEntity.InstallationRelation relation(String target, String texture, ModelResourcePurpose purpose,
        Class<?> subject) {
        if (resourceHolder == null)
            return AnimatableEntity.InstallationRelation.UNBOUND;
        var hash = modelHash();
        var owner = models.requestOwner();
        var previous = requestRelation;
        var modelId = previous != null && Objects.equals(previous.hash(), hash) ? previous.modelId()
            : hash == null ? PublishedModelId.INTRINSIC_DEFAULT : PublishedModelId.model(hash);
        var content = models.content(modelId);
        if (previous != null && Objects.equals(previous.hash(), hash) && Objects.equals(previous.target(), target)
            && Objects.equals(previous.texture(), texture) && previous.purpose() == purpose
            && previous.subject() == subject && previous.owner() == owner && previous.content() == content)
            return previous.relation();
        var request = hash == null ? intrinsicRequest(target, texture, purpose, subject)
            : target == null ? null : models.resourceRequest(hash, target, texture, purpose, subject);
        var relation = request != null && matches(request, false) ? AnimatableEntity.InstallationRelation.MATCHING
            : isIntrinsicDefault() ? AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE
                : AnimatableEntity.InstallationRelation.RETAINED;
        requestRelation =
            new RequestRelation(hash, modelId, target, texture, purpose, subject, owner, content, relation);
        return relation;
    }

    private record RequestRelation(@Nullable ModelId hash, PublishedModelId modelId, String target, String texture,
        ModelResourcePurpose purpose, Class<?> subject, Object owner, @Nullable ModelContent content,
        AnimatableEntity.InstallationRelation relation) {
    }

    @Override
    public void close() {
        revokeAssignment();
        releaseRenderTarget();
        rejectedInstallations.clear();
    }

    private record ContentFailure(WeakReference<ModelContent> content, Throwable cause) {
    }

    private record ModelIntent(@Nullable ModelId modelId, String targetId, String textureName, Object operation) {
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
        public Object requestOwner() {
            return service();
        }

        @Override
        public @Nullable ModelContent content(ModelId modelId) {
            var record = service().catalog().catalog().byModelId().get(modelId);
            return record == null ? null : record.binding().content();
        }

        @Override
        public @Nullable ModelContent content(PublishedModelId modelId) {
            if (modelId instanceof PublishedModelId.Model model)
                return content(model.hash());
            var record = service().catalog().catalog().intrinsicDefault();
            return record == null ? null : record.binding().content();
        }

        @Override
        public @Nullable ModelId builtinDefaultModelId() {
            return ClientModelService.current()
                .map(value -> value.catalog().find(PublishedModelId.INTRINSIC_DEFAULT).orElseThrow().modelHash())
                .orElse(null);
        }

        @Override
        public ResourceRequest resourceRequest(ModelId modelId, String targetId, String textureName) {
            return service().resourceRequest(modelId, targetId, textureName);
        }

        @Override
        public ResourceRequest defaultResourceRequest(String targetId) {
            return service().defaultResourceRequest(targetId);
        }

        @Override
        public ResourceRequest resourceRequest(ModelId modelId, String targetId, String textureName,
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
