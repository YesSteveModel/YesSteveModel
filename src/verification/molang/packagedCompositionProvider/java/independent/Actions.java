package independent;

import com.elfmcys.ysm.api.annotation.*;
import com.elfmcys.ysm.api.molang.*;
import net.minecraft.world.entity.Entity;

@YsmEntityComponent("matrix_mod")
public final class Actions {
    static {
        System.setProperty("composition.init", "2");
    }

    private Actions() {
        throw new AssertionError("static provider constructed");
    }

    @MolangFunction(effect = MolangEffect.ACTION)
    public static float action(MolangContextView<Entity> view, int argc, @DefaultValue("0") float value) {
        Query.observe(view);
        view.submit((PreparedEffect)() ->
            System.setProperty("composition.actions", System.getProperty("composition.actions", "") + value + ","));
        return 100 + value;
    }
}
