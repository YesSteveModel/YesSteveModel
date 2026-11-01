package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.client.gui.GuiFrameTasks;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.model.catalog.client.entry.CatalogModelMetadata;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.DoubleSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Bridges package access without constructing a second card or execution owner. */
public final class CatalogFrameTestAccess {
    private CatalogFrameTestAccess() {}

    public static Card card(CatalogPageFrame frame, ClientDemandCoordinator.PageScope page,
        ClientCatalogEntry entry, CustomGuiPlayerEntity entity) {
        return new Card(new CatalogModelCardState(frame, page, entry, CatalogModelMetadata.from(entry), entity));
    }

    public static List<GuiFrameTasks.Job> plan(CatalogPageFrame frame, List<Card> cards, int poolSize) {
        return frame.plan(cards.stream().map(card -> card.state).toList(), List.of(), poolSize);
    }

    public static final class Card implements AutoCloseable {
        private final CatalogModelCardState state;
        private Card(CatalogModelCardState state) { this.state = state; }
        public void input(float partial, long now) { state.captureInput(partial, true, false, now, false); }
        public ClientDemandCoordinator.Target target() { return state.target(); }
        public ClientDemandCoordinator.EntityScope scope() { return state.scope(); }
        public GeoRenderData prepared() { return state.prepared(); }
        public void retainAnimationReference(ModelRenderTarget target) throws Exception {
            var animations = field(CatalogModelCardState.class, "previewAnimations").get(state);
            field(CatalogModelPreviewAnimationState.class, "hoverFadeoutMillis").set(animations,
                (DoubleSupplier)() -> target.playerResources().animations().size());
            assertSame(target, field(CatalogModelCardState.class, "configuredTarget").get(state));
        }
        public void assertAnimationReferencesCleared() throws Exception {
            assertNull(field(CatalogModelCardState.class, "configuredTarget").get(state));
            var animations = field(CatalogModelCardState.class, "previewAnimations").get(state);
            assertEquals(0d, ((DoubleSupplier)field(CatalogModelPreviewAnimationState.class,
                "hoverFadeoutMillis").get(animations)).getAsDouble());
        }
        @Override public void close() { state.close(); }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
