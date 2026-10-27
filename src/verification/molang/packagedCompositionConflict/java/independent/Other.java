package independent;

import com.elfmcys.ysm.api.annotation.*;
import com.elfmcys.ysm.api.molang.*;
import net.minecraft.world.entity.Entity;

@YsmEntityComponent("matrix_mod")
public final class Other {
    static {
        System.setProperty("composition.init", "3");
    }

    @MolangFunction
    public static int other(MolangContextView<Entity> view) {
        return 9;
    }
}
