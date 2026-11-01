package com.elfmcys.ysm.expression.host;

import dev.semaweave.BuiltinType;
import dev.semaweave.registry.InvocationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HostBindingContractTest {
    @Test
    void rejectedOptionalClassPublishesNoContractAndAcceptedStaticClassHasNoProvider() {
        var stableId = "ctrl:carryon_is_princess";
        var absent = ExpressionHost.create(HostAvailability.none());
        assertTrue(absent.registry().variable(new dev.semaweave.SymbolKey(stableId)).isEmpty());
        assertTrue(absent.builtins().stream().noneMatch(d -> d.stableId().equals(stableId)));
        var present = ExpressionHost.create(HostAvailability.all()).builtins().stream()
            .filter(d -> d.stableId().equals(stableId)).findFirst().orElseThrow();
        assertEquals(InvocationKind.STATIC, present.binding().invocationKind());
        assertTrue(present.providerId().isEmpty());
    }

    @Test
    void soundIdsStayInternalAndScalarStringOverloadsKeepNamespaces() {
        var numeric = ModelSoundId.numeric(7);
        var text = ModelSoundId.string("7");
        assertNotEquals(numeric, text);
        assertThrows(IllegalArgumentException.class, () -> ModelSoundId.numeric(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> ModelSoundId.string("\uD800"));

        var registry = ExpressionHost.create(HostAvailability.all()).registry();
        assertFalse(registry.types().containsKey("ysm:sound-id"));
        for (var symbol : java.util.List.of("ysm:play_sound", "ysm:stop_sound")) {
            var group = registry.function(new dev.semaweave.SymbolKey(symbol)).orElseThrow();
            assertEquals(java.util.Set.of(BuiltinType.F32, BuiltinType.STRING), group.methods().stream()
                .map(method -> method.parameterTypes().get(0)).collect(java.util.stream.Collectors.toSet()));
        }
    }
}
