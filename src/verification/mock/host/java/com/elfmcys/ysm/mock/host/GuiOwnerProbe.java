package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.util.RenderUtil;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Uses real GUI owners, worker tasks and renderer; only worker admission is delayed. */
final class GuiOwnerProbe {
    private final Minecraft minecraft;
    private final HostAction action;
    private final CustomGuiPlayerEntity owner = new CustomGuiPlayerEntity();
    private final Map<String, Object> result = new LinkedHashMap<>();
    private final Map<String, Integer> rendered = new LinkedHashMap<>();
    private final ArrayList<Runnable> held = new ArrayList<>();
    private Field workerField;
    private Object manager;
    private ScheduledExecutorService workers;
    private boolean open;
    private int step;
    private int ticks;
    private int placeholders;
    private ResourceLease supplied;
    private int suppliedCloses;

    GuiOwnerProbe(Minecraft minecraft, HostAction action) {
        this.minecraft = minecraft;
        this.action = action;
    }

    Map<String, ?> poll() throws Exception {
        if (++ticks > 600)
            throw new IllegalStateException("GUI observation did not complete");
        var service = ClientModelService.instance();
        if (step == 0) {
            var initial = service.resolvePath("entity/parts-a");
            if (initial.isEmpty() || service.resolvePath("entity/gui-pending").isEmpty())
                return null;
            owner.updateModelAndTexture(initial.orElseThrow(), "gsl");
            minecraft.setScreen(new Screen(Component.literal("Entity GUI ownership verification")) {
                @Override
                public boolean isPauseScreen() {
                    return false;
                }

                @Override
                public void render(GuiGraphics graphics, int x, int y, float partialTick) {
                    owner.checkModelUpdate();
                    if (owner.displayedModel() == null) {
                        placeholders++;
                        return;
                    }
                    RenderUtil.renderModelInGui(width / 2f, height * 0.8f, 60, partialTick, owner,
                        RegisterEntityRenderersEvent.getPlayerRenderer(), true, true);
                    rendered.merge(owner.getInstalledModelId(), 1, Integer::sum);
                }
            });
            step = 1;
            return null;
        }
        if (step == 1) {
            if (rendered.getOrDefault("entity/parts-a", 0) == 0)
                return null;
            result.put("matchingA", facts());
            manager = field(ClientModelService.class, "renderTargetManager").get(service);
            workerField = field(manager.getClass(), "workers");
            workers = (ScheduledExecutorService)workerField.get(manager);
            var gated = Proxy.newProxyInstance(workers.getClass().getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("execute"))
                        synchronized (held) {
                            if (!open) {
                                held.add((Runnable)arguments[0]);
                                return null;
                            }
                        }
                    try {
                        return method.invoke(workers, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            workerField.set(manager, gated);
            owner.updateModelAndTexture(service.resolvePath("entity/gui-pending").orElseThrow(), "gsl");
            owner.checkModelUpdate();
            rendered.clear();
            placeholders = 0;
            ticks = 0;
            step = 2;
            return null;
        }
        if (step == 2) {
            if (ticks < 3)
                return null;
            result.put("pendingB", facts());
            if (held.isEmpty() || !owner.hasUnfinishedWork() || owner.displayedModel() != null || placeholders == 0
                || !rendered.isEmpty())
                throw new IllegalStateException("GUI did not keep a placeholder for actual Pending B");
            owner.reset();
            result.put("closedPending", facts());
            workerField.set(manager, workers);
            synchronized (held) {
                open = true;
                held.forEach(workers::execute);
                held.clear();
            }
            var request = service.resourceRequest(service.resolvePath("entity/parts-a").orElseThrow(), "player", "gsl", com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.GUI, CustomGuiPlayerEntity.FakePlayer.class);
            supplied = service.getOrStart(request);
            ticks = 0;
            step = 3;
            return null;
        }
        if (step == 3) {
            if (!(supplied.poll() instanceof AcquireResult.Ready))
                return null;
            var request = service.resourceRequest(service.resolvePath("entity/parts-a").orElseThrow(), "player", "gsl", com.elfmcys.ysm.model.resource.client.ModelResourcePurpose.GUI, CustomGuiPlayerEntity.FakePlayer.class);
            var method = CustomGuiPlayerEntity.class.getDeclaredMethod("installPreviewResource", ResourceRequest.class,
                ResourceLease.class);
            method.setAccessible(true);
            method.invoke(owner, request, new ResourceLease() {
                @Override
                public com.elfmcys.ysm.model.resource.client.ModelResourcePurpose purpose() {
                    return supplied.purpose();
                }

                @Override
                public AcquireResult poll() {
                    return supplied.poll();
                }

                @Override
                public boolean isCurrent(ResourceRequest candidate) {
                    return supplied.isCurrent(candidate);
                }

                @Override
                public void cancelPending() {
                    supplied.cancelPending();
                }

                @Override
                public void close() {
                    suppliedCloses++;
                    supplied.close();
                }
            });
            rendered.clear();
            placeholders = 0;
            ticks = 0;
            step = 4;
            return null;
        }
        if (ticks < 3)
            return null;
        result.put("suppliedReady", facts());
        owner.reset();
        owner.reset();
        result.put("suppliedClosed", facts());
        result.put("suppliedLeaseCloses", suppliedCloses);
        minecraft.setScreen(null);
        return result;
    }

    private Map<String, Object> facts() {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("installed", String.valueOf(owner.getInstalledModelId()));
        facts.put("relation", owner.getInstallationRelation().name());
        facts.put("displayed", owner.displayedModel() != null);
        facts.put("unfinished", owner.hasUnfinishedWork());
        facts.put("expressionInstalled", owner.getExpressionRuntime().module().isPresent());
        facts.put("rendered", new LinkedHashMap<>(rendered));
        facts.put("placeholders", placeholders);
        return facts;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
