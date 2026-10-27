package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.resource.client.ClientModelRenderTargetManager;

import com.elfmcys.ysm.model.resource.client.render.HostTexturePublisher;
import com.elfmcys.ysm.model.resource.client.render.ModelRenderTargetCache;
import com.elfmcys.ysm.model.resource.client.render.ModelRenderTargetLoader;
import com.elfmcys.ysm.model.resource.client.render.PreparedTextureSet;
import com.elfmcys.ysm.model.resource.client.render.RenderTargetKey;
import com.elfmcys.ysm.model.resource.client.render.RenderTargetLeaseOwnership;

import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.model.resource.client.RenderTargetResources;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import com.elfmcys.ysm.mock.evidence.EvidenceRun;
import com.elfmcys.ysm.mock.evidence.JsonlEvidenceSink;
import com.elfmcys.ysm.mock.evidence.ScenarioEvidence;
import com.elfmcys.ysm.util.ResourceTransaction;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModelManagementDemandLifecycleScenarioTest {
    private final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);

    @AfterEach
    void stopScheduler() throws InterruptedException {
        scheduler.shutdown();
        scheduler.awaitTermination();
    }

    private static final String SCENARIO_ID = "MMR-DOM-DEMAND-001";
    private static final BakeProfile PROFILE = new BakeProfile("i04");
    private static final AtomicInteger NEXT_CONTAINER_ID = new AtomicInteger(100);

    @BeforeAll
    static void establishRenderOwner() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.initRenderThread();
        }
    }

    @Test
    @Tag("model-management-mock")
    void sharedDemandReplacementAndRetirementHaveExactOwners() throws Throwable {
        assumeTrue(EvidenceRun.isConfigured(), "Only the explicit mock task writes acceptance evidence");
        var plan = new ScenarioPlan(
            List.of("entity-A acquire exact target", "entity-B acquire exact target", "GUI card acquire exact target",
                "single pending interest release", "shared Flight success",
                "catalog tuple replacement while old lease is held", "old operation late success after replacement",
                "last pre-admission interest release", "last post-admission interest release",
                "deterministic transfer failure and retry", "cache-only miss then normal demand",
                "session disconnect and late old terminal", "required-default lease retirement",
                "construction cancellation after ownership transfer", "host publication failure"),
            Map.of("sharedBackingCount", 1, "preAdmissionNetworkCommitments", 0, "preAdmissionNetworkCancels", 0,
                "postAdmissionNetworkCommitments", 1, "postAdmissionNetworkCancelsMax", 1,
                "terminalsPerAcceptedOperation", 1, "logicalReleasesPerInterestMax", 1, "contractViolationDiagnostics",
                0));
        var inputBytes = EvidenceJson.canonicalBytes(plan);
        var run = EvidenceRun.openConfigured();
        var identity = run.identity(SCENARIO_ID, List.of("FA-003", "FA-004", "FA-007"),
            List.of("CO-006", "CO-007", "CO-008", "CO-009", "CO-010", "CO-011", "CO-016"),
            EvidenceRun.Radius.DETERMINISTIC_COMPOSITION, true, inputBytes);
        var evidence = run.scenario(identity, inputBytes);

        try {
            execute(evidence);
            evidence.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.PASS, List.of(
                "Entity and GUI interests shared one exact Flight and one Ready target without transferring backing close ownership",
                "Pre-admission last release created no network commitment while post-admission last release sent one exact cancellation request",
                "Retired content remained usable through its held lease and late old terminals could not replace the new operation",
                "Cache-only miss, deterministic failure, replacement, and disconnect each converged without persistent failure or current effect",
                "Pre-Ready construction and host-publication failures closed candidate-owned resources exactly once on the deterministic owner boundary"),
                List.of(),
                Map.of("contractViolation", 0L, "unexpectedError", 0L, "expectedCancellation", 4L,
                    "expectedDeterministicFailure", 2L, "expectedHostPublicationFailure", 1L),
                "Deterministic in-process Java composition; it proves logical demand, exact operation, pre-Ready candidate, and owner-bound release semantics, but not a real Minecraft render thread, Forge process stop, Cleaner timing, GPU release, or OS reclamation"));
        } catch (Throwable failure) {
            completeFailure(evidence, failure);
            throw failure;
        }
    }

    private void execute(ScenarioEvidence evidence) throws Exception {
        var rejectedTargets = new AtomicInteger();
        var hostAttempts = new AtomicInteger();
        var contractViolations = new AtomicInteger();
        var hostThread = new ArrayList<String>();
        var targets = new ArrayList<ModelRenderTarget>();
        HostTexturePublisher rejectingHost = prepared -> {
            hostAttempts.incrementAndGet();
            hostThread.add(Thread.currentThread().getName());
            throw new IOException("controlled host publication failure");
        };

        try (var cache = new ModelRenderTargetCache(scheduler, target -> {
            rejectedTargets.incrementAndGet();
            target.close();
        }, ignored -> contractViolations.incrementAndGet(), rejectingHost, () -> 8, () -> 60)) {
            verifySharedReadyAndHeldRetirement(cache, targets, evidence);
            verifyReplacementLateTerminal(cache, targets, rejectedTargets, evidence);
            verifyAdmissionCancellation(cache, evidence);
            verifyFailureAndOfflineRetry(cache, targets, evidence);
            verifyDisconnectLateTerminal(cache, targets, rejectedTargets, evidence);
            verifyRequiredLeaseRetirement(evidence);
            verifyConstructionFailure(evidence);
            verifyHostPublicationFailure(cache, hostAttempts, hostThread, evidence);
        } finally {
            targets.forEach(ModelRenderTarget::close);
        }

        require(contractViolations.get() == 0, "Supported lifecycle paths must not report contract violations");
        record(evidence, "final-owner-inventory", "scenario", "owner-inventory",
            Map.of("contractViolations", "0", "hostAdoptedPhysicalResources", "0", "logicalReadyTargets",
                Integer.toString(targets.size()), "physicalReclamationOwner",
                "Minecraft/Iris/Cleaner after logical release", "physicalReclamationDeadline", "none"));
    }

    private void verifySharedReadyAndHeldRetirement(ModelRenderTargetCache cache, List<ModelRenderTarget> targets,
        ScenarioEvidence evidence) throws Exception {
        var modelId = hash(1);
        var oldContent = content(modelId);
        var currentIdentity = new AtomicReference<>(oldContent.representation().identity());
        ModelRenderTargetCache.CurrentLookup lookup =
            (identity, key, request) -> identity.equals(currentIdentity.get());
        var pending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        var admissions = new AtomicInteger();
        var oldTarget = closeableTarget(modelId, "old");
        targets.add(oldTarget);
        ModelRenderTargetCache.Loader loader = (ignored, dispatch) -> {
            admissions.incrementAndGet();
            return pending;
        };
        var entityA = cache.getOrStart(request(modelId), oldContent, key(), loader, lookup, "session-A", WorkPriority.BACKGROUND);
        var entityB = cache.getOrStart(request(modelId), oldContent, key(), loader, lookup, "session-A", WorkPriority.BACKGROUND);
        var guiCard = cache.getOrStart(request(modelId), oldContent, key(), loader, lookup, "session-A", WorkPriority.BACKGROUND);
        var terminal = cache.terminal(entityB);
        var terminalObservations = observeTerminal(terminal);

        require(admissions.get() == 1 && cache.loadingCount() == 1, "Three exact interests must share one Flight");
        entityA.close();
        require(cache.loadingCount() == 1, "A single release must not cancel remaining interests");
        pending.complete(readyResult(oldTarget));
        cache.tick();
        requireReady(entityB, oldTarget);
        requireReady(guiCard, oldTarget);
        require(terminalObservations.get() == 1, "The shared Flight must publish exactly one terminal");
        guiCard.close();
        guiCard.close();
        requireReady(entityB, oldTarget);
        record(evidence, "shared-ready", "flight-shared-1", "interest-ledger",
            Map.of("consumers", "entity-A,entity-B,gui-card", "loaderAdmissions", "1", "readyIdentity",
                identity(oldTarget), "singleReleasePreservedBacking", "true", "terminalCount", "1"));

        var nextContent = content(modelId);
        currentIdentity.set(nextContent.representation().identity());
        require(!entityB.isCurrent(request(modelId)), "A retired Ready lease must lose current eligibility");
        requireReady(entityB, oldTarget);
        var nextTarget = closeableTarget(modelId, "new");
        targets.add(nextTarget);
        var replacement =
            cache.getOrStart(request(modelId), nextContent, key(), (ignored, dispatch) -> ready(nextTarget), lookup, "session-A", WorkPriority.BACKGROUND);
        cache.tick();
        requireReady(replacement, nextTarget);
        requireReady(entityB, oldTarget);
        entityB.close();
        replacement.close();
        record(evidence, "held-lease-replacement", "flight-shared-2", "replacement-isolation",
            Map.of("oldReadyIdentity", identity(oldTarget), "newReadyIdentity", identity(nextTarget), "oldLeaseUsable",
                "true", "oldCurrent", "false", "newCurrent", "true"));
    }

    private void verifyReplacementLateTerminal(ModelRenderTargetCache cache, List<ModelRenderTarget> targets,
        AtomicInteger rejectedTargets, ScenarioEvidence evidence) throws Exception {
        var modelId = hash(2);
        var oldContent = content(modelId);
        var nextContent = content(modelId);
        var currentIdentity = new AtomicReference<>(oldContent.representation().identity());
        ModelRenderTargetCache.CurrentLookup lookup =
            (identity, key, request) -> identity.equals(currentIdentity.get());
        var oldPending = new CancelResistantFuture<ModelRenderTargetLoader.LoadResult>();
        var oldTarget = closeableTarget(modelId, "late-old");
        var nextTarget = closeableTarget(modelId, "replacement");
        targets.add(nextTarget);
        var old = cache.getOrStart(request(modelId), oldContent, key(), (ignored, dispatch) -> oldPending, lookup, "session-A", WorkPriority.BACKGROUND);
        var oldTerminal = cache.terminal(old);
        var oldTerminals = observeTerminal(oldTerminal);

        currentIdentity.set(nextContent.representation().identity());
        var replacement =
            cache.getOrStart(request(modelId), nextContent, key(), (ignored, dispatch) -> ready(nextTarget), lookup, "session-A", WorkPriority.BACKGROUND);
        oldPending.complete(readyResult(oldTarget));
        cache.tick();

        requireFailed(oldTerminal.join(), ResourceFailure.Kind.TRANSIENT);
        requireReady(replacement, nextTarget);
        require(oldTerminals.get() == 1 && oldPending.cancelCalls.get() == 0,
            "The stale finite operation must finish once without a retirement cancel");
        require(rejectedTargets.get() == 1, "The late old candidate must be rejected exactly once");
        replacement.close();
        record(evidence, "late-old-replacement", "flight-replacement-old", "late-terminal",
            Map.of("oldCancelRequests", "0", "oldTerminals", "1", "oldCandidateReleases", "1",
                "replacementReadyIdentity", identity(nextTarget), "lateCurrentEffects", "0"));
    }

    private void verifyAdmissionCancellation(ModelRenderTargetCache cache, ScenarioEvidence evidence)
        throws IOException {
        var preId = hash(3);
        var cancellationProbe = new AtomicReference<BooleanSupplier>();
        var preCommitments = new AtomicInteger();
        var preCancels = new AtomicInteger();
        var pre = cache.getOrStart(request(preId), content(preId), key(), (cancelled, dispatch) -> {
            cancellationProbe.set(cancelled);
            return new CompletableFuture<>();
        }, current(), "session-A", WorkPriority.BACKGROUND);
        var preTerminal = cache.terminal(pre);
        var preTerminals = observeTerminal(preTerminal);
        pre.cancelPending();
        cache.tick();
        require(cancellationProbe.get().getAsBoolean() && preTerminal.isDone(),
            "Last pre-admission release must cancel its exact Flight");
        require(preCommitments.get() == 0 && preCancels.get() == 0,
            "Pre-admission release must create no network commitment or cancel");
        require(preTerminals.get() == 1, "The pre-admission Flight owner must still publish one local terminal");

        var postId = hash(4);
        var postContent = content(postId);
        var transfer = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var accepted = new AtomicInteger();
        ModelRenderTargetCache.Loader loader = (ignored, dispatch) -> {
            accepted.incrementAndGet();
            return transfer;
        };
        var first = cache.getOrStart(request(postId), postContent, key(), loader, current(), "session-A", WorkPriority.BACKGROUND);
        var second = cache.getOrStart(request(postId), postContent, key(), loader, current(), "session-A", WorkPriority.BACKGROUND);
        var postTerminal = cache.terminal(second);
        var postTerminals = observeTerminal(postTerminal);
        first.cancelPending();
        require(transfer.cancelCalls.get() == 0, "A non-last interest must not cancel accepted work");
        second.cancelPending();
        second.cancelPending();
        cache.tick();
        requireFailed(postTerminal.join(), ResourceFailure.Kind.TRANSIENT);
        require(accepted.get() == 1 && transfer.cancelCalls.get() == 1 && postTerminals.get() == 1,
            "Post-admission last release must request one exact cancel and one terminal");
        record(evidence, "admission-cancel", "flight-cancel", "commitment-ledger",
            Map.of("preAdmissionCommitments", "0", "preAdmissionNetworkCancels", "0", "preAdmissionFlightTerminals",
                "1", "postAdmissionCommitments", "1", "postAdmissionCancelRequests", "1", "postAdmissionTerminals",
                "1"));
    }

    private void verifyFailureAndOfflineRetry(ModelRenderTargetCache cache, List<ModelRenderTarget> targets,
        ScenarioEvidence evidence) throws Exception {
        var failedId = hash(5);
        var failed = cache.getOrStart(request(failedId), content(failedId), key(),
            (ignored, dispatch) -> CompletableFuture.completedFuture(
                new ModelRenderTargetLoader.LoadResult.Failed(new ResourceFailure(ResourceFailure.Kind.DETERMINISTIC,
                    new IOException("controlled content failure")))),
            current(), "session-A", WorkPriority.BACKGROUND);
        cache.tick();
        requireFailed(cache.terminal(failed).join(), ResourceFailure.Kind.DETERMINISTIC);
        var retryTarget = closeableTarget(failedId, "retry");
        targets.add(retryTarget);
        var retry = cache.getOrStart(request(failedId), content(failedId), key(), (ignored, dispatch) -> ready(retryTarget),
            current(), "session-A", WorkPriority.BACKGROUND);
        cache.tick();
        requireReady(retry, retryTarget);

        var offlineId = hash(6);
        var offlineContent = content(offlineId);
        var offlineStarts = new AtomicInteger();
        var offline = cache.getOrStartOffline(request(offlineId), offlineContent, key(), (ignored, dispatch) -> {
            offlineStarts.incrementAndGet();
            return CompletableFuture.completedFuture(new ModelRenderTargetLoader.LoadResult.Failed(
                new ResourceFailure(ResourceFailure.Kind.TRANSIENT, new IOException("controlled cache-only miss"))));
        }, current(), "session-A", WorkPriority.BACKGROUND);
        cache.tick();
        Optional<ResourceLease> miss = offline.join();
        require(miss.isEmpty(), "Cache-only miss must stay an empty probe result");
        var normalTarget = closeableTarget(offlineId, "normal-after-miss");
        targets.add(normalTarget);
        var normal = cache.getOrStart(request(offlineId), offlineContent, key(), (ignored, dispatch) -> {
            offlineStarts.incrementAndGet();
            return ready(normalTarget);
        }, current(), "session-A", WorkPriority.BACKGROUND);
        cache.tick();
        requireReady(normal, normalTarget);
        require(offlineStarts.get() == 2, "Cache-only miss must not suppress later normal demand");
        failed.close();
        retry.close();
        normal.close();
        record(evidence, "failure-and-cache-probe", "flight-retry", "failure-replay",
            Map.of("deterministicFailureTerminals", "1", "retryReadyIdentity", identity(retryTarget),
                "cacheOnlyOutcome", "miss", "normalStartsAfterMiss", "1", "normalReadyIdentity",
                identity(normalTarget)));
    }

    private void verifyDisconnectLateTerminal(ModelRenderTargetCache cache, List<ModelRenderTarget> targets,
        AtomicInteger rejectedTargets, ScenarioEvidence evidence) throws Exception {
        var modelId = hash(7);
        var content = content(modelId);
        var oldOwner = new Object();
        var nextOwner = new Object();
        var oldPending = new CancelResistantFuture<ModelRenderTargetLoader.LoadResult>();
        var oldTarget = closeableTarget(modelId, "disconnect-old");
        var nextTarget = closeableTarget(modelId, "disconnect-new");
        targets.add(nextTarget);
        var old = cache.getOrStart(request(modelId), content, key(), (ignored, dispatch) -> oldPending, current(), oldOwner, WorkPriority.BACKGROUND);
        var oldTerminal = cache.terminal(old);
        var previousRejected = rejectedTargets.get();

        cache.clearSession(oldOwner);
        var next =
            cache.getOrStart(request(modelId), content, key(), (ignored, dispatch) -> ready(nextTarget), current(), nextOwner, WorkPriority.BACKGROUND);
        cache.tick();
        oldPending.complete(readyResult(oldTarget));

        requireFailed(oldTerminal.join(), ResourceFailure.Kind.TRANSIENT);
        requireReady(next, nextTarget);
        require(rejectedTargets.get() == previousRejected + 1,
            "A disconnected operation's late candidate must be released exactly once");
        next.close();
        record(evidence, "disconnect-late-terminal", "flight-disconnect-old", "session-retirement",
            Map.of("oldCancelRequests", Integer.toString(oldPending.cancelCalls.get()), "oldTerminals", "1",
                "oldCandidateReleases", "1", "newReadyIdentity", identity(nextTarget), "lateCurrentEffects", "0"));
    }

    private void verifyRequiredLeaseRetirement(ScenarioEvidence evidence) throws Exception {
        var failedRequired = CountingLease.pending();
        var retainedRequired = CountingLease.pending();
        try (var ownership = new RenderTargetLeaseOwnership()) {
            ownership.addRequired(failedRequired);
            ownership.addRequired(retainedRequired);
            ownership.removeRequired(failedRequired);
            require(failedRequired.cancelCalls.get() == 1,
                "Failed required-default ownership must release its exact lease");
        }
        require(retainedRequired.cancelCalls.get() == 1,
            "Client owner close must release the retained required-default lease once");
        record(evidence, "required-default-and-stop", "required-target-owner", "logical-release", Map.of(
            "failedRequiredReleases", "1", "stopRequiredReleases", "1", "sharedBackingCloseOwnerTransferred", "false"));
    }

    private void verifyConstructionFailure(ScenarioEvidence evidence) throws Exception {
        var releases = new ArrayList<Integer>();
        var first = (AutoCloseable)() -> releases.add(1);
        var second = (AutoCloseable)() -> releases.add(2);
        var candidate = new OwnedCandidate(first, second);
        try (var resources = new ResourceTransaction()) {
            resources.own(first);
            resources.own(second);
            requireThrows(CancellationException.class,
                () -> ModelRenderTargetLoader.publishCandidate(() -> true, resources, candidate));
        }
        candidate.close();
        require(releases.equals(List.of(2, 1)),
            "Construction cancellation must release candidate-owned resources once");
        record(evidence, "construction-cancel", "candidate-construction", "candidate-release",
            Map.of("ownershipTransferred", "true", "terminal", "cancellation", "releaseOrder", "2,1", "releaseCount",
                "2", "duplicateReleaseCount", "0"));
    }

    private void verifyHostPublicationFailure(ModelRenderTargetCache cache, AtomicInteger hostAttempts,
        List<String> hostThread, ScenarioEvidence evidence) throws Exception {
        var modelId = hash(10);
        ImageSource unusedSource = () -> {
            throw new AssertionError("The injected decoder owns this boundary");
        };
        var image = new NativeImage(1, 1, false);
        var prepared = new PreparedTextureSet(new PBRImageSources(unusedSource, null, null), "i04/",
            ModelResourceFailures.none(), ignored -> image);
        prepared.prepare(() -> false);
        var candidate = new ModelRenderTargetLoader.ModelCandidate(closeableTarget(modelId, "host-failure"), prepared);
        var lease = cache.getOrStart(request(modelId), content(modelId), key(),
            (ignored, dispatch) -> CompletableFuture.completedFuture(new ModelRenderTargetLoader.LoadResult.Ready(candidate)),
            current(), "session-A", WorkPriority.BACKGROUND);
        var terminal = cache.terminal(lease);
        var terminals = observeTerminal(terminal);

        cache.tick();

        requireFailed(terminal.join(), ResourceFailure.Kind.DETERMINISTIC);
        require(hostAttempts.get() == 1 && terminals.get() == 1,
            "Host publication failure must have one attempt and one terminal");
        requireThrows(IllegalStateException.class, () -> image.getPixelRGBA(0, 0));
        lease.close();
        record(evidence, "host-publication-failure", "candidate-host-failure", "host-owner-disposition",
            Map.of("hostAttempts", "1", "terminals", "1", "candidateImageReleases", "1", "hostAdoptedResources", "0",
                "ownerThread", hostThread.get(0)));
    }

    private static AtomicInteger observeTerminal(CompletableFuture<AcquireResult> terminal) {
        var observations = new AtomicInteger();
        terminal.whenComplete((ignored, failure) -> observations.incrementAndGet());
        return observations;
    }

    private static void requireReady(ResourceLease lease, ModelRenderTarget expected) {
        var result = lease.poll();
        require(result instanceof AcquireResult.Ready ready && ready.target() == expected,
            "Lease did not expose the expected Ready identity");
    }

    private static void requireFailed(AcquireResult result, ResourceFailure.Kind kind) {
        require(result instanceof AcquireResult.Failed failed && failed.failure().kind() == kind,
            "Operation did not expose the expected failure terminal");
    }

    private static void record(ScenarioEvidence evidence, String action, String operation, String event,
        Map<String, String> payload) throws IOException {
        evidence.append(new JsonlEvidenceSink.Observation(SCENARIO_ID, action, "demand-lifecycle", null, operation,
            event, payload));
    }

    private static void completeFailure(ScenarioEvidence evidence, Throwable failure) throws IOException {
        var artifact = evidence.retainFailureArtifact("scenario-failure.txt",
            (failure + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        evidence.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.FAIL, List.of(),
            List.of("Demand lifecycle scenario failed: " + failure),
            Map.of("contractViolation", 0L, "unexpectedError", 1L), "Failure retained at " + artifact
                + "; no claim extends beyond the deterministic in-process Java boundary"));
    }

    private static CompletableFuture<ModelRenderTargetLoader.LoadResult> ready(ModelRenderTarget target) {
        return CompletableFuture.completedFuture(readyResult(target));
    }

    private static ModelRenderTargetLoader.LoadResult.Ready readyResult(ModelRenderTarget target) {
        return new ModelRenderTargetLoader.LoadResult.Ready(ModelRenderTargetLoader.ModelCandidate.testing(target));
    }

    private static ResourceRequest request(Hash256 modelId) {
        return new ResourceRequest(modelId, "player", "default", PROFILE);
    }

    private static RenderTargetKey key() {
        return new RenderTargetKey("player", "default", PROFILE);
    }

    private static ModelRenderTargetCache.CurrentLookup current() {
        return (content, key, request) -> true;
    }

    private static String identity(ModelRenderTarget target) {
        return Integer.toUnsignedString(System.identityHashCode(target));
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }

    private static ModelContent content(Hash256 modelId) {
        var representation = representation(new ModelFileIdentity(modelId, hash(NEXT_CONTAINER_ID.getAndIncrement())));
        return new ModelContent() {
            @Override
            public Hash256 modelId() {
                return modelId;
            }

            @Override
            public ModelRepresentation representation() {
                return representation;
            }

            @Override
            public ModelFileView modelFile() {
                return null;
            }

            @Override
            public ChunkDataSource chunks() {
                return null;
            }
        };
    }

    private static ModelRepresentation representation(ModelFileIdentity identity) {
        try {
            var value = (ModelRepresentation)UnsafeUtil.getUnsafe().allocateInstance(ModelRepresentation.class);
            var field = ModelRepresentation.class.getDeclaredField("identity");
            UnsafeUtil.getUnsafe().putObject(value, UnsafeUtil.getUnsafe().objectFieldOffset(field), identity);
            return value;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static ModelRenderTarget closeableTarget(Hash256 modelId, String targetId) throws InstantiationException {
        return new ModelRenderTarget(modelId, targetId, new RenderTargetResources() {},
            (CommonAsset)UnsafeUtil.getUnsafe().allocateInstance(CommonAsset.class),
            com.elfmcys.ysm.expression.aot.TestExpressionModules.create(),
            (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T extends Throwable> void requireThrows(Class<T> expected, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return;
            }
            throw new AssertionError("Unexpected failure type", failure);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private record ScenarioPlan(List<String> actions, Map<String, Integer> expected) {
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }

    private static class CountingFuture<T> extends CompletableFuture<T> {
        protected final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static final class CancelResistantFuture<T> extends CountingFuture<T> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            return false;
        }
    }

    private static final class CountingLease implements ResourceLease {
        private final ModelRenderTarget target;
        private final boolean pending;
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private boolean cancelled;

        private CountingLease(ModelRenderTarget target, boolean pending) {
            this.target = target;
            this.pending = pending;
        }

        private static CountingLease pending() {
            return new CountingLease(null, true);
        }

        private static CountingLease ready(ModelRenderTarget target) {
            return new CountingLease(target, false);
        }

        @Override
        public com.elfmcys.ysm.model.resource.client.ModelResourcePurpose purpose() {
            return com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.FULL;
        }

        @Override
        public AcquireResult poll() {
            return pending ? new AcquireResult.Pending() : new AcquireResult.Ready(target);
        }

        @Override
        public boolean isCurrent(ResourceRequest request) {
            return !cancelled;
        }

        @Override
        public void close() {}

        @Override
        public void cancelPending() {
            if (pending && !cancelled) {
                cancelled = true;
                cancelCalls.incrementAndGet();
            }
        }
    }

    private static final class OwnedCandidate implements AutoCloseable {
        private final AutoCloseable first;
        private final AutoCloseable second;
        private final AtomicBoolean closed = new AtomicBoolean();

        private OwnedCandidate(AutoCloseable first, AutoCloseable second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void close() throws Exception {
            if (closed.compareAndSet(false, true)) {
                second.close();
                first.close();
            }
        }
    }
}
