package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.api.annotation.YsmEntityComponent;
import com.elfmcys.ysm.api.annotation.MolangFunction;
import com.elfmcys.ysm.api.molang.MolangEffect;
import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.api.molang.MolangActionView;
import it.unimi.dsi.fastutil.floats.FloatList;
import net.minecraft.world.entity.Entity;

/** Deliberately violates the extension contract only in the isolated development mod. */
@YsmEntityComponent("ysm_mock_host")
public final class MolangFaultProvider {
    static int recordCalls;
    static int arrayCalls;

    public record Point(float x, float y) {
    }

    @MolangFunction(effect = MolangEffect.ACTION)
    public static void trace_event(MolangActionView<Entity> context, float value) {
        EntityLifecycleProbe.trace(value);
    }

    @MolangFunction(effect = MolangEffect.ACTION)
    public static void worker_gate(MolangActionView<Entity> context) {
        EntityLifecycleProbe.block(context);
    }

    @MolangFunction(effect = MolangEffect.ACTION)
    public static Point broken_record(MolangActionView<Entity> context) {
        recordCalls++;
        context.playSound("recovery_discarded", "minecraft:block.note_block.harp", 6, 1, 1);
        return null;
    }

    @MolangFunction(effect = MolangEffect.ACTION)
    public static FloatList broken_array(MolangActionView<Entity> context) {
        arrayCalls++;
        context.playSound("recovery_discarded", "minecraft:block.note_block.harp", 6, 1, 1);
        return null;
    }
}
