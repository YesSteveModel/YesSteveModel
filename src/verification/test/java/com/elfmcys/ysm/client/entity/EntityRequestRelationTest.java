package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.content.ContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.*;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRoot;
import com.elfmcys.ysm.model.domain.*;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.resource.client.render.RenderTargetKey;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.*;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.LicenseProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.MetadataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStatsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.UnsafeUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntityRequestRelationTest {
    @Test
    void currentManifestAndCatalogPresenceInvalidateTheGuiRelationBeforeConsumption() throws Exception {
        var access = new CatalogAccess();
        var id = ModelId.fromHash(new Hash256(new byte[Hash256.SIZE]));
        access.publish(id, "first", "first", "second");
        try (var binding = new EntityModelBinding(access)) {
            binding.updateModelHash(id);
            var resolutions = install(binding, access, id, "first");
            assertEquals(AnimatableEntity.InstallationRelation.MATCHING,
                binding.relation("player", "", ModelResourcePurpose.GUI, null));
            int initial = resolutions[0];
            for (int i = 0; i < 20; i++)
                assertEquals(AnimatableEntity.InstallationRelation.MATCHING,
                    binding.relation("player", "", ModelResourcePurpose.GUI, null));
            assertEquals(initial, resolutions[0]);
            access.publish(id, "second", "first", "second");
            assertEquals(AnimatableEntity.InstallationRelation.RETAINED,
                binding.relation("player", "", ModelResourcePurpose.GUI, null));
            install(binding, access, id, "second");
            assertEquals(AnimatableEntity.InstallationRelation.MATCHING,
                binding.relation("player", "", ModelResourcePurpose.GUI, null));
            access.publish(id, "missing", "first", "second");
            assertEquals(AnimatableEntity.InstallationRelation.RETAINED,
                binding.relation("player", "", ModelResourcePurpose.GUI, null));
            install(binding, access, id, "first");
            assertEquals(AnimatableEntity.InstallationRelation.MATCHING,
                binding.relation("player", "", ModelResourcePurpose.GUI, null));
            access.publish(id, "first");
            assertEquals(AnimatableEntity.InstallationRelation.RETAINED,
                binding.relation("player", "", ModelResourcePurpose.GUI, null));
            access.publish(id, "first", "first");
            assertEquals(AnimatableEntity.InstallationRelation.RETAINED,
                binding.relation("player", "", ModelResourcePurpose.GUI, null));
            install(binding, access, id, "first");
            assertEquals(AnimatableEntity.InstallationRelation.MATCHING,
                binding.relation("player", "", ModelResourcePurpose.GUI, null));
            var lease = binding.resourceHolder().lease();
            access.publishSnapshot(Map.of());
            assertEquals(AnimatableEntity.InstallationRelation.RETAINED,
                binding.relation("player", "", ModelResourcePurpose.GUI, null));
            assertSame(lease, binding.resourceHolder().lease());
            assertTrue(binding.permitsCurrentUse());
        }
    }

    static int[] install(EntityModelBinding binding, CatalogAccess access, ModelId id, String texture) {
        var request = access.resourceRequest(id, "player", "", ModelResourcePurpose.GUI, null);
        var key = ClientModelRenderTargetManager.resolveEffectiveKey(access.manifest, request);
        assertEquals(texture, key.selectedTexture());
        var identity = access.content(id).representation().identity();
        int[] resolutions = {0};
        var lease = new ResourceLease() {
            public ModelResourcePurpose purpose() {
                return ModelResourcePurpose.GUI;
            }

            public AcquireResult poll() {
                return new AcquireResult.Ready(access.target);
            }

            public boolean isCurrent(ResourceRequest candidate) {
                resolutions[0]++;
                return access.current(identity, key, candidate);
            }

            public void close() {}

            public void cancelPending() {}
        };
        assertEquals(com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult.INSTALLED, binding.accept(
            request, lease, false, false, (candidate, fallback) -> new AnimatableEntity.ResourceHolder(candidate)));
        return resolutions;
    }

    static final class CatalogAccess implements EntityModelBinding.ModelAccess {
        private final ClientCatalogManager catalogs = allocate(ClientCatalogManager.class);
        private final ClientModelRenderTargetManager manager = allocate(ClientModelRenderTargetManager.class);
        private final ModelRenderTarget target = allocate(ModelRenderTarget.class);
        private ManifestProto manifest;
        private int publication;

        CatalogAccess() throws Exception {
            set(manager, "catalogs", catalogs);
            set(manager, "bakeProfile", new BakeProfile("relation"));
            var binding = ExpressionBinding.standalone(dev.semaweave.compilation.BindingPlan.identity());
            set(manager, "subjectBindings",
                Map.of(com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class, binding));
            set(manager, "defaultSubjectBinding", binding);
        }

        void publish(ModelId id, String defaultTexture, String... textures) throws Exception {
            var target =
                RenderTargetProto.newBuilder().setTargetId("player").setKind(RenderTargetKind.PLAYER).setBlobId(0)
                    .setSettings(
                        ModelSettingsProto.newBuilder().setHeightScale(1).setWidthScale(1).setRenderLayersFirst(false)
                            .setForceCulling(false).setGuiNoLighting(false).setMergeMultilineExpr(false).build())
                    .setStats(ModelStatsProto.newBuilder().setBones(0).setCubes(0).setFaces(0).build());
            for (var texture : textures)
                target.putTextures(texture, PBRTextureSetProto.newBuilder().setUv(ImageProto.newBuilder().setBlobId(0)
                    .setFormat("").setWidth(0).setHeight(0).setFrameCount(0).build()).build());
            manifest = ManifestProto.newBuilder().addRenderTargets(target.build())
                .setInfo(InfoProto.newBuilder()
                    .setSettings(SettingsProto.newBuilder().setDefaultTexture(defaultTexture).setPreviewAnimation("")
                        .setDisablePreviewRotation(false).build())
                    .setProperties(
                        PropertiesProto.newBuilder().setModelId(id.toString()).setFree(false).setOriginVer("").build())
                    .setMetadata(MetadataProto.newBuilder().setName("").setTips("")
                        .setLicense(LicenseProto.newBuilder().setType("").setDesc("").build()).build())
                    .build())
                .setCommonAssets(CommonProto.newBuilder().setStringsBlobId(0).build()).build();
            var view = allocate(ModelFileView.class);
            set(view, "manifest", manifest);
            var representation = allocate(ModelRepresentation.class);
            set(representation, "view", view);
            var bytes = new byte[Hash256.SIZE];
            bytes[0] = (byte)++publication;
            set(representation, "identity", new ModelFileIdentity(id, new Hash256(bytes)));
            ModelContent content = new ModelContent() {
                public ModelRepresentation representation() {
                    return representation;
                }

                public ChunkDataSource chunks() {
                    throw new AssertionError("Relation must not read chunks");
                }
            };
            var location = new CatalogLocation(CatalogRoot.LOCAL, "model");
            var entry = new CatalogEntry(id, new HierarchyPath(location.value()),
                com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess.PUBLIC, CatalogPresentation.empty("model"));
            publishSnapshot(Map.of(id, new CatalogRecord(entry, location, new ContentBinding() {
                public ModelId modelId() {
                    return id;
                }

                public ModelContent content() {
                    return content;
                }
            })));
        }

        void publishSnapshot(Map<ModelId, CatalogRecord> records) throws Exception {
            set(catalogs, "catalog",
                new ClientCatalogSnapshot(new CatalogSnapshot(records, List.of(), ModelScanReport.empty())));
        }

        boolean current(ModelFileIdentity identity, RenderTargetKey key, ResourceRequest request) {
            try {
                var method = ClientModelRenderTargetManager.class.getDeclaredMethod("isCurrent",
                    ModelFileIdentity.class, RenderTargetKey.class, ResourceRequest.class);
                method.setAccessible(true);
                return (boolean)method.invoke(manager, identity, key, request);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        public ModelContent content(ModelId id) {
            return catalogs.snapshot().find(id).map(value -> value.content()).orElse(null);
        }

        public ResourceRequest resourceRequest(ModelId id, String target, String texture) {
            return resourceRequest(id, target, texture, ModelResourcePurpose.GUI, null);
        }

        public ResourceRequest resourceRequest(ModelId id, String target, String texture, ModelResourcePurpose purpose,
            Class<?> subject) {
            return manager.request(id, target, texture, purpose, subject);
        }

        public ResourceRequest defaultResourceRequest(String target) {
            return manager.defaultRequest(target);
        }

        public void reportActiveModelFailure(ModelContent content, boolean fallback) {
            throw new AssertionError("No installation failure expected");
        }
    }

    private static <T> T allocate(Class<T> type) {
        try {
            return type.cast(UnsafeUtil.getUnsafe().allocateInstance(type));
        } catch (InstantiationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void set(Object receiver, String name, Object value) throws Exception {
        var field = receiver.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(receiver, value);
    }
}
