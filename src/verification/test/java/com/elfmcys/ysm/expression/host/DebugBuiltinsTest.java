package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.api.molang.MolangEffect;
import com.elfmcys.ysm.api.molang.MolangInputs;
import com.elfmcys.ysm.client.animation.debug.DebugSource;
import com.elfmcys.ysm.expression.host.builtin.DebugBuiltins;
import com.elfmcys.ysm.expression.host.builtin.DebugOutputBuiltins;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.util.UnsafeUtil;
import java.util.ArrayList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DebugBuiltinsTest {
    @Test
    void declarationsRequireActionAndCuriosIsOptional() {
        var absent = ExpressionHost.create(HostAvailability.none());
        var present = ExpressionHost.create(HostAvailability.all());
        for (var name : new String[] {"dump_mods", "dump_effects", "dump_biome", "dump_equipped_item",
            "dump_relative_block", "dump_curios"}) {
            var symbol = "ysm:" + name;
            assertEquals(MolangEffect.ACTION, BuiltinDeclarations.group(symbol).effect());
            assertTrue(present.registry().function(new dev.semaweave.SymbolKey(symbol)).isPresent(), symbol);
            assertEquals(!name.equals("dump_curios"),
                absent.registry().function(new dev.semaweave.SymbolKey(symbol)).isPresent(), symbol);
        }
    }

    @Test
    void debugOutputExistsOnlyDuringEnabledEvaluation() throws Exception {
        ComponentScopeTest.initializeMinecraftEntityClasses();
        var builtin = new DebugBuiltins(null);
        var messages = new ArrayList<String>();
        var debug = new DebugSource() {
            @Override
            public void print(String message, Object... args) {
                messages.add(String.format(message, args));
            }

            @Override
            public void print(Component message) {
                messages.add(message.getString());
            }
        };
        var entity = (Entity)UnsafeUtil.getUnsafe().allocateInstance(ArmorStand.class);
        var view = new View(entity);
        builtin.dumpEquippedItem(view, "invalid");
        builtin.dumpRelativeBlock(null, 6, 0, 0);
        assertTrue(messages.isEmpty());

        builtin.beginEval(new AnimationEvent<>(null, 0, 0, 0, null, null, debug));
        builtin.dumpEquippedItem(view, "invalid");
        builtin.dumpRelativeBlock(null, 6, 0, 0);
        assertEquals(java.util.List.of("Illegal slot type: invalid."), messages);
        builtin.endEval();
        builtin.dumpEquippedItem(view, "invalid");
        assertEquals(1, messages.size());
    }

    @Test
    void mixedAndNestedDebugMessagesAreSeparateAndClearedAfterEvaluation() {
        var output = new DebugOutputBuiltins(null);
        assertFalse(output.enabled());
        var messages = new ArrayList<String>();
        var debug = new DebugSource() {
            @Override
            public void print(String message, Object... args) {
                messages.add(String.format(message, args));
            }

            @Override
            public void print(Component message) {
                messages.add(message.getString());
            }
        };
        output.beginEval(new AnimationEvent<>(null, 0, 0, 0, null, null, debug));
        output.begin();
        output.append("outer ");
        output.begin();
        output.append("inner ");
        output.append(2f);
        output.end();
        output.append(true);
        output.end();
        assertEquals(java.util.List.of("inner 2.0", "outer true"), messages);
        output.begin();
        output.append("abandoned");
        output.endEval();
        output.beginEval(new AnimationEvent<>(null, 0, 0, 0, null, null, debug));
        output.end();
        assertEquals(2, messages.size());
        output.endEval();
    }

    private record View(Entity subject) implements MolangContextView<Entity> {
        public com.elfmcys.ysm.api.molang.SubjectSampleView subjectSample() {
            return SubjectSample.MISSING;
        }

        @Override
        public MolangInputs inputs() {
            throw new AssertionError();
        }

        @Override
        public void defer(float delay, float... values) {
            throw new AssertionError();
        }
    }
}
