package independent;

import com.elfmcys.ysm.api.annotation.*;
import com.elfmcys.ysm.api.molang.*;
import com.elfmcys.ysm.expression.host.EntityComponent;
import optional.a.Subject;
import optional.b.Api;

@YsmEntityComponent(value = "matrix_mod", dependencies = {@MolangDependency(modId = "a", version = "[1,2)"),
    @MolangDependency(modId = "b", version = "[1,2)")})
public final class Combined implements EntityComponent<Subject> {
    public Api optionalField;
    static {
        count("combined.init");
        if (Boolean.getBoolean("matrix.failInitialization"))
            throw new IllegalStateException("matrix initialization source");
    }

    public Combined(com.elfmcys.ysm.client.entity.AnimatableEntity<Subject> owner) {
        count("combined.construct");
    }

    public Api optionalSignature(Api input) {
        return input;
    }

    @MolangFunction(effect = MolangEffect.ACTION)
    public int action(MolangActionView<Subject> context, float ignored) {
        count("combined.action");
        return Api.value();
    }

    @MolangFunction(effect = MolangEffect.CONTEXT)
    public int read(MolangContextView<Subject> context) {
        return 9;
    }

    @MolangFunction(effect = MolangEffect.CONTEXT)
    public static int fixed(MolangContextView<Subject> context) {
        return 9;
    }

    private static void count(String name) {
        System.setProperty(name, Integer.toString(Integer.getInteger(name, 0) + 1));
    }
}
