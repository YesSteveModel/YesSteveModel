package independent;

import com.elfmcys.ysm.api.annotation.*;
import com.elfmcys.ysm.api.molang.*;
import net.minecraft.world.entity.Entity;

@YsmEntityComponent("matrix_mod")
public final class Probe {
    @MolangFunction(effect = MolangEffect.CONTEXT)
    public static float probe(MolangContextView<Entity> context) {
        System.setProperty("probe.calls", Integer.toString(Integer.getInteger("probe.calls", 0) + 1));
        if (Boolean.getBoolean("matrix.throwProbe"))
            throw new IllegalStateException("probe source");
        return 1;
    }
}
