package independent;

import com.elfmcys.ysm.api.annotation.*;
import com.elfmcys.ysm.api.molang.*;
import optional.a.Subject;

@YsmEntityComponent(value = "matrix_mod", dependencies = @MolangDependency(modId = "a"))
public final class AOnly {
    private AOnly() {
        throw new AssertionError("static declaration constructed");
    }

    @MolangFunction(value = "a_value", effect = MolangEffect.CONTEXT)
    public static int aValue(MolangContextView<Subject> context) {
        return 4;
    }
}
