package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Controls existing host owners at the receiving seam; all installation results remain production results. */
public final class GuiEntryObservation {
    public static final AtomicInteger preparations = new AtomicInteger();
    public static final AtomicInteger networkFrames = new AtomicInteger();
    public static int acceptedRenders;
    public static int rejectedRenders;
    public static long vertices;
    private static String mode;
    private static Object catalogs;
    private static ClientCatalogSnapshot original;
    private static ClientDemandCoordinator.InstallationResult observed;
    private static boolean revoked;

    static void arm(String value) {
        mode = value;
        observed = null;
        revoked = false;
    }

    public static void preparationStarted() {
        preparations.incrementAndGet();
    }

    public static void rendered(boolean accepted, int count) {
        if (accepted) {
            acceptedRenders++;
            vertices += count;
        } else {
            rejectedRenders++;
        }
    }

    public static void networkSent(Object direction) {
        if (direction.toString().equals("PLAY_TO_SERVER"))
            networkFrames.incrementAndGet();
    }

    public static void afterInstallation(Object owner, Object value) {
        if (owner instanceof CustomGuiPlayerEntity && mode != null) {
            observed = (ClientDemandCoordinator.InstallationResult)value;
        }
    }

    public static void afterReceive(boolean received) {
        if (!received || !"permission".equals(mode))
            return;
        try {
            catalogs = field(ClientModelService.instance(), "catalogManager");
            original = (ClientCatalogSnapshot)field(catalogs, "catalog");
            var value = new ClientCatalogSnapshot(original.catalog(), original.failed(),
                new RemotePublicationSnapshot(Map.of(), Set.of(), List.of(), Map.of()));
            set(catalogs, "catalog", value);
            revoked = true;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static Map<String, ?> finish() throws Exception {
        var facts = Map.of("installation", String.valueOf(observed), "permissionRevokedAfterReceive", revoked);
        mode = null;
        if (original != null)
            set(catalogs, "catalog", original);
        original = null;
        catalogs = null;
        return facts;
    }

    static Object field(Object owner, String name) throws ReflectiveOperationException {
        var type = owner.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    static void set(Object owner, String name, Object value) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
