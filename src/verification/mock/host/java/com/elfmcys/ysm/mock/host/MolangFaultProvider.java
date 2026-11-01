package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.api.annotation.YsmEntityComponent;
import com.elfmcys.ysm.api.annotation.MolangFunction;
import com.elfmcys.ysm.api.molang.MolangEffect;
import com.elfmcys.ysm.api.molang.MolangContextView;
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
    public static void trace_event(MolangContextView<Entity> context, float value) {
        context.submit((com.elfmcys.ysm.api.molang.PreparedEffect)() -> EntityLifecycleProbe.trace(value));
    }

    @MolangFunction(effect = MolangEffect.ACTION)
    public static void worker_gate(MolangContextView<Entity> context) {
        EntityLifecycleProbe.block(context);
    }

    @MolangFunction(effect = MolangEffect.ACTION)
    public static Point broken_record(MolangContextView<Entity> context) {
        recordCalls++;
        context.submit((com.elfmcys.ysm.api.molang.PreparedEffect)() -> EntityLifecycleProbe.trace(-9876));
        return null;
    }

    @MolangFunction(effect = MolangEffect.ACTION)
    public static FloatList broken_array(MolangContextView<Entity> context) {
        arrayCalls++;
        context.submit((com.elfmcys.ysm.api.molang.PreparedEffect)() -> EntityLifecycleProbe.trace(-9876));
        return null;
    }
}
