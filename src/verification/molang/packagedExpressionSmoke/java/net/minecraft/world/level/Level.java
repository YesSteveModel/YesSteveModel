package net.minecraft.world.level;

/** Deterministic read input for the packaged builtin benchmark. */
public class Level {
    public long dayTime;
    public long reads;

    public long m_46468_() {
        reads++;
        return dayTime;
    }
}
