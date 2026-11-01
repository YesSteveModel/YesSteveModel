package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.model.resource.client.work.WorkPriority;

import com.elfmcys.ysm.client.animation.condition.ConditionManager;
import com.elfmcys.ysm.client.animation.condition.FPArmConditionManager;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettingsProto;
import com.elfmcys.ysm.util.FifoHashMap;
import com.elfmcys.ysm.util.UnsafeUtil;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Replaces external resource supply while retaining production binding and installation. */
final class OwnerResourceFixtures implements DemandFixtures.ModelAccess {
    @Override
    public boolean permitsAcquisition(ResourceRequest request) {
        return true;
    }

    private com.elfmcys.ysm.client.demand.ClientDemandCoordinator coordinator;

    @Override
    public com.elfmcys.ysm.client.demand.ClientDemandCoordinator coordinator(java.util.function.LongSupplier clock) {
        if (coordinator == null)
            coordinator = DemandFixtures.coordinator(this, clock);
        return coordinator;
    }

    static final BakeProfile PROFILE = new BakeProfile("owner-fixture");
    final Map<Hash256, ModelRenderTarget> targets = new HashMap<>();
    final ArrayList<Lease> leases = new ArrayList<>();
    int acquisitions;
    int activeReports;
    int failureReports;
    final List<Hash256> failedContentIds = new ArrayList<>();
    final Map<Hash256, CompletableFuture<Optional<ResourceLease>>> pending = new HashMap<>();
    private final Map<Hash256, ResourceRequest> pendingRequests = new HashMap<>();
    private final Map<Hash256, Integer> offlineConsumers = new HashMap<>();
    final Map<Hash256, List<com.elfmcys.ysm.format.schema.model.views.RenderTargetView>> declarations = new HashMap<>();
    private com.elfmcys.ysm.model.catalog.client.ClientCatalogManager catalog;

