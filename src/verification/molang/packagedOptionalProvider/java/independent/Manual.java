package independent;

import com.elfmcys.ysm.api.annotation.*;
import com.elfmcys.ysm.api.molang.*;
import net.minecraft.world.entity.Entity;

@YsmEntityComponent(value = "matrix_mod", mode = MolangAdmissionMode.MANUAL)
public final class Manual {
    @MolangFunction(effect = MolangEffect.CONTEXT)
    public static int manual(MolangContextView<Entity> context) {
        return 6;
    }
}
