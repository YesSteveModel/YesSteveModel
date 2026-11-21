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

    @MolangFunction(alwaysOrigin = true, action = true)
    public int action(MolangContextView<Subject> context, float ignored) {
        count("combined.action");
        return Api.value();
    }

    @MolangFunction(alwaysOrigin = true)
    public int read(MolangContextView<Subject> context) {
        return 9;
    }

    @MolangFunction
    public static int fixed(MolangContextView<Subject> context) {
        return 9;
    }

    @MolangFunction(alwaysOrigin = true)
    public float member_plain_fixed(float value) {
        return value;
    }

    @MolangFunction(alwaysOrigin = true)
    public float member_plain_optional(int argc, @DefaultValue("5") float value) {
        return argc + value;
    }

    @MolangFunction(alwaysOrigin = true, maxArgs = 5)
    public float member_plain_tail(int argc, float... values) {
        return argc + values.length;
    }

    @MolangFunction(alwaysOrigin = true)
    public float member_view_fixed(MolangContextView<Subject> context, float value) {
        return value;
    }

    @MolangFunction(alwaysOrigin = true)
    public float member_view_optional(MolangContextView<Subject> context, int argc, @DefaultValue("5") float value) {
        return argc + value;
    }

    @MolangFunction(alwaysOrigin = true, maxArgs = 5)
    public float member_view_tail(MolangContextView<Subject> context, int argc, float... values) {
        return argc + values.length;
    }

    @MolangFunction
    public static float static_plain_fixed(float value) {
        return value;
    }

    @MolangFunction
    public static float static_plain_optional(int argc, @DefaultValue("5") float value) {
        return argc + value;
    }

    @MolangFunction(maxArgs = 5)
    public static float static_plain_tail(int argc, float... values) {
        return argc + values.length;
    }

    @MolangFunction
    public static float static_view_fixed(MolangContextView<Subject> context, float value) {
        return value;
    }

    @MolangFunction
    public static float static_view_optional(MolangContextView<Subject> context, int argc,
        @DefaultValue("5") float value) {
        return argc + value;
    }

    @MolangFunction(maxArgs = 5)
    public static float static_view_tail(MolangContextView<Subject> context, int argc, float... values) {
        return argc + values.length;
    }

    @MolangNullable
    @MolangFunction
    public static float nullable_float(float value) {
        return value == 1 ? Float.NaN
            : value == 2 ? Float.POSITIVE_INFINITY : value == 3 ? Float.NEGATIVE_INFINITY : value;
    }

    @MolangFunction(action = true)
    public static float plain_action(float value) {
        count("plain.action");
        return value;
    }

    private static void count(String name) {
        System.setProperty(name, Integer.toString(Integer.getInteger(name, 0) + 1));
    }
}