    static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }

    void attach(AnimatableEntity<?> owner) throws Exception {
        attach(owner, () -> System.nanoTime() / 1_000_000L);
    }

    void attach(AnimatableEntity<?> owner, java.util.function.LongSupplier clock) throws Exception {
        var field = AnimatableEntity.class.getDeclaredField("modelBinding");
        field.setAccessible(true);
        var close = AnimatableEntity.class.getDeclaredMethod("closeDemandScope");
        close.setAccessible(true);
        close.invoke(owner);
        field.set(owner, new EntityModelBinding(this, clock));
        // The fixture replaces the constructor's binding; activate its arm assignment explicitly.
        if (owner instanceof CustomFirstPersonArmEntity arm)
            arm.updateModelHash(arm.getMainModelEntity().getModelHash());
    }

    ModelRenderTarget target(int marker, RenderTargetExpressionModule module, GeoModel... models) throws Exception {
        return target(marker, module, new ResourceLocation("ysm", "fixture" + marker), null, models);
    }

    ModelRenderTarget target(int marker, RenderTargetExpressionModule module, ResourceLocation texture,
        ModelRenderTarget.StateIdentity stateIdentity, GeoModel... models) throws Exception {
        return target(hash(marker), module, texture, stateIdentity, models);
    }

    ModelRenderTarget target(Hash256 id, RenderTargetExpressionModule module, ResourceLocation texture,
        ModelRenderTarget.StateIdentity stateIdentity, GeoModel... models) throws Exception {
        var variants = new HashMap<String, PlayerModelVariant>();
        for (int i = 0; i < models.length; i++)
            variants.put("variant" + i, new PlayerModelVariant(models[i], models[i]));
        var names = variants.keySet().toArray(String[]::new);
        var values = java.util.Arrays.stream(names).map(variants::get).toArray(PlayerModelVariant[]::new);
        var assets = new CommonAsset(Map.of(), TargetSoundResources.empty(new ModelFileIdentity(id, hash(255))),
            new Object2ReferenceOpenHashMap<>(), new Object2ReferenceOpenHashMap<>());
        var resources = new PlayerModelResources(new FifoHashMap<>(names, values), new AnimationStore(),
            new AnimationStore(), new ConditionManager(), new FPArmConditionManager(),
            new Object2ReferenceOpenHashMap<>(), "variant0", assets);
        var info = (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class);
        var player = ModelInfoView.class.getDeclaredField("player");
        player.setAccessible(true);
        player.set(info,
            RenderTargetProto.newBuilder()
                .setSettings(ModelSettingsProto.newBuilder().setWidthScale(1).setHeightScale(1).build())
                .setStats(com.elfmcys.ysm.proto.mixel.manifest.info.ModelStatsProto.newBuilder().build()).build());
        var target = new ModelRenderTarget(id, "player", resources, assets, module, info, assets, module, () -> {
        }, Map.of(), stateIdentity);
        target.adoptTexture(texture, () -> {
        });
        targets.put(id, target);
        return target;
    }

    ModelRenderTarget scopedTarget(int marker, RenderTargetExpressionModule module, GeoModel model,
        com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind kind) throws Exception {
        var id = hash(marker);
        var assets = new CommonAsset(Map.of(), TargetSoundResources.empty(new ModelFileIdentity(id, hash(255))),
            new Object2ReferenceOpenHashMap<>(), new Object2ReferenceOpenHashMap<>());
        RenderTargetResources resources = kind == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE
            ? new ProjectileModelResources(model, new AnimationStore(), new Object2ReferenceOpenHashMap<>(), assets)
            : new VehicleModelResources(model, new AnimationStore(), new Object2ReferenceOpenHashMap<>(), assets);
        var info = (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class);
        var target = new ModelRenderTarget(id, "scoped", resources, assets, module, info, null, null, () -> {
        }, Map.of());
        target.adoptTexture(new ResourceLocation("ysm", "scope" + marker), () -> {
        });
        targets.put(id, target);
        declare(id, kind, true);
        return target;
    }

    void declare(Hash256 id, com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind kind, boolean textured)
        throws Exception {
        var descriptor = RenderTargetProto.newBuilder().setTargetId("scoped").setKind(kind)
            .setSettings(ModelSettingsProto.newBuilder().setWidthScale(1).setHeightScale(1).build())
            .setStats(com.elfmcys.ysm.proto.mixel.manifest.info.ModelStatsProto.newBuilder().build())
            .addMatch(kind == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE ? "minecraft:arrow"
                : "minecraft:boat");
        if (textured)
            descriptor.putTextures("main", com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto.newBuilder()
                .setUv(com.elfmcys.ysm.proto.mixel.common.ImageProto.newBuilder().build()).build());
        declarations.put(id,
            List.of(new com.elfmcys.ysm.format.schema.model.views.RenderTargetView(null, descriptor.build())));
        if (catalog != null)
            publishCatalog();
    }

    AutoCloseable useCatalog() throws Exception {
        var serviceType = com.elfmcys.ysm.model.service.ClientModelService.class;
        var instance = serviceType.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        var previous = instance.get(null);
        var service = UnsafeUtil.getUnsafe().allocateInstance(serviceType);
        catalog = (com.elfmcys.ysm.model.catalog.client.ClientCatalogManager)UnsafeUtil.getUnsafe()
            .allocateInstance(com.elfmcys.ysm.model.catalog.client.ClientCatalogManager.class);
        var manager = serviceType.getDeclaredField("catalogManager");
        manager.setAccessible(true);
        manager.set(service, catalog);
        var demandOwner = serviceType.getDeclaredField("demands");
        demandOwner.setAccessible(true);
        demandOwner.set(service, coordinator(() -> System.nanoTime() / 1_000_000L));
        publishCatalog();
        instance.set(null, service);
        return () -> {
            instance.set(null, previous);
            catalog = null;
        };
    }

    private void publishCatalog() throws Exception {
        var records = new HashMap<Hash256, com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord>();
        for (var id : targets.keySet()) {
            var content = content(id);
            var path = "fixture" + Byte.toUnsignedInt(id.bytes()[0]);
            var entry = new com.elfmcys.ysm.model.catalog.snapshot.CatalogEntry(id,
                new com.elfmcys.ysm.model.domain.HierarchyPath(
                    com.elfmcys.ysm.model.catalog.source.CatalogRoot.LOCAL_CUSTOM.prefix() + "/" + path),
                com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess.PUBLIC,
                com.elfmcys.ysm.model.catalog.snapshot.CatalogPresentation.empty(path));
            var binding = new com.elfmcys.ysm.model.catalog.content.ContentBinding() {
                public Hash256 modelId() {
                    return id;
                }

                public ModelContent content() {
                    return content;
                }
            };
            records.put(id,
                new com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord(entry,
                    new com.elfmcys.ysm.model.catalog.source.CatalogLocation(
                        com.elfmcys.ysm.model.catalog.source.CatalogRootKind.CUSTOM,
                        new com.elfmcys.ysm.model.domain.ModelPath(path)),
                    binding));
        }
        var snapshot = new com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot(
            new com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot(records, List.of(),
                com.elfmcys.ysm.model.domain.ModelScanReport.empty()));
        var field = catalog.getClass().getDeclaredField("catalog");
        field.setAccessible(true);
        field.set(catalog, snapshot);
    }

    @Override
    public ModelContent content(Hash256 id) {
        if (!targets.containsKey(id))
            return null;
        return contents.computeIfAbsent(id, key -> new ModelContent() {
            public Hash256 modelId() {
                return key;
            }

            public ModelRepresentation representation() {
                try {
                    var view = (ModelFileView)UnsafeUtil.getUnsafe().allocateInstance(ModelFileView.class);
                    var declared = ModelFileView.class.getDeclaredField("renderTargets");
                    declared.setAccessible(true);
                    var targets = declarations.getOrDefault(key, List.of());
                    declared.set(view, targets);
                    var byId = ModelFileView.class.getDeclaredField("renderTargetsById");
                    byId.setAccessible(true);
                    byId.set(view, targets.stream().collect(java.util.stream.Collectors
                        .toMap(com.elfmcys.ysm.format.schema.model.views.RenderTargetView::id, value -> value)));
                    var representation =
                        (ModelRepresentation)UnsafeUtil.getUnsafe().allocateInstance(ModelRepresentation.class);
                    var field = ModelRepresentation.class.getDeclaredField("view");
                    field.setAccessible(true);
                    field.set(representation, view);
                    var identity = ModelRepresentation.class.getDeclaredField("identity");
                    identity.setAccessible(true);
                    identity.set(representation, new ModelFileIdentity(key, hash(255)));
                    return representation;
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            }

            public ModelFileView modelFile() {
                return null;
            }

            public ChunkDataSource chunks() {
                return null;
            }
        });
    }

    private final Map<Hash256, ModelContent> contents = new HashMap<>();

    @Override
    public ResourceRequest resourceRequest(Hash256 id, String target, String texture) {
        var available = targets.get(id);
        if (available == null)
            return new ResourceRequest(id, target, texture, PROFILE);
        var module = available.expressions();
        return new ResourceRequest(id, target, texture, PROFILE, module.expressionBinding());
    }

    @Override
    public ResourceRequest defaultResourceRequest(String target) {
        return resourceRequest(hash(0), target, "");
    }

    @Override
    public Optional<ResourceLease> findReady(ResourceRequest request) {
        if (pending.containsKey(request.modelId()))
            return Optional.empty();
        var target = targets.get(request.modelId());
        if (target == null)
            return Optional.empty();
        var previous = leases.stream().filter(lease -> lease.closes == 0 && lease.request.equals(request)).findFirst()
            .orElse(null);
        if (previous == null)
            acquisitions++;
        var lease = new Lease(request, target);
        if (previous != null) {
            lease.provisional = false;
            if (previous.provisional)
                leases.remove(previous);
        }
        leases.add(lease);
        return Optional.of(lease);
    }

    @Override
    public ResourceLease getOrStart(ResourceRequest request, WorkPriority priority) {
        if (pending.containsKey(request.modelId())) {
            acquisitions++;
            var lease =
                new Lease(request, targets.get(request.modelId()), () -> !pending.containsKey(request.modelId()));
            leases.add(lease);
            return lease;
        }
        return findReady(request).orElseThrow(() -> new AssertionError("Unexpected unavailable resource: " + request));
    }

    @Override
    public CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request, WorkPriority priority) {
        if (pending.containsKey(request.modelId()))
            return getOrStartOffline(request, priority);
        return CompletableFuture.completedFuture(findReady(request));
    }

    @Override
    public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request,
        WorkPriority priority) {
        var delayed = pending.get(request.modelId());
        if (delayed != null) {
            acquisitions++;
            pendingRequests.put(request.modelId(), request);
            if (offlineConsumers.merge(request.modelId(), 1, Integer::sum) > 1) {
                // A retired subscriber cannot close a replacement subscriber's independent lease.
                return delayed.thenApply(outcome -> outcome.map(supply -> {
                    var lease = new Lease(request, ((Lease)supply).target);
                    leases.add(lease);
                    return lease;
                }));
            }
            return delayed;
        }
        return CompletableFuture.completedFuture(findReady(request));
    }

    @Override
    public void reportActiveModelUse(ResourceRequest request) {
        activeReports++;
    }

    @Override
    public void reportActiveModelFailure(ModelContent content, boolean fallback) {
        failureReports++;
        failedContentIds.add(content.modelId());
    }

    CompletableFuture<Optional<ResourceLease>> delay(Hash256 id) {
        var future = new CompletableFuture<Optional<ResourceLease>>() {
            @Override
            public boolean cancel(boolean mayInterrupt) {
                return false;
            }
        };
        pending.put(id, future);
        offlineConsumers.remove(id);
        return future;
    }

    Lease deliver(Hash256 id) {
        var future = pending.remove(id);
        var request = pendingRequests.remove(id);
        var lease = new Lease(request, targets.get(id));
        leases.add(lease);
        future.complete(Optional.of(lease));
        return lease;
    }

    static final class Lease implements ResourceLease {
        final ResourceRequest request;
        final ModelRenderTarget target;
        final java.util.function.BooleanSupplier ready;
        int closes;
        boolean provisional = true;
        Runnable onClose = () -> {
        };

        Lease(ResourceRequest request, ModelRenderTarget target) {
            this(request, target, () -> true);
        }

        Lease(ResourceRequest request, ModelRenderTarget target, java.util.function.BooleanSupplier ready) {
            this.request = request;
            this.target = target;
            this.ready = ready;
        }

        @Override
        public ModelResourcePurpose purpose() {
            return request.purpose();
        }

        @Override
        public AcquireResult poll() {
            if (closes != 0)
                throw new IllegalStateException("Lease closed");
            return ready.getAsBoolean() ? new AcquireResult.Ready(target) : new AcquireResult.Pending();
        }

        @Override
        public boolean isCurrent(ResourceRequest request) {
            return closes == 0 && this.request.equals(request);
        }

        @Override
        public void close() {
            if (closes == 0) {
                closes++;
                onClose.run();
            }
        }

        @Override
        public void cancelPending() {
            close();
        }
    }
}
