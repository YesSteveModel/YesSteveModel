package com.elfmcys.ysm.testutil;

import com.elfmcys.ysm.natives.render.NativeBakedModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class NativeLibraryLoadTest {
    @Test
    void loadsNativeLibraryBeforeTestsRun() {
        assertDoesNotThrow(() -> NativeBakedModel.capability());
    }
}
