package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Adapts the existing resource owners without adding a cache or a work registry. */
public final class ClientDemandResources implements ClientDemandCoordinator.Resources {
    private final ClientModelService service;

    public ClientDemandResources(ClientModelService service) {
        this.service = service;
    }

    @Override
    public ResourceRequest playerRequest(ClientDemandCoordinator.Target target) {
        return service.resourceRequest(target.identity(), "player", target.texture(), ModelResourcePurpose.FULL,
            net.minecraft.client.player.AbstractClientPlayer.class);
    }

    @Override
    public ModelContent content(ModelId modelId) {
        return service.catalog().find(modelId).map(entry -> entry.content()).orElse(null);
    }

    @Override
    public ModelContent content(PublishedModelId modelId) {
        return service.catalog().find(modelId).map(entry -> entry.content()).orElse(null);
    }

    @Override
    public ResourceRequest resourceRequest(ModelId modelId, String target, String texture, ModelResourcePurpose purpose,
        Class<?> subject) {
        return service.resourceRequest(modelId, target, texture, purpose, subject);
    }

    @Override
    public ResourceRequest defaultResourceRequest(String target, ModelResourcePurpose purpose, Class<?> subject) {
        return service.defaultResourceRequest(target, purpose, subject);
    }

    @Override
    public boolean permitsAcquisition(ResourceRequest request) {
        return service.permitsAcquisition(request);
    }

    @Override
    public Optional<ResourceLease> findReady(ResourceRequest request) {
        return service.findReady(request);
    }

    @Override
    public ResourceLease getOrStartCached(ResourceRequest request, WorkPriority priority) {
        return service.getOrStartCached(request, priority);
    }

    @Override
    public ResourceLease getOrStartOffline(ResourceRequest request, WorkPriority priority) {
        return service.getOrStartOffline(request, priority);
    }

    @Override
    public ResourceLease getOrStart(ResourceRequest request, WorkPriority priority) {
        return service.getOrStart(request, priority);
    }

    @Override
    public void reportActiveModelUse(ResourceRequest request) {
        service.reportActiveModelUse(request);
    }

    @Override
    public void reportActiveModelFailure(ModelContent content, boolean fallbackAvailable) {
        service.reportActiveModelFailure(content, fallbackAvailable);
    }
}
