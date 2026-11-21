package com.elfmcys.ysm.model.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.elfmcys.ysm.model.catalog.content.ContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogPresentation;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRoot;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.storage.PlayerPreferences.LocalSelection;
import com.elfmcys.ysm.model.storage.StorageQuery;
import com.elfmcys.ysm.model.storage.StorageQueryTestAccess;
import com.elfmcys.ysm.natives.storage.StorageResult;
import com.elfmcys.ysm.network.session.SessionMode;
import com.elfmcys.ysm.util.UnsafeUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class StartupModelWarmupTest {
    private static final UUID PLAYER = UUID.fromString("845f9528-cf47-4490-b64f-443c47a3c902");
    private static final ModelId BUILTIN = ModelId.parse("builtin/default@1.0.0");
    private static final ModelId SELECTED = ModelId.parse("author/avatar@2.0.0");
    private static final LocalSelection SAVED = new LocalSelection(PublishedModelId.model(SELECTED), "saved-texture");

    @Test
    void autoMenuUsesOrdinaryBuiltinWithoutReadingPlayerPreferences() {
        try (var fixture = new Fixture(SessionMode.AUTO)) {
            var ordinary = record(BUILTIN, CatalogLocation.BUILTIN_DEFAULT);
            fixture.snapshot =
                new CatalogSnapshot(java.util.Map.of(BUILTIN, ordinary), ordinary, List.of(), ModelScanReport.empty());
            fixture.warmup.tick();
            assertTrue(fixture.readPlayers.isEmpty());
            assertEquals(List.of(new LocalSelection(PublishedModelId.model(BUILTIN), "")), fixture.acquired);
            assertFalse(fixture.acquired.get(0).identity().isIntrinsicDefault());
            assertEquals(1, fixture.subscriptionCloses);
            assertEquals(0, fixture.lease.closes);
        }
    }

    @Test
    void inlineCatalogReplayOnlySignalsUntilSubscriptionHasReturned() {
        try (var fixture = new Fixture(SessionMode.AUTO)) {
            fixture.snapshot = builtin();
            fixture.insideSubscribe = () -> {
                assertTrue(fixture.acquired.isEmpty());
                assertEquals(0, fixture.subscriptionCloses);
            };
            fixture.warmup.tick();
            assertEquals(1, fixture.acquired.size());
            assertEquals(1, fixture.subscriptionCloses);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectionAndCatalogConvergeInEitherArrivalOrder(boolean queryFirst) {
        try (var fixture = new Fixture(SessionMode.LOCAL)) {
            fixture.warmup.tick();
            assertEquals(List.of(PLAYER), fixture.readPlayers);
            if (queryFirst) {
                fixture.complete(found(SAVED));
                fixture.warmup.tick();
                assertTrue(fixture.query.poll().closed());
            } else {
                fixture.publish(selected());
                fixture.warmup.tick();
            }
            assertTrue(fixture.acquired.isEmpty());
            if (queryFirst)
                fixture.publish(selected());
            else
                fixture.complete(found(SAVED));
            assertTrue(fixture.acquired.isEmpty());
            fixture.warmup.tick();
            assertEquals(List.of(SAVED), fixture.acquired);
            assertTrue(fixture.query.poll().closed());
            assertEquals(1, fixture.subscriptionCloses);
        }
    }

    @Test
    void savedFullIdentityWaitsThroughOtherVersionsAndBuiltinPublications() {
        try (var fixture = new Fixture(SessionMode.LOCAL)) {
            fixture.complete(found(SAVED));
            fixture.publish(builtin());
            fixture.warmup.tick();
            fixture.publish(
                snapshot(record(ModelId.parse("author/avatar@1.0.0"), new CatalogLocation(CatalogRoot.LOCAL, "avatar")),
                    record(BUILTIN, CatalogLocation.BUILTIN_DEFAULT)));
            fixture.warmup.tick();
            fixture.publish(CatalogSnapshot.empty());
            fixture.warmup.tick();
            assertTrue(fixture.acquired.isEmpty());
            assertEquals(0, fixture.subscriptionCloses);
            assertTrue(fixture.failures.isEmpty());
            fixture.publish(selected());
            fixture.warmup.tick();
            assertEquals(List.of(SAVED), fixture.acquired);
        }
    }

    @ParameterizedTest
    @EnumSource(Fallback.class)
    void missingOrFailedPreferenceFallsBackToOrdinaryBuiltin(Fallback fallback) {
        try (var fixture = new Fixture(SessionMode.LOCAL)) {
            fixture.snapshot = builtin();
            var cause = new IllegalStateException("preference read");
            switch (fallback) {
                case MISSING -> fixture.complete(result(StorageResult.Outcome.MISSING, StorageResult.Failure.NONE));
                case FAILED -> fixture.complete(result(StorageResult.Outcome.FAILED, StorageResult.Failure.ACCESS_IO));
                case REJECTED -> fixture.complete(StorageResult.refused(StorageResult.Failure.CAPACITY));
                case STORAGE_CLOSED -> fixture.complete(StorageResult.refused(StorageResult.Failure.CLOSED));
                case THROWN -> fixture.readFailure = cause;
                case ASYNC_FAILURE -> StorageQueryTestAccess.publish(fixture.query, null, cause);
            }
            fixture.warmup.tick();
            assertEquals(List.of(new LocalSelection(PublishedModelId.model(BUILTIN), "")), fixture.acquired);
            assertEquals(1, fixture.readPlayers.size());
            if (fallback == Fallback.MISSING)
                assertTrue(fixture.failures.isEmpty());
            else {
                assertEquals(1, fixture.failures.size());
                if (fallback == Fallback.THROWN || fallback == Fallback.ASYNC_FAILURE)
                    assertSame(cause, fixture.failures.get(0));
            }
        }
    }

    @Test
    void explicitIntrinsicSelectionEndsWithoutAnOrdinaryRequest() {
        try (var fixture = new Fixture(SessionMode.LOCAL)) {
            fixture.snapshot = builtin();
            fixture.complete(found(new LocalSelection(PublishedModelId.INTRINSIC_DEFAULT, "")));
            fixture.warmup.tick();
            fixture.publish(selected());
            fixture.warmup.tick();
            assertTrue(fixture.acquired.isEmpty());
            assertTrue(fixture.query.poll().closed());
            assertEquals(1, fixture.subscriptionCloses);
            assertTrue(fixture.failures.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(CancelledRead.class)
    void cancelledOrClosedQueryEndsWithoutFallbackOrWarning(CancelledRead cancelled) {
        try (var fixture = new Fixture(SessionMode.LOCAL)) {
            fixture.snapshot = builtin();
            switch (cancelled) {
                case OUTCOME -> fixture.complete(result(StorageResult.Outcome.CANCELLED, StorageResult.Failure.NONE));
                case FAILURE -> fixture.complete(result(StorageResult.Outcome.FAILED, StorageResult.Failure.CANCELLED));
                case EXCEPTION -> StorageQueryTestAccess.publish(fixture.query, null, new CancellationException());
                case WRAPPED_EXCEPTION -> StorageQueryTestAccess.publish(fixture.query, null,
                    new CompletionException(new CancellationException()));
                case SYNCHRONOUS -> fixture.readFailure = new CancellationException();
                case HANDLE_CLOSED -> fixture.query.close();
            }
            fixture.warmup.tick();
            fixture.publish(builtin());
            fixture.warmup.tick();
            assertTrue(fixture.acquired.isEmpty());
            assertEquals(1, fixture.subscriptionCloses);
            assertTrue(fixture.failures.isEmpty());
        }
    }

    @Test
    void playerAlreadyPresentConsumesOpportunityWithoutStartingInputs() {
        try (var fixture = new Fixture(SessionMode.LOCAL)) {
            fixture.host = new StartupModelWarmup.HostState(true, SessionMode.LOCAL, PLAYER);
            fixture.warmup.tick();
            fixture.host = menu(SessionMode.LOCAL);
            fixture.publish(builtin());
            fixture.complete(found(SAVED));
            fixture.warmup.tick();
            assertEquals(0, fixture.subscriptions);
            assertTrue(fixture.readPlayers.isEmpty());
            assertTrue(fixture.acquired.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(HostChange.class)
    void waitingForQueryEndsWhenPlayerModeOrAccountChanges(HostChange change) {
        try (var fixture = new Fixture(SessionMode.LOCAL)) {
            fixture.warmup.tick();
            fixture.host = change.host();
            fixture.warmup.tick();
            assertTrue(fixture.query.poll().closed());
            assertEquals(1, fixture.subscriptionCloses);
            fixture.host = menu(SessionMode.LOCAL);
            fixture.complete(found(SAVED));
            fixture.publish(selected());
            fixture.warmup.tick();
            assertTrue(fixture.acquired.isEmpty());
            assertTrue(fixture.failures.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(HostChange.class)
    void waitingForCatalogEndsWhenPlayerModeOrAccountChanges(HostChange change) {
        try (var fixture = new Fixture(SessionMode.LOCAL)) {
            fixture.complete(found(SAVED));
            fixture.warmup.tick();
            fixture.host = change.host();
            fixture.publish(selected());
            fixture.warmup.tick();
            fixture.host = menu(SessionMode.LOCAL);
            fixture.publish(selected());
            fixture.warmup.tick();
            assertTrue(fixture.acquired.isEmpty());
            assertEquals(1, fixture.subscriptionCloses);
        }
    }

    @Test
    void acquisitionRechecksHostAfterUnsubscription() {
        try (var fixture = new Fixture(SessionMode.AUTO)) {
            fixture.snapshot = builtin();
            fixture.onUnsubscribe =
                () -> fixture.host = new StartupModelWarmup.HostState(true, SessionMode.AUTO, PLAYER);
            fixture.warmup.tick();
            assertEquals(1, fixture.subscriptionCloses);
            assertTrue(fixture.acquired.isEmpty());
        }
    }

    @Test
    void repeatedNotificationsAndReloadsNeverSubmitASecondLease() {
        try (var fixture = new Fixture(SessionMode.AUTO)) {
            fixture.snapshot = builtin();
            fixture.warmup.tick();
            for (int index = 0; index < 3; index++) {
                fixture.publish(builtin());
                fixture.warmup.tick();
            }
            fixture.lease.result = new AcquireResult.Missing();
            fixture.warmup.tick();
            fixture.publish(builtin());
            fixture.warmup.tick();
            assertEquals(1, fixture.acquired.size());
            assertEquals(1, fixture.subscriptionCloses);
            assertEquals(1, fixture.lease.closes);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deniedOrSynchronouslyFailedAcquisitionConsumesTheOnlyAttempt(boolean denied) {
        try (var fixture = new Fixture(SessionMode.AUTO)) {
            fixture.snapshot = builtin();
            fixture.denied = denied;
            var cause = new IllegalStateException("resource admission");
            fixture.acquireFailure = denied ? null : cause;
            fixture.warmup.tick();
            fixture.publish(builtin());
            fixture.warmup.tick();
            assertEquals(1, fixture.acquired.size());
            assertEquals(1, fixture.subscriptionCloses);
            assertEquals(0, fixture.lease.closes);
            assertEquals(denied ? List.of() : List.of(cause), fixture.failures);
        }
    }

    @ParameterizedTest
    @EnumSource(Terminal.class)
    void pendingHoldsTheLeaseAndEachTerminalOutcomeReleasesItOnce(Terminal terminal) {
        try (var fixture = new Fixture(SessionMode.AUTO)) {
            fixture.snapshot = builtin();
            fixture.warmup.tick();
            fixture.host = new StartupModelWarmup.HostState(true, SessionMode.LOCAL, UUID.randomUUID());
            fixture.warmup.tick();
            assertEquals(0, fixture.lease.closes);
            var cause = new IllegalStateException("preparation failed");
            fixture.lease.result = switch (terminal) {
                case READY -> new AcquireResult.Ready(target());
                case MISSING -> new AcquireResult.Missing();
                case FAILED -> new AcquireResult.Failed(new ResourceFailure(ResourceFailure.Kind.DETERMINISTIC, cause));
                case ENDED -> new AcquireResult.Ended();
            };
            fixture.warmup.tick();
            assertEquals(1, fixture.lease.closes);
            fixture.warmup.close();
            fixture.publish(builtin());
            fixture.warmup.tick();
            assertEquals(1, fixture.acquired.size());
            assertEquals(1, fixture.lease.closes);
            assertEquals(terminal == Terminal.FAILED ? List.of(cause) : List.of(), fixture.failures);
        }
    }

    @ParameterizedTest
    @EnumSource(ClosePhase.class)
    void constructionIsInertAndCloseSealsEveryPhaseAgainstLateResults(ClosePhase phase) {
        try (var fixture = new Fixture(SessionMode.LOCAL)) {
            assertEquals(0, fixture.subscriptions);
            assertTrue(fixture.readPlayers.isEmpty());
            if (phase != ClosePhase.NEW) {
                if (phase == ClosePhase.CATALOG || phase == ClosePhase.LOADING)
                    fixture.complete(found(SAVED));
                if (phase == ClosePhase.LOADING)
                    fixture.snapshot = selected();
                fixture.warmup.tick();
            }
            fixture.warmup.close();
            fixture.warmup.close();
            fixture.complete(found(SAVED));
            fixture.publish(selected());
            fixture.lease.result = new AcquireResult.Ready(target());
            fixture.warmup.tick();
            assertEquals(phase == ClosePhase.LOADING ? 1 : 0, fixture.acquired.size());
            assertEquals(phase == ClosePhase.NEW ? 0 : 1, fixture.subscriptionCloses);
            assertEquals(phase == ClosePhase.LOADING ? 1 : 0, fixture.lease.closes);
            if (phase != ClosePhase.NEW)
                assertTrue(fixture.query.poll().closed());
            else {
                assertEquals(0, fixture.subscriptions);
                assertTrue(fixture.readPlayers.isEmpty());
            }
            assertTrue(fixture.failures.isEmpty());
        }
    }

    private enum Fallback {
        MISSING, FAILED, REJECTED, STORAGE_CLOSED, THROWN, ASYNC_FAILURE
    }

    private enum CancelledRead {
        OUTCOME, FAILURE, EXCEPTION, WRAPPED_EXCEPTION, SYNCHRONOUS, HANDLE_CLOSED
    }

    private enum Terminal {
        READY, MISSING, FAILED, ENDED
    }

    private enum ClosePhase {
        NEW, QUERY, CATALOG, LOADING
    }

    private enum HostChange {
        PLAYER_APPEARED, MODE_CHANGED, ACCOUNT_CHANGED;

        StartupModelWarmup.HostState host() {
            return switch (this) {
                case PLAYER_APPEARED -> new StartupModelWarmup.HostState(true, SessionMode.LOCAL, PLAYER);
                case MODE_CHANGED -> menu(SessionMode.AUTO);
                case ACCOUNT_CHANGED -> new StartupModelWarmup.HostState(false, SessionMode.LOCAL, UUID.randomUUID());
            };
        }
    }

    private static StartupModelWarmup.HostState menu(SessionMode mode) {
        return new StartupModelWarmup.HostState(false, mode, PLAYER);
    }

    private static StorageResult<LocalSelection> found(LocalSelection selection) {
        return new StorageResult<>(StorageResult.Outcome.FOUND, StorageResult.Failure.NONE, false, List.of(selection));
    }

    private static StorageResult<LocalSelection> result(StorageResult.Outcome outcome, StorageResult.Failure failure) {
        return new StorageResult<>(outcome, failure, false, List.of());
    }

    private static CatalogSnapshot builtin() {
        return snapshot(record(BUILTIN, CatalogLocation.BUILTIN_DEFAULT));
    }

    private static CatalogSnapshot selected() {
        return snapshot(record(SELECTED, new CatalogLocation(CatalogRoot.LOCAL, "avatar")));
    }

    private static CatalogSnapshot snapshot(CatalogRecord... records) {
        var byModel = new LinkedHashMap<ModelId, CatalogRecord>();
        for (var record : records)
            byModel.put(record.entry().modelId(), record);
        return new CatalogSnapshot(byModel, List.of(), ModelScanReport.empty());
    }

    private static CatalogRecord record(ModelId identity, CatalogLocation location) {
        var binding = new ContentBinding() {
            @Override
            public ModelId modelId() {
                return identity;
            }

            @Override
            public ModelContent content() {
                throw new AssertionError("Target selection must not load model content");
            }
        };
        var entry = new CatalogEntry(identity, new HierarchyPath(location.value()), CatalogAccess.PUBLIC,
            CatalogPresentation.empty(identity.toString()));
        return new CatalogRecord(entry, location, binding);
    }

    private static ModelRenderTarget target() {
        try {
            return (ModelRenderTarget)UnsafeUtil.getUnsafe().allocateInstance(ModelRenderTarget.class);
        } catch (InstantiationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class Fixture implements StartupModelWarmup.Inputs, AutoCloseable {
        private StartupModelWarmup.HostState host;
        private CatalogSnapshot snapshot = CatalogSnapshot.empty();
        private final StorageQuery<LocalSelection> query = new StorageQuery<>();
        private final FakeLease lease = new FakeLease();
        private final List<UUID> readPlayers = new ArrayList<>();
        private final List<LocalSelection> acquired = new ArrayList<>();
        private final List<Throwable> failures = new ArrayList<>();
        private final StartupModelWarmup warmup;
        private Runnable changed = () -> {
        };
        private Runnable insideSubscribe = () -> {
        };
        private Runnable onUnsubscribe = () -> {
        };
        private RuntimeException readFailure;
        private RuntimeException acquireFailure;
        private boolean denied;
        private int subscriptions;
        private int subscriptionCloses;

        Fixture(SessionMode mode) {
            host = menu(mode);
            warmup = new StartupModelWarmup(() -> host, this, failures::add);
        }

        @Override
        public AutoCloseable subscribe(Runnable listener) {
            subscriptions++;
            changed = listener;
            listener.run();
            insideSubscribe.run();
            return () -> {
                subscriptionCloses++;
                onUnsubscribe.run();
            };
        }

        @Override
        public CatalogSnapshot catalog() {
            return snapshot;
        }

        @Override
        public StorageQuery<LocalSelection> readSelection(UUID playerId) {
            readPlayers.add(playerId);
            if (readFailure != null)
                throw readFailure;
            return query;
        }

        @Override
        public ResourceLease acquire(LocalSelection selection) {
            acquired.add(selection);
            if (acquireFailure != null)
                throw acquireFailure;
            return denied ? null : lease;
        }

        void publish(CatalogSnapshot next) {
            snapshot = next;
            changed.run();
        }

        void complete(StorageResult<LocalSelection> result) {
            StorageQueryTestAccess.publish(query, result, null);
        }

        @Override
        public void close() {
            warmup.close();
        }
    }

    private static final class FakeLease implements ResourceLease {
        private AcquireResult result = new AcquireResult.Pending();
        private int closes;

        @Override
        public ModelResourcePurpose purpose() {
            return ModelResourcePurpose.FULL;
        }

        @Override
        public AcquireResult poll() {
            return closes == 0 ? result : new AcquireResult.Ended();
        }

        @Override
        public boolean isCurrent(ResourceRequest request) {
            return closes == 0;
        }

        @Override
        public void cancelPending() {
            close();
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
