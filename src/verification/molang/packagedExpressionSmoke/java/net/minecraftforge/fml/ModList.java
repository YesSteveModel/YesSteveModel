package net.minecraftforge.fml;

public final class ModList {
    public static ModList get() {
        return new ModList();
    }

    public boolean isLoaded(String id) {
        return true;
    }
}
