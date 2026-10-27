package com.elfmcys.ysm.expression.inventory;

import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.EventArguments;
import dev.semaweave.BuiltinType;
import dev.semaweave.RequiredParameter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MolangEventCatalogTest {
    @Test
    void frozenRolesExposeExactTopLevelSignaturesAndBounds() {
        var catalog = MolangEventCatalog.production();

        assertEquals(5, catalog.routes().size());
        assertEquals(0, route(catalog, ProgramRole.EVENT_PLAYER_INIT).signature().parameters().size());
        assertEquals(BuiltinType.BOOL, parameter(route(catalog, ProgramRole.EVENT_PLAYER_UPDATE)));
        assertEquals(ExpressionHost.FLOAT_LIST, parameter(route(catalog, ProgramRole.EVENT_SYNC)));
        assertEquals(ExpressionHost.FLOAT_LIST, parameter(route(catalog, ProgramRole.EVENT_DEFER)));
        assertEquals(0, route(catalog, ProgramRole.EVENT_CODED_CONTROLLER).signature().parameters().size());
        assertEquals(MolangEventCatalog.SYNC_MAX_ARGUMENTS, route(catalog, ProgramRole.EVENT_SYNC).maximumArguments());
        assertEquals(MolangEventCatalog.DEFER_MAX_ARGUMENTS,
            route(catalog, ProgramRole.EVENT_DEFER).maximumArguments());
    }

    @Test
    void primitivePayloadsOwnTheirSnapshotAndUseCatalogBounds() {
        var syncValues = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        for (var index = 0; index < MolangEventCatalog.SYNC_MAX_ARGUMENTS; index++) {
            syncValues.add((float)index);
        }
        var sync = EventArguments.sync(syncValues);
        assertEquals(0, EventArguments.sync().size());
        assertEquals(MolangEventCatalog.SYNC_MAX_ARGUMENTS, sync.size());
        assertEquals(0, sync.getFloat(0));
        syncValues.set(0, 99.0f);
        assertEquals(0, sync.getFloat(0));
        assertThrows(IndexOutOfBoundsException.class, () -> sync.getFloat(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> sync.getFloat(sync.size()));
        assertThrows(UnsupportedOperationException.class, () -> sync.set(0, 99.0f));
        syncValues.add(17.0f);
        assertThrows(IllegalArgumentException.class, () -> EventArguments.sync(syncValues));

        var deferred = EventArguments.deferredFloats(1.0f, 2.0f);
        assertEquals(0, EventArguments.deferredFloats().size());
        assertEquals(2, deferred.size());
        assertThrows(IndexOutOfBoundsException.class, () -> deferred.getFloat(100));
        assertEquals(MolangEventCatalog.DEFER_MAX_ARGUMENTS,
            EventArguments.deferredFloats(new float[MolangEventCatalog.DEFER_MAX_ARGUMENTS]).size());
        assertThrows(IllegalArgumentException.class,
            () -> EventArguments.deferredFloats(new float[MolangEventCatalog.DEFER_MAX_ARGUMENTS + 1]));
        assertThrows(IllegalArgumentException.class, () -> EventArguments.deferredFloats(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> EventArguments.sync(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> EventArguments.sync(Float.POSITIVE_INFINITY));
    }

    @Test
    void classificationIsClosedAndCodedControllerPolicyIsExplicit() {
        var catalog = MolangEventCatalog.production();
        var coded =
            assertInstanceOf(MolangEventCatalog.Classification.Declared.class, catalog.classify("player_ctrl_main", 3));
        assertEquals(ProgramRole.EVENT_CODED_CONTROLLER, coded.route().role());
        assertEquals(MolangEventCatalog.HandlerSelection.FIRST, coded.route().handlerSelection());
        assertTrue(coded.route().fallback().contains("ABSENT=DELEGATE_PREDICATE"));
        assertTrue(coded.route().fallback().contains("DETERMINISTIC_FALLBACK=DELEGATE_PREDICATE"));
        assertTrue(coded.route().fallback().contains("UNSET=DELEGATE_PREDICATE"));
        assertTrue(coded.route().fallback().contains("2=CONTINUE"));
        assertTrue(coded.route().fallback().contains("OTHER_SUCCESS=DELEGATE_PREDICATE"));
        assertTrue(coded.route().effect().contains("FAILED_INVOCATION_DISCARDS"));

        var rejected = assertInstanceOf(MolangEventCatalog.Classification.Rejected.class,
            catalog.classify("not_a_declared_event", 0));
        assertEquals("unknown-event-suffix:v2:not_a_declared_event", rejected.diagnostic());
    }

    @Test
    void everyRoutePolicyDimensionChangesCatalogIdentity() {
        var catalog = MolangEventCatalog.production();
        var base = catalog.routes().get(0);
        var variants = List.of(
            new MolangEventCatalog.Route(base.routeId() + "!", base.role(), base.payload(), base.result(),
                base.maximumArguments(), base.handlerSelection(), base.ordering(), base.fallback(), base.effect(),
                base.diagnostic(), base.publicReachability()),
            copy(base, base.payload() + "!", base.result(), base.maximumArguments(), base.ordering(), base.fallback(),
                base.effect(), base.diagnostic()),
            copy(base, base.payload(), base.result() + "!", base.maximumArguments(), base.ordering(), base.fallback(),
                base.effect(), base.diagnostic()),
            copy(base, base.payload(), base.result(), base.maximumArguments() + 1, base.ordering(), base.fallback(),
                base.effect(), base.diagnostic()),
            new MolangEventCatalog.Route(base.routeId(), base.role(), base.payload(), base.result(),
                base.maximumArguments(),
                base.handlerSelection() == MolangEventCatalog.HandlerSelection.ALL
                    ? MolangEventCatalog.HandlerSelection.FIRST : MolangEventCatalog.HandlerSelection.ALL,
                base.ordering(), base.fallback(), base.effect(), base.diagnostic(), base.publicReachability()),
            copy(base, base.payload(), base.result(), base.maximumArguments(), base.ordering() + "!", base.fallback(),
                base.effect(), base.diagnostic()),
            copy(base, base.payload(), base.result(), base.maximumArguments(), base.ordering(), base.fallback() + "!",
                base.effect(), base.diagnostic()),
            copy(base, base.payload(), base.result(), base.maximumArguments(), base.ordering(), base.fallback(),
                base.effect() + "!", base.diagnostic()),
            copy(base, base.payload(), base.result(), base.maximumArguments(), base.ordering(), base.fallback(),
                base.effect(), base.diagnostic() + "!"));
        for (var variant : variants) {
            var routes = new ArrayList<>(catalog.routes());
            routes.set(0, variant);
            assertNotEquals(catalog.fingerprint(), new MolangEventCatalog(routes).fingerprint());
        }

        var swappedRoles = new ArrayList<>(catalog.routes());
        var first = swappedRoles.get(0);
        var second = swappedRoles.get(1);
        swappedRoles.set(0, withRole(first, second.role()));
        swappedRoles.set(1, withRole(second, first.role()));
        assertNotEquals(catalog.fingerprint(), new MolangEventCatalog(swappedRoles).fingerprint());
    }

    private static MolangEventCatalog.Route route(MolangEventCatalog catalog, ProgramRole role) {
        return catalog.routes().stream().filter(value -> value.role() == role).findFirst().orElseThrow();
    }

    private static dev.semaweave.ValueType parameter(MolangEventCatalog.Route route) {
        return ((RequiredParameter)route.signature().parameters().get(0)).type();
    }

    private static MolangEventCatalog.Route copy(MolangEventCatalog.Route route, String payload, String result,
        int maximumArguments, String ordering, String fallback, String effect, String diagnostic) {
        return new MolangEventCatalog.Route(route.routeId(), route.role(), payload, result, maximumArguments,
            route.handlerSelection(), ordering, fallback, effect, diagnostic, route.publicReachability());
    }

    private static MolangEventCatalog.Route withRole(MolangEventCatalog.Route route, ProgramRole role) {
        return new MolangEventCatalog.Route(route.routeId(), role, route.payload(), role.shape().name(),
            route.maximumArguments(), route.handlerSelection(), route.ordering(), route.fallback(), route.effect(),
            route.diagnostic(), route.publicReachability());
    }
}
