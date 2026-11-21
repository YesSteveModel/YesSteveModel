package independent;

import com.elfmcys.ysm.api.annotation.YsmEntityComponent;
import com.elfmcys.ysm.api.annotation.MolangFunction;
import com.elfmcys.ysm.api.molang.MolangContextView;

@YsmEntityComponent("weapon")
public final class PackagedMolangProvider {
    private static int calls;

    private PackagedMolangProvider() {}

    public record Pair(int base, float value) {
    }

    @MolangFunction
    public static float scalar(MolangContextView<net.minecraft.world.entity.Entity> context, float value) {
        return value + 1;
    }

    @MolangFunction
    public static Pair pair(MolangContextView<net.minecraft.world.entity.Entity> context) {
        return new Pair(7, 2.0f);
    }

    @MolangFunction(action = true)
    public static void readAction(MolangContextView<net.minecraft.world.entity.Entity> context) {}

    @MolangFunction(action = true, namespace = "weapon_actions")
    public static void action(MolangContextView<net.minecraft.world.entity.Entity> context) {}

    @MolangFunction(maxArgs = 4)
    public static float sum(MolangContextView<net.minecraft.world.entity.Entity> context, int argc, float base,
        float first, float... tail) {
        calls++;
        var result = base + argc + first;
        for (var value : tail)
            result += value;
        return result;
    }

    public static int calls() {
        return calls;
    }
}
