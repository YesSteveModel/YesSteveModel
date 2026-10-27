package com.elfmcys.ysm.natives.legacy;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLegacyImporterTest {
    @Test
    void declaresImportAndSharedOwnerEntryPoints() {
        var methods = Arrays.stream(NativeLegacyImporter.class.getDeclaredMethods())
            .filter(method -> Modifier.isNative(method.getModifiers()))
            .sorted((left, right) -> left.getName().compareTo(right.getName())).toList();

        assertEquals(2, methods.size());
        assertEquals("nImport", methods.get(0).getName());
        assertEquals(NativeLegacyImportResult.Output.class, methods.get(0).getReturnType());
        assertEquals(Arrays.asList(Object.class, long.class), Arrays.asList(methods.get(0).getParameterTypes()));
        assertEquals("nShare", methods.get(1).getName());
        assertEquals(long.class, methods.get(1).getReturnType());
        assertEquals(Arrays.asList(long.class), Arrays.asList(methods.get(1).getParameterTypes()));
        assertTrue(methods.stream().allMatch(method -> Modifier.isStatic(method.getModifiers())));
    }
}
