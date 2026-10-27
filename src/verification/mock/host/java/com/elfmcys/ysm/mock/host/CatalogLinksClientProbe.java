package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL11;

final class CatalogLinksClientProbe {
    private static ResourceLease first;
    private static ResourceLease second;
    private static String pixels;
    private static com.elfmcys.ysm.model.domain.Hash256 acquiredModel;
    private static String acquiredPath;
    private final HostAction action;
    private CompletableFuture<?> reload;
    private boolean started;

    CatalogLinksClientProbe(HostAction action) {
        this.action = action;
    }

    Map<String, ?> poll() throws Exception {
        var service = ClientModelService.instance();
        return switch (action.name()) {
            case "catalog-snapshot" -> snapshot();
            case "catalog-repeat-integrated" -> {
                if (reload == null) {
                    if (CatalogLinksHostProbe.snapshot() == null)
                        yield null;
                    var server = Minecraft.getInstance().getSingleplayerServer();
                    reload = server.submit(() -> {
                        try {
                            var before = CatalogLinksHostProbe.snapshot();
                            var current = com.elfmcys.ysm.model.service.ServerModelService.instance();
                            if (server.isDedicatedServer() || com.elfmcys.ysm.model.service.ServerModelService.start(server) != current)
                                throw new IllegalStateException("Integrated service identity changed");
                            if (!before.equals(CatalogLinksHostProbe.snapshot()))
                                throw new IllegalStateException("Repeated integrated start admitted work");
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
                }
                if (!reload.isDone())
                    yield null;
                reload.join();
                yield snapshot();
            }
            case "catalog-local-reload" -> {
                if (reload == null)
                    reload = ModelRuntime.system().catalog().reload();
                if (!reload.isDone())
                    yield null;
                reload.join();
                yield snapshot();
            }
            case "catalog-leases-acquire" -> {
                if (!started) {
                    acquiredPath = action.require("path");
                    acquiredModel = service.resolvePath(acquiredPath).orElseThrow();
                    var request = service.resourceRequest(acquiredModel, "player", "");
                    first = service.getOrStart(request);
                    second = service.getOrStart(request);
                    started = true;
                }
                if (!(first.poll() instanceof AcquireResult.Ready) || !(second.poll() instanceof AcquireResult.Ready)) {
                    if (first.poll() instanceof AcquireResult.Failed || second.poll() instanceof AcquireResult.Failed)
                        throw new IllegalStateException("Fixture render resource failed: " + second.poll());
                    yield null;
                }
                pixels = texturePixels(second);
                yield Map.of("leases", 2, "gpuPixelsSha256", pixels, "modelId", acquiredModel.toString());
            }
            case "catalog-leases-use", "catalog-lease-release-first" -> {
                var current = service.resolvePath(acquiredPath).orElseThrow();
                if (current.equals(acquiredModel))
                    throw new IllegalStateException("The retained resource has not retired from the current catalog");
                if (action.name().equals("catalog-lease-release-first")) {
                    first.close();
                    first = null;
                }
                var actual = texturePixels(second);
                if (!actual.equals(pixels))
                    throw new IllegalStateException("Retained texture pixels changed");
                yield Map.of("gpuPixelsSha256", actual, "peerReleased", first == null,
                    "retiredModelId", acquiredModel.toString(), "currentModelId", current.toString());
            }
            case "catalog-leases-close" -> {
                if (first != null)
                    first.close();
                if (second != null)
                    second.close();
                first = null;
                second = null;
                yield Map.of("closed", true);
            }
            case "catalog-disconnect" -> {
                if (!started) {
                    Minecraft.getInstance().getConnection().getConnection().disconnect(Component.literal("Catalog verification"));
                    started = true;
                }
                if (Minecraft.getInstance().player != null)
                    yield null;
                yield snapshot();
            }
            default -> throw new IllegalArgumentException(action.name());
        };
    }

    private static Map<String, ?> snapshot() throws Exception {
        var facts = CatalogLinksHostProbe.snapshot();
        if (facts == null)
            return null;
        var service = ClientModelService.instance();
        facts.put("visiblePaths", service.catalog().models().values().stream().map(entry -> entry.displayPath()).sorted().toList());
        facts.put("sessionState", ClientSessionRuntime.state().map(Enum::name).orElse("LOCAL"));
        return facts;
    }

    private static String texturePixels(ResourceLease lease) throws Exception {
        var target = ((AcquireResult.Ready)lease.poll()).target();
        var texture = Minecraft.getInstance().getTextureManager().getTexture(target.textureId());
        var previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        com.mojang.blaze3d.systems.RenderSystem.bindTexture(texture.getId());
        try {
            if (!GL11.glIsTexture(texture.getId()))
                throw new IllegalStateException("Retained GPU texture is unavailable");
            var width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            var height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0)
                throw new IllegalStateException("Retained GPU texture is empty");
            var data = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(width, height), 4));
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(data);
            return HexFormat.of().formatHex(digest.digest());
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.bindTexture(previous);
        }
    }
}
