package com.elfmcys.ysm.api.internal.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.elfmcys.ysm.api.annotation.YsmEventHandler;
import java.lang.annotation.ElementType;
import java.util.List;
import java.util.Map;
import net.minecraftforge.forgespi.language.ModFileScanData;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class ExtensionEventHandlerLoaderTest {
    @Test
    void selectsTypeAnnotationsByNameThenDeduplicatesAndSortsHandlers() {
        ModFileScanData first = new ModFileScanData();
        first.getAnnotations().add(annotation(ElementType.TYPE, "sample.ZHandler", YsmEventHandler.class));
        first.getAnnotations().add(annotation(ElementType.METHOD, "sample.Ignored#method", YsmEventHandler.class));
        first.getAnnotations().add(annotation(ElementType.TYPE, "sample.IgnoredAnnotation", Deprecated.class));

        ModFileScanData second = new ModFileScanData();
        second.getAnnotations().add(annotation(ElementType.TYPE, "sample.A$Nested", YsmEventHandler.class));
        second.getAnnotations().add(annotation(ElementType.TYPE, "sample.ZHandler", YsmEventHandler.class));

        assertEquals(List.of("sample.A$Nested", "sample.ZHandler"),
            ExtensionEventHandlerLoader.discoverHandlerNames(List.of(first, second)));
    }

    private static ModFileScanData.AnnotationData annotation(ElementType target, String memberName,
        Class<?> annotationType) {
        return new ModFileScanData.AnnotationData(Type.getType(annotationType), target, Type.getType(Object.class),
            memberName, Map.of());
    }
}
