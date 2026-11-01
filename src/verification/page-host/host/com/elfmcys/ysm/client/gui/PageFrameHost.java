package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import jdk.jfr.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;

/** A bounded host driver; production scheduling and resource ownership remain authoritative. */
@Mod.EventBusSubscriber(modid = "ysm", value = Dist.CLIENT)
public final class PageFrameHost {
    private static final Path OUTPUT = Path.of(System.getenv("YSM_PAGE_OUTPUT"));
    private static final long DEADLINE_MILLIS = Long.parseLong(
        System.getenv().getOrDefault("YSM_PAGE_DEADLINE_MILLIS", String.valueOf(Long.MAX_VALUE)));
    private static boolean finished;
    private static final boolean DETAIL = Boolean.parseBoolean(System.getenv("YSM_PAGE_DETAIL"));
    private static final List<Map<String, Object>> SCRIPT = script();
    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final List<Map<String, Object>> EVENTS = Collections.synchronizedList(new ArrayList<>());
    private static final List<long[]> FRAMES = new ArrayList<>();
    private static final List<Map<String, Object>> VISITS = new ArrayList<>();
    private static final List<Map<String, Object>> INPUTS = new ArrayList<>();
    private static final ThreadLocal<Map<String, ArrayDeque<Span>>> SPANS = ThreadLocal.withInitial(HashMap::new);
    private static final java.lang.management.ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static boolean world;
    private static long readyAt, next, last, start, cpu;
    private static volatile int visit = -1;
    private static PlayerModelScreen screen;
    private static CatalogBrowserState browser;
    private static Recording recording;
    private static boolean functionalDone;
    private static final Set<Integer> screenshots = new HashSet<>();
    private static final ThreadLocal<Integer> CARD_DEPTH = ThreadLocal.withInitial(() -> 0);

    @Name("ysm.PageSpan") @Label("Page host operation") @StackTrace(false)
    public static final class Span extends Event { public String operation; public int visit; public boolean cardTask; }

