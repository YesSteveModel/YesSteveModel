package independent;

import com.elfmcys.ysm.api.annotation.*;
import com.elfmcys.ysm.api.molang.*;
import com.elfmcys.ysm.expression.host.EntityComponent;
import net.minecraft.world.entity.Entity;

@YsmEntityComponent("matrix_mod")
public final class Query implements EntityComponent<Entity> {
    private int sequence;

    static {
        System.setProperty("composition.init", "1");
    }

    public Query(com.elfmcys.ysm.client.entity.AnimatableEntity<Entity> owner) {
        System.setProperty("composition.construct", Integer.toString(Integer.getInteger("composition.construct", 0) + 1));
    }

    @MolangFunction
    public float next(MolangContextView<Entity> view) {
        observe(view);
        System.setProperty("composition.sequence", System.getProperty("composition.sequence", "") + (++sequence) + ",");
        return sequence;
    }

    @MolangFunction
    public float defaulted(MolangContextView<Entity> view, int argc, @DefaultValue("0") float value) {
        return next(view) + value;
    }

    @MolangFunction
    public float missing(MolangContextView<Entity> view) {
        observe(view);
        return Float.NaN;
    }

    public static void observe(Object view) {
        String identity = Integer.toString(System.identityHashCode(view));
        String previous = System.getProperty("composition.view");
        if (previous != null && !previous.equals(identity)) throw new AssertionError("scope view changed");
        System.setProperty("composition.view", identity);
        System.setProperty("composition.views", "1");
    }
}
