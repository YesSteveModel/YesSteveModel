package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.util.UnsafeUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerModelNavigationTest {
    @Test
    void currentAuthorizationDoesNotDependOnPageReconstruction() throws Exception {
        var type = com.elfmcys.ysm.client.gui.button.CatalogModelButton.class;
        var card = (com.elfmcys.ysm.client.gui.button.CatalogModelButton)UnsafeUtil.getUnsafe().allocateInstance(type);
        var denied = new java.util.concurrent.atomic.AtomicBoolean(true);
        field(type, "authorizationRequired").set(card, (java.util.function.BooleanSupplier)denied::get);
        field(net.minecraft.client.gui.components.AbstractWidget.class, "visible").setBoolean(card, true);
        field(net.minecraft.client.gui.components.AbstractWidget.class, "active").setBoolean(card, true);
        field(net.minecraft.client.gui.components.AbstractWidget.class, "width").setInt(card, 52);
        field(net.minecraft.client.gui.components.AbstractWidget.class, "height").setInt(card, 90);
        var clicked = type.getDeclaredMethod("clicked", double.class, double.class);
        clicked.setAccessible(true);
        assertEquals(false, clicked.invoke(card, 1d, 1d));
        denied.set(false);
        assertEquals(true, clicked.invoke(card, 1d, 1d));
        denied.set(true);
        assertEquals(false, clicked.invoke(card, 1d, 1d));
    }

    @Test
    void bothEntriesUseTheCurrentTargetWithoutACardInstallation() throws Exception {
        for (var purpose : new ModelResourcePurpose[] {ModelResourcePurpose.FULL}) {
            var screen = screen();
            screen.readyPurpose = purpose;
            var header = header(screen);
            header.onPress(null);
            assertEquals(screen.authority, screen.queried);
            assertEquals(1, screen.opened);
            assertEquals(1, screen.received);
            assertEquals(0, screen.lease.closes);
            screen.page.releaseResources();
            assertEquals(1, screen.lease.closes);
            var cardTarget = new ClientDemandCoordinator.Target(hash(2), "card-texture");
            var card = card(screen, cardTarget);
            assertTrue(card.mouseClicked(1, 1, 1));
            assertEquals(cardTarget, screen.queried);
            assertEquals(2, screen.opened);
            assertEquals(2, screen.received);
            screen.page.releaseResources();
        }
    }

    @Test
    void missesAndOrdinaryGuiRejectWithoutConstructingOrRestoringNavigation() throws Exception {
        for (var purpose : new ModelResourcePurpose[] {null, ModelResourcePurpose.GUI}) {
            var screen = screen();
            screen.readyPurpose = purpose;
            var action = header(screen);
            action.onPress(null);
            assertEquals(1, screen.queries);
            assertEquals(0, screen.constructed);
            assertEquals(0, screen.opened);
            screen.readyPurpose = ModelResourcePurpose.FULL;
            assertEquals(0, screen.opened);
            action.onPress(null);
            assertEquals(1, screen.opened);
            screen.page.releaseResources();
        }
    }

    @Test
    void retainedCallbacksRecheckWaitingWorldAndAuthorizationBeforeQueryingReady() throws Exception {
        var screen = screen();
        var action = header(screen);
        for (int condition = 0; condition < 3; condition++) {
            screen.waiting = condition == 0;
            screen.world = condition != 1;
            screen.authorized = condition != 2;
            action.onPress(null);
            screen.openTexturePage(new ClientDemandCoordinator.Target(hash(2), "main"));
            assertEquals(0, screen.queries);
            assertEquals(0, screen.opened);
        }
        screen.waiting = false;
        screen.world = screen.authorized = true;
        action.onPress(null);
        assertEquals(1, screen.opened);
        screen.page.releaseResources();
    }

    @Test
    void constructionReceivingNavigationFailureAndCancellationReleaseTheirExactLeases() throws Exception {
        for (int failure = 1; failure <= 5; failure++) {
            var screen = screen();
            screen.failure = failure;
            header(screen).onPress(null);
            assertEquals(1, screen.lease.closes, "failure " + failure);
            assertEquals(failure >= 3 ? 1 : 0, screen.received);
            assertEquals(failure == 3 || failure == 4 ? 1 : 0, screen.opened);
        }
    }

    private static ProbeScreen screen() throws Exception {
        var screen = (ProbeScreen)UnsafeUtil.getUnsafe().allocateInstance(ProbeScreen.class);
        screen.authority = new ClientDemandCoordinator.Target(hash(1), "selected-texture");
        screen.readyPurpose = ModelResourcePurpose.FULL;
        screen.world = screen.authorized = true;
        return screen;
    }

    private static net.minecraft.client.gui.components.Button.OnPress header(ProbeScreen screen) throws Exception {
        var method = PlayerModelScreen.class.getDeclaredMethod("textureNavigationAction");
        method.setAccessible(true);
        return (net.minecraft.client.gui.components.Button.OnPress)method.invoke(screen);
    }

    private static com.elfmcys.ysm.client.gui.button.CatalogModelButton card(ProbeScreen screen,
        ClientDemandCoordinator.Target target) throws Exception {
        var type = com.elfmcys.ysm.client.gui.button.CatalogModelButton.class;
        var card = (com.elfmcys.ysm.client.gui.button.CatalogModelButton)UnsafeUtil.getUnsafe().allocateInstance(type);
        var stateType = Class.forName("com.elfmcys.ysm.client.gui.button.CatalogModelCardState");
        var state = UnsafeUtil.getUnsafe().allocateInstance(stateType);
        field(stateType, "target").set(state, target);
        field(type, "state").set(card, state);
        field(type, "navigation").set(card, (java.util.function.BooleanSupplier)screen::canOpenTexturePage);
        field(type, "authorizationRequired").set(card, (java.util.function.BooleanSupplier)() -> !screen.authorized);
        field(type, "openRenderTarget").set(card,
            (com.elfmcys.ysm.client.gui.button.CatalogModelButton.RenderTargetHandler)screen::openTexturePage);
        field(net.minecraft.client.gui.components.AbstractWidget.class, "visible").setBoolean(card, true);
        field(net.minecraft.client.gui.components.AbstractWidget.class, "active").setBoolean(card, true);
        field(net.minecraft.client.gui.components.AbstractWidget.class, "width").setInt(card, 52);
        field(net.minecraft.client.gui.components.AbstractWidget.class, "height").setInt(card, 90);
        return card;
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class ProbeScreen extends PlayerModelScreen {
        ClientDemandCoordinator.Target authority;
        ClientDemandCoordinator.Target queried;
        ModelResourcePurpose readyPurpose;
        boolean waiting;
        boolean world;
        boolean authorized;
        int failure;
        int queries;
        int constructed;
        int received;
        int opened;
        Lease lease;
        ProbeTexture page;

        @Override
        protected boolean canOpenTexturePage() {
            return !waiting && world && authorized;
        }

        @Override
        protected ClientDemandCoordinator.Target currentTextureTarget() {
            return authority;
        }

        @Override
        protected boolean permitsTextureRequest(ResourceRequest request) {
            return authorized;
        }

        @Override
        protected ResourceRequest textureRequest(ClientDemandCoordinator.Target target) {
            queried = target;
            return new ResourceRequest(target.modelId(), "player", target.texture(), new BakeProfile("navigation"),
                ExpressionBinding.standalone(), ModelResourcePurpose.FULL);
        }

        @Override
        protected ResourceLease findTextureReady(ResourceRequest request) {
            queries++;
            if (readyPurpose == null)
                return null;
            try {
                lease = new Lease(readyPurpose, model(request.modelId()));
                return lease;
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }

        @Override
        protected PlayerTextureScreen getTextureScreen(ModelRenderTarget model) {
            constructed++;
            if (failure == 1)
                throw new IllegalStateException("constructor failed");
            try {
                page = (ProbeTexture)UnsafeUtil.getUnsafe().allocateInstance(ProbeTexture.class);
                page.source = this;
                return page;
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }

        @Override
        protected boolean showTextureScreen(PlayerTextureScreen page) {
            assertNotNull(this.page.anchor, "receiver owns the lease before the source is removed");
            opened++;
            if (failure == 3)
                throw new IllegalStateException("navigation failed");
            return failure != 4;
        }
    }

    private static final class ProbeTexture extends PlayerTextureScreen {
        ProbeScreen source;
        ResourceLease anchor;

        private ProbeTexture() {
            super(null, null);
        }

        @Override
        boolean receiveReady(ResourceRequest request, ResourceLease lease) {
            if (source.failure == 2)
                return false;
            source.received++;
            anchor = lease;
            if (source.failure == 5)
                source.authorized = false;
            return true;
        }

        @Override
        void releaseResources() {
            if (anchor != null) {
                anchor.close();
                anchor = null;
            }
        }
    }

    private static final class Lease implements ResourceLease {
        final ModelResourcePurpose purpose;
        final ModelRenderTarget target;
        int closes;

        Lease(ModelResourcePurpose purpose, ModelRenderTarget target) {
            this.purpose = purpose;
            this.target = target;
        }

        public ModelResourcePurpose purpose() {
            return purpose;
        }

        public AcquireResult poll() {
            return new AcquireResult.Ready(target);
        }

        public boolean isCurrent(ResourceRequest request) {
            return closes == 0 && request.purpose() == purpose;
        }

        public void cancelPending() {
            throw new AssertionError();
        }

        public void close() {
            closes++;
        }
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[32];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }

    private static ModelRenderTarget model(Hash256 hash) throws Exception {
        var model = (ModelRenderTarget)UnsafeUtil.getUnsafe().allocateInstance(ModelRenderTarget.class);
        field(ModelRenderTarget.class, "modelHash").set(model, hash);
        return model;
    }
}
