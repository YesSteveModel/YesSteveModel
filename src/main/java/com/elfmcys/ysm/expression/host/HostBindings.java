package com.elfmcys.ysm.expression.host;

/** Direct public linkage methods referenced by the frozen Semaweave registry. */
public final class HostBindings {
    private HostBindings() {}

    public static MolangVector copyVector(MolangVector value) {
        return value;
    }

    public static MolangVector zeroVector() {
        return new MolangVector(0, 0, 0);
    }

    public static String displayVector(MolangVector value) {
        return value.toString();
    }

    public static boolean equalVector(MolangVector left, MolangVector right) {
        return left.x() == right.x() && left.y() == right.y() && left.z() == right.z();
    }

    public static float vectorX(MolangVector value) {
        return value.x();
    }

    public static float vectorY(MolangVector value) {
        return value.y();
    }

    public static float vectorZ(MolangVector value) {
        return value.z();
    }

    public static float booleanToFloat(boolean value) {
        return value ? 1 : 0;
    }

    public static boolean floatToBoolean(float value) {
        return !Float.isNaN(value) && value != 0;
    }

    public static it.unimi.dsi.fastutil.floats.FloatList emptyFloatList() {
        return it.unimi.dsi.fastutil.floats.FloatLists.EMPTY_LIST;
    }

    public static boolean equalFloatList(it.unimi.dsi.fastutil.floats.FloatList left,
        it.unimi.dsi.fastutil.floats.FloatList right) {
        if (left.size() != right.size())
            return false;
        for (int index = 0; index < left.size(); index++) {
            if (left.getFloat(index) != right.getFloat(index))
                return false;
        }
        return true;
    }
}
