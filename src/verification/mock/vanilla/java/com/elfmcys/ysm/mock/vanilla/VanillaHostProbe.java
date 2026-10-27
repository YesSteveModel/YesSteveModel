package com.elfmcys.ysm.mock.vanilla;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** Runs only Forge and this probe; no YSM classes or channel are registered. */
@Mod("ysm_mock_vanilla")
public final class VanillaHostProbe {
    private static final Gson JSON = new Gson();
    private final Path root = Path.of(System.getenv("YSM_MOCK_ROLE_DIR"));
    private boolean connecting;
    private boolean ready;
    private boolean stopped;

    public VanillaHostProbe() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END)
            return;
        var minecraft = Minecraft.getInstance();
        if (stopped) {
            minecraft.stop();
            return;
        }
        if (!connecting && minecraft.getOverlay() == null && minecraft.getConnection() == null) {
            if (ModList.get().isLoaded("ysm"))
                throw new IllegalStateException("YSM must be absent");
            var address = System.getenv("YSM_MOCK_SERVER_ADDRESS");
            minecraft.options.pauseOnLostFocus = false;
            ConnectScreen.startConnecting(minecraft.screen, minecraft, ServerAddress.parseString(address),
                new ServerData("Entity verification", address, false), false);
            connecting = true;
        }
        if (!ready && minecraft.player != null) {
            Files.createDirectories(root);
            Files.writeString(root.resolve("ready.json"),
                JSON.toJson(Map.of("ysmLoaded", ModList.get().isLoaded("ysm"), "connected", true)));
            ready = true;
        }
        var actionFile = root.resolve("action.json");
        if (!ready || !Files.isRegularFile(actionFile))
            return;
        var action = JSON.fromJson(Files.readString(actionFile), JsonObject.class);
        var id = action.get("id").getAsString();
        var ack = root.resolve("acks/" + id + ".json");
        if (Files.exists(ack))
            return;
        if (!action.get("name").getAsString().equals("stop"))
            throw new IllegalArgumentException("Only stop is supported");
        Files.createDirectories(ack.getParent());
        Files.writeString(ack, JSON.toJson(
            Map.of("id", id, "success", true, "facts", Map.of("normalStopRequested", true, "ysmLoaded", false))));
        stopped = true;
    }
}