    private static List<Map<String, Object>> script() {
        try {
            var file = System.getenv("YSM_PAGE_SCRIPT");
            if (file != null) return new Gson().fromJson(Files.readString(Path.of(file)),
                new TypeToken<List<Map<String, Object>>>() {}.getType());
            var result = new ArrayList<Map<String, Object>>();
            boolean eager = Boolean.parseBoolean(System.getenv("YSM_PAGE_EAGER"));
            for (String scene : List.of("cold", "warmup", "hot", "rapid", "stable", "expired", "steady")) {
                if (scene.equals("expired")) result.add(Map.of("scene", "expired-wait", "page", 0,
                    "eager", eager, "dwell_ms", 12000, "close", true));
                for (int page = 0; page < 3; page++) result.add(Map.of("scene", scene, "page", page,
                    "eager", eager, "dwell_ms", scene.equals("rapid") ? 300 : 2000));
            }
            return result;
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    public static void observe(String kind, Object object) {
        if (kind.startsWith("animation") && object != null
            && !object.getClass().getName().contains("CustomGuiPlayerEntity")) return;
        if (kind.equals("task-run")) CARD_DEPTH.set(CARD_DEPTH.get() + 1);
        boolean cardTask = CARD_DEPTH.get() > 0;
        COUNTS.computeIfAbsent(kind, ignored -> new LongAdder()).increment();
        if (visit < 0 || visit >= SCRIPT.size()) return;
        if (kind.startsWith("task-") || kind.startsWith("animation") || (kind.startsWith("provider:") || kind.startsWith("provider-hook:"))) {
            boolean returned = kind.endsWith("-return");
            String operation = returned ? kind.substring(0, kind.length() - 7) : kind;
            var stack = SPANS.get().computeIfAbsent(operation, ignored -> new ArrayDeque<>());
            if (returned) {
                if (!stack.isEmpty()) { var span = stack.pop(); span.end(); span.commit(); }
            } else { var span = new Span(); span.operation = operation; span.visit = visit; span.cardTask = cardTask; span.begin(); stack.push(span); }
        }
        if (kind.equals("draw3d-return") && object != null) {
            try {
                var entity = (AnimatableEntity<?>)field(object, "entity");
                if (entity.hasMatchingInstallation()) {
                    var current = VISITS.get(visit);
                    current.putIfAbsent("firstMatchingDrawNanos", System.nanoTime());
                    @SuppressWarnings("unchecked")
                    var models = (Set<String>)current.computeIfAbsent("matchingModels", ignored -> new TreeSet<String>());
                    models.add(object.getClass().getMethod("modelHash").invoke(object).toString());
                }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        if (DETAIL) {
            EVENTS.add(Map.of("kind", kind, "nanos", System.nanoTime(), "visit", visit,
                "thread", Thread.currentThread().getName(), "cardTask", cardTask, "object", object == null ? "null"
                    : object.getClass().getName() + "@" + Integer.toUnsignedString(System.identityHashCode(object))));
        }
        if (kind.equals("task-run-return")) CARD_DEPTH.set(CARD_DEPTH.get() - 1);
    }

    public static void observeHook(String edge, Object entry, Object phase) {
        try {
            var declaration = entry.getClass().getDeclaredMethod("declaration"); declaration.setAccessible(true);
            var component = entry.getClass().getDeclaredMethod("component"); component.setAccessible(true);
            Class<?> owner = (Class<?>)declaration.invoke(entry);
            if (owner.getName().contains(".compat.") || owner.getName().contains("Curios"))
                observe("provider-hook:" + owner.getSimpleName() + ":" + phase + edge, component.invoke(entry));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    public static void observeId(String kind, int id) {
        observe(kind, Integer.valueOf(id));
        if (DETAIL && kind.endsWith("-return") && visit >= 0)
            EVENTS.add(Map.of("kind", "gl-delete-state", "nanos", System.nanoTime(), "visit", visit,
                "thread", Thread.currentThread().getName(), "glId", id, "live", GL11.glIsTexture(id)));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        var minecraft = Minecraft.getInstance();
        try {
            if (System.currentTimeMillis() >= DEADLINE_MILLIS) {
                finish(new java.util.concurrent.TimeoutException("Host sampling deadline reached"));
                minecraft.stop(); return;
            }
            if (!world && minecraft.getOverlay() == null && minecraft.screen != null) {
                world = true;
                minecraft.createWorldOpenFlows().createFreshLevel("page-frame-host",
                    new LevelSettings("Page frame host", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                        false, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(42L, false, false), access -> access.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            }
            if (minecraft.level == null || minecraft.player == null) return;
            long now = System.nanoTime();
            if (readyAt == 0) { readyAt = now + 15_000_000_000L; return; }
            if (now < readyAt) return;
            if (!functionalDone && Boolean.parseBoolean(System.getenv("YSM_PAGE_FUNCTIONAL"))) {
                functionalDone = Boolean.TRUE.equals(Class.forName("com.elfmcys.ysm.client.gui.ImageResidencyHostProbe")
                    .getMethod("tick").invoke(null));
                return;
            }
            if (visit < 0) {
                Files.createDirectories(OUTPUT);
                recording = new Recording();
                for (String name : List.of("jdk.FileRead", "jdk.FileWrite", "jdk.ThreadPark", "jdk.JavaMonitorEnter"))
                    recording.enable(name).withThreshold(Duration.ZERO).withStackTrace();
                recording.enable("jdk.GarbageCollection"); recording.enable("jdk.GCPhasePause");
                recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
                recording.enable("jdk.CPULoad").withPeriod(Duration.ofMillis(100));
                recording.enable("ysm.PageSpan"); recording.start();
                minecraft.options.framerateLimit().set(120);
                transition(0);
                Files.writeString(OUTPUT.resolve("input.json"), new GsonBuilder().setPrettyPrinting().create().toJson(
                    Map.of("java", System.getProperty("java.version"), "detail", DETAIL,
                        "vmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments(),
                        "processors", Runtime.getRuntime().availableProcessors(), "script", SCRIPT,
                        "mods", net.minecraftforge.fml.ModList.get().getMods().stream().map(value -> value.getModId() + "@" + value.getVersion()).toList(),
                        "models", browser.models().stream().map(value -> value.displayPath()).toList())));
            } else if (now >= next) {
                var current = VISITS.get(visit);
                current.put("endNanos", now); current.put("countsAfter", counts());
                current.put("registrationsAfter", registrations());
                current.put("cardStatesAfter", cards());
                if (DETAIL && !Boolean.TRUE.equals(current.get("close"))) snapshot("end-" + visit);
                if (visit + 1 == SCRIPT.size()) { finish(null); minecraft.stop(); return; }
                transition(visit + 1);
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            try { finish(failure); } catch (Exception secondary) { failure.addSuppressed(secondary); }
            minecraft.stop();
        }
    }

    private static void transition(int index) throws Exception {
        var stage = SCRIPT.get(index);
        boolean eager = Boolean.TRUE.equals(stage.get("eager"));
        ClientConfig.MODEL_SCREEN_EAGER_LOADING.set(eager);
        var initialCounts = counts();
        var initialRegistrations = registrations();
        long before = System.nanoTime();
        var instant = java.time.Instant.now();
        long epochNanos = instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
        if (Boolean.TRUE.equals(stage.get("close"))) Minecraft.getInstance().setScreen(null);
        else {
            if (screen == null || Minecraft.getInstance().screen != screen || Boolean.TRUE.equals(stage.get("reopen"))) {
                screen = new PlayerModelScreen(); Minecraft.getInstance().setScreen(screen);
                browser = (CatalogBrowserState)field(screen, "browser");
                ((EditBox)field(screen, "textField")).setValue("wine"); screen.init();
                if (browser.models().size() != 22 || browser.maxPage() != 2)
                    throw new IllegalStateException("Expected winefox 22 models / 3 pages");
            }
            navigate(((Number)stage.get("page")).intValue());
            if (Boolean.TRUE.equals(stage.get("hover"))) {
                var buttons = (List<?>)field(screen, "modelButtons");
                if (!buttons.isEmpty()) {
                    var button = (net.minecraft.client.gui.components.AbstractWidget)buttons.get(0);
                    Minecraft.getInstance().setLastInputType(net.minecraft.client.InputType.KEYBOARD_TAB);
                    screen.setFocused(button);
                    double scale = Minecraft.getInstance().getWindow().getGuiScale();
                    org.lwjgl.glfw.GLFW.glfwSetCursorPos(Minecraft.getInstance().getWindow().getWindow(),
                        (button.getX() + 20) * scale, (button.getY() + 20) * scale);
                }
            }
        }
        visit = index;
        var value = new LinkedHashMap<String, Object>(stage);
        value.put("visit", index); value.put("inputNanos", before); value.put("inputEpochNanos", epochNanos); value.put("countsBefore", initialCounts);
        value.put("registrationsBefore", initialRegistrations);
        VISITS.add(value);
        next = before + ((Number)stage.get("dwell_ms")).longValue() * 1_000_000L;
    }

    private static List<Map<String, Object>> cards() throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        if (screen == null || Minecraft.getInstance().screen != screen) return result;
        for (var button : (List<?>)field(screen, "modelButtons")) {
            var entity = (AnimatableEntity<?>)field(button, "entity");
            var state = field(button, "state");
            var preview = state.getClass().getDeclaredMethod("preview"); preview.setAccessible(true);
            var failure = state.getClass().getDeclaredMethod("loadError"); failure.setAccessible(true);
            result.add(Map.of("model", button.getClass().getMethod("modelHash").invoke(button).toString(),
                "matching", entity.hasMatchingInstallation(), "preview", preview.invoke(state) != null,
                "failure", String.valueOf(failure.invoke(state))));
        }
        return result;
    }

    private static void navigate(int target) {
        while (browser.page() != target) {
            int previous = browser.page(); long time = System.nanoTime();
            screen.mouseClicked(screen.x + (target > previous ? 320 : 210), screen.y + 220, 0);
            screen.mouseReleased(screen.x + (target > previous ? 320 : 210), screen.y + 220, 0);
            if (browser.page() == previous) throw new IllegalStateException("Page button rejected the input");
            INPUTS.add(Map.of("nanos", time, "from", previous, "to", browser.page(), "kind", "mouse-click"));
        }
    }

    private static List<Map<String, Object>> registrations() throws Exception {
        var owner = com.elfmcys.ysm.client.texture.CustomTextureManager.class.getDeclaredField("REGISTRY");
        owner.setAccessible(true);
        var values = new ArrayList<Map<String, Object>>();
        for (var entry : ((Map<?, ?>)field(owner.get(null), "registrations")).entrySet()) {
            var state = field(entry.getValue(), "state");
            values.add(Map.of("texture", Integer.toUnsignedString(System.identityHashCode(entry.getKey())),
                "id", field(state, "id").toString(), "registered", field(state, "registered"),
                "active", field(state, "active"), "remainingTicks", field(state, "remainingRemovalTicks")));
        }
        return values;
    }

    @SubscribeEvent
    public static void render(TickEvent.RenderTickEvent event) {
        if (finished) return;
        if (visit < 0 || visit >= SCRIPT.size()) {
            if (event.phase == TickEvent.Phase.START) last = System.nanoTime();
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            start = System.nanoTime(); cpu = CPU.getCurrentThreadCpuTime();
            FRAMES.add(new long[] {visit, start, last == 0 ? 0 : start - last, 0, 0}); last = start;
        } else if (!FRAMES.isEmpty()) {
            var frame = FRAMES.get(FRAMES.size() - 1);
            frame[3] = System.nanoTime() - start; frame[4] = CPU.getCurrentThreadCpuTime() - cpu;
            if (DETAIL && VISITS.get(visit).containsKey("firstMatchingDrawNanos") && screenshots.add(visit)) {
                snapshot("matching-" + visit);
            }
        }
    }

    private static void snapshot(String name) {
        try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            image.writeToFile(OUTPUT.resolve(name + ".png"));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    public static Map<String, Long> counts() {
        var result = new TreeMap<String, Long>(); COUNTS.forEach((key, value) -> result.put(key, value.sum())); return result;
    }

    private static void finish(Throwable failure) throws Exception {
        if (finished) return;
        finished = true;
        if (visit >= 0 && visit < VISITS.size()) {
            var current = VISITS.get(visit);
            current.putIfAbsent("endNanos", System.nanoTime());
            current.putIfAbsent("countsAfter", counts());
            try {
                current.putIfAbsent("registrationsAfter", registrations());
                current.putIfAbsent("cardStatesAfter", cards());
            } catch (Exception snapshotFailure) {
                current.put("snapshotFailure", snapshotFailure.toString());
            }
        }
        Files.createDirectories(OUTPUT);
        var gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(OUTPUT.resolve("visits.json"), gson.toJson(VISITS));
        Files.writeString(OUTPUT.resolve("inputs.json"), gson.toJson(INPUTS));
        Files.writeString(OUTPUT.resolve("events.json"), gson.toJson(EVENTS));
        Files.writeString(OUTPUT.resolve("result.json"), gson.toJson(Map.of("completed", failure == null,
            "failure", failure == null ? "" : failure.toString(), "counts", counts(),
            "frameCount", FRAMES.size(), "visits", VISITS.size())));
        var csv = new StringBuilder("visit,start_ns,gap_ns,render_ns,cpu_ns\n");
        for (var row : FRAMES) csv.append(row[0]).append(',').append(row[1]).append(',').append(row[2])
            .append(',').append(row[3]).append(',').append(row[4]).append('\n');
        Files.writeString(OUTPUT.resolve("frames.csv"), csv);
        if (recording != null) { recording.stop(); recording.dump(OUTPUT.resolve("operations.jfr")); recording.close(); }
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
}
