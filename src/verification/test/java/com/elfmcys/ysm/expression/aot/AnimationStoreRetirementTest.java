package com.elfmcys.ysm.expression.aot;

import static org.junit.jupiter.api.Assertions.*;

import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import dev.semaweave.CompiledModuleResource;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnimationStoreRetirementTest {
    @Test
    void finalReturnCompletesRetirementEvenWhenBackendCleanupFails() {
        var closes = new AtomicInteger();
        var failure = new IllegalStateException("injected cleanup failure");
        try (var template = TestExpressionModules.create()) {
            var store = failingStore(template, closes, failure);
            var use = store.prepare();
            store.close();
            assertFalse(store.isClosed());
            assertEquals(0, closes.get());
            assertSame(failure, assertThrows(IllegalStateException.class, use::close));
            assertTrue(store.isClosed());
            use.close();
            store.close();
            assertEquals(1, closes.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static AnimationStore failingStore(RenderTargetExpressionModule template, AtomicInteger closes,
        RuntimeException failure) {
        var resource = (CompiledModuleResource<MolangContext>)Proxy.newProxyInstance(
            AnimationStoreRetirementTest.class.getClassLoader(), new Class<?>[] {CompiledModuleResource.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "roamingFieldNames" -> List.of();
                case "generation" -> template.generation();
                case "close" -> {
                    closes.incrementAndGet();
                    throw failure;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        var backend = new RenderTargetExpressionModule(template.identity(), resource, template.host(),
            TestExpressionModules.emptyReport(), Map.of(), Map.of(), Map.of(), ExpressionArtifactStore.Source.COLD,
            template.eventCatalog(), template.productionLinkage());
        return new AnimationStore(backend, ModelResourcePurpose.FULL);
    }
}
