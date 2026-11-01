package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.model.resource.client.asset.PageImages;
import com.elfmcys.ysm.model.resource.client.asset.ModelAssetSelector;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.google.gson.GsonBuilder;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/** Functional GL observations are outside the performance scripts. */
public final class ImageResidencyHostProbe {
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static PageImages first, second;
    private static List<PageImages.Image> images;
    private static List<Map<String, Object>> cold;
    private static Map<String, Long> before;
    private static com.elfmcys.ysm.model.domain.Hash256 model;
    private static long expires;
    private static int phase;
    private static CompletableFuture<Void> reload;

    public static boolean tick() throws Exception {
        try {
            var service = ClientModelService.instance();
            if (phase == 0) {
                com.elfmcys.ysm.config.ClientConfig.MODEL_SCREEN_EAGER_LOADING.set(false);
                model = service.catalog().models().values().stream().filter(entry -> entry.displayPath().endsWith("22_elf"))
                    .findFirst().orElseThrow().modelHash();
                first = new PageImages(service.createAssetBatch()); images = four(first);
                before = PageFrameHost.counts(); phase = 1;
            } else if (phase == 1) {
                first.advance(ModelResourceDemand.Mode.OFFLINE);
                if (!ready()) return false;
                cold = textures();
                require(cold.stream().allMatch(value -> Boolean.TRUE.equals(value.get("live"))), "Cold GL texture missing");
                RESULTS.add(Map.of("case", "cold-four-sources", "textures", cold, "delta", delta(before)));
                second = new PageImages(service.createAssetBatch()); images = four(second);
                before = PageFrameHost.counts(); second.advance(ModelResourceDemand.Mode.READY);
                require(ready(), "Second consumer cannot acquire residents");
                require(cold.equals(textures()), "Shared content or GL pixels changed");
                first.close(); first = null;
                require(ready() && cold.equals(textures()), "Closing first consumer changed the second");
                RESULTS.add(Map.of("case", "shared-and-first-close", "textures", textures(), "delta", delta(before)));
                second.close(); second = null;
                first = new PageImages(service.createAssetBatch()); images = four(first);
                before = PageFrameHost.counts(); first.advance(ModelResourceDemand.Mode.READY);
                require(ready() && cold.equals(textures()), "Retention hit changed content or GL registration");
                var work = delta(before);
                for (String key : List.of("chunk-read", "decode", "construct", "host-register", "upload"))
                    require(work.getOrDefault(key, 0L) == 0, "Retention hit restarted " + key);
                RESULTS.add(Map.of("case", "closed-page-retention-hit", "delta", work, "textures", textures()));
                first.close(); first = null; expires = System.nanoTime() + 12_000_000_000L; phase = 2;
            } else if (phase == 2) {
                if (System.nanoTime() < expires) return false;
                var deleted = cold.stream().map(value -> Map.of("glId", value.get("glId"),
                    "live", GL11.glIsTexture(((Number)value.get("glId")).intValue()))).toList();
                require(deleted.stream().noneMatch(value -> Boolean.TRUE.equals(value.get("live"))), "Expired GL texture remains live");
                RESULTS.add(Map.of("case", "physical-gl-expiry", "textures", deleted));
                first = new PageImages(service.createAssetBatch()); images = four(first); before = PageFrameHost.counts(); phase = 3;
            } else if (phase == 3) {
                first.advance(ModelResourceDemand.Mode.OFFLINE);
                if (!ready()) return false;
                RESULTS.add(Map.of("case", "expired-rebuild", "textures", textures(), "delta", delta(before)));
                reload = Minecraft.getInstance().reloadResourcePacks(); phase = 4;
            } else if (phase == 4) {
                if (!reload.isDone()) return false;
                reload.join();
                first.advance(ModelResourceDemand.Mode.READY);
                RESULTS.add(Map.of("case", "real-host-reload-ready-observation", "available", ready()));
                before = PageFrameHost.counts(); phase = 5;
            } else if (phase == 5) {
                first.advance(ModelResourceDemand.Mode.OFFLINE);
                if (!ready()) return false;
                RESULTS.add(Map.of("case", "post-reload-usable", "textures", textures(), "delta", delta(before)));
                first.close(); first = null; save(true, ""); phase = 6; return true;
            }
            return phase == 6;
        } catch (Throwable failure) { save(false, failure.toString()); throw failure; }
    }

    private static List<PageImages.Image> four(PageImages page) {
        return List.of(page.preview(model),
            page.presentation(model, ModelAssetSelector.PresentationAsset.GUI_FOREGROUND, 0),
            page.presentation(model, ModelAssetSelector.PresentationAsset.GUI_BACKGROUND, 0),
            page.presentation(model, ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0));
    }
    private static boolean ready() {
        for (var image : images) {
            if (image.failure() != null) throw new IllegalStateException("Image failed", image.failure());
            if (image.texture() == null) return false;
        }
        return true;
    }
    private static List<Map<String, Object>> textures() throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            for (var image : images) {
                TextureHolder holder = image.texture();
                var texture = Minecraft.getInstance().getTextureManager().getTexture(holder.id());
                int id = texture.getId(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
                String hash;
                try {
                    GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                    var digest = MessageDigest.getInstance("SHA-256"); digest.update(pixels); hash = HexFormat.of().formatHex(digest.digest());
                } finally { MemoryUtil.memFree(pixels); }
                result.add(Map.of("id", holder.id().toString(), "glId", id, "width", width, "height", height,
                    "pixelSha256", hash, "identity", String.valueOf(image.identity()), "live", GL11.glIsTexture(id)));
            }
        } finally { GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous); }
        return result;
    }
    private static Map<String, Long> delta(Map<String, Long> start) {
        var result = new TreeMap<String, Long>();
        PageFrameHost.counts().forEach((key, value) -> result.put(key, value - start.getOrDefault(key, 0L))); return result;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void save(boolean passed, String failure) throws Exception {
        var output = Path.of(System.getenv("YSM_PAGE_OUTPUT")); Files.createDirectories(output);
        Files.writeString(output.resolve("image-residency.json"), new GsonBuilder().setPrettyPrinting().create().toJson(
            Map.of("passed", passed, "failure", failure, "cases", RESULTS,
                "mods", net.minecraftforge.fml.ModList.get().getMods().stream().map(value -> value.getModId() + "@" + value.getVersion()).toList(),
                "limits", List.of("LOCAL builtin winefox22; no remote transfer or same-path replacement", "GL pixels compared between consumers, not against an independent source decoder", "Fresh repository batches; distinct ImageSource oracle remains separately required"))));
    }
}
