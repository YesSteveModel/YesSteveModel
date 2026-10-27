package com.elfmcys.ysm.expression.host;

import dev.semaweave.BuiltinType;
import dev.semaweave.RoamingRole;
import dev.semaweave.registry.InvocationKind;
import com.elfmcys.ysm.expression.host.builtin.ModelBuiltins;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HostBindingContractTest {
    @Test
    void rejectedOptionalClassPublishesNoContractAndAcceptedStaticClassHasNoProvider() {
        var stableId = HostSymbol.CTRL_CARRYON_IS_PRINCESS.stableId();
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
        for (var symbol : java.util.List.of(HostSymbol.YSM_PLAY_SOUND, HostSymbol.YSM_STOP_SOUND)) {
            var group = registry.function(new dev.semaweave.SymbolKey(symbol.stableId())).orElseThrow();
            assertEquals(java.util.Set.of(BuiltinType.F32, BuiltinType.STRING), group.methods().stream()
                .map(method -> method.parameterTypes().get(0)).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void optionalAndVariadicAdaptersPreserveSuppliedArgumentsAndTypedPayloads() {
        var fixture = new ControlledHostFixture();
        var context = logical(fixture);
        context.bindExpression("binding", 0);
        context.beginTopLevel(true);
        try {
            var view = context;
            ModelBuiltins.playSound(view, 2, "voice", "ysm:test", 0, 1, 1);
            assertThrows(IllegalArgumentException.class, () -> ModelBuiltins.particle(view, 2, "ysm:spark", Float.NaN));
        } finally {
            context.endTopLevel();
            context.releaseExpression();
        }
        assertEquals(1, context.effects().orElseThrow().size());

        var event = EventArguments.deferredFloats(2.5f);
        assertEquals(2.5f, event.getFloat(0));
        assertThrows(IndexOutOfBoundsException.class, () -> event.getFloat(1));
        context.commit(fixture);
        var sound = assertInstanceOf(EffectCommand.Sound.class, fixture.committedEffects().get(0));
        assertEquals(ModelSoundId.string("voice"), sound.soundId());
        assertEquals("ysm:test", sound.name());
        assertEquals(0, sound.mode());
        assertEquals(1, sound.volume());
        assertEquals(1, sound.pitch());
    }

    private static MolangContext<Entity> observation(ControlledHostFixture fixture) {
        return MolangContext.observation(new EntityView() {}, new RenderView() {}, fixture,
            com.elfmcys.ysm.expression.TestRoamingStates.create(null, RoamingRole.PRODUCER),
            new InvocationProvenance("target", "program", 1, 2, 3), () -> false, () -> true);
    }

    private static MolangContext<Entity> logical(ControlledHostFixture fixture) {
        return MolangContext.logical(new EntityView() {}, new RenderView() {}, MolangContext.LogicalPhase.ACTION,
            fixture, com.elfmcys.ysm.expression.TestRoamingStates.create(null, RoamingRole.PRODUCER),
            new InvocationProvenance("target", "program", 1, 2, 3), () -> false, () -> true, 8);
    }
}
