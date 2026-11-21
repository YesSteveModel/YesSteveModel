package com.elfmcys.ysm.geckolib3.core.manager;

import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnimationDataTest {
    @Test
    void failedControllerCleanupStillClearsTheOtherControllersAndLookupReferences() {
        var events = new ArrayList<String>();
        var failure = new IllegalStateException("first controller cleanup");
        var data = new AnimationData();
        var first = controller("first", events, failure);
        var second = controller("second", events, null);
        data.addAnimationController(first);
        data.addAnimationController(second);
        assertSame(first, data.getAnimationController("first"));
        assertSame(failure, assertThrows(IllegalStateException.class, data::reset));
        assertEquals(List.of("first", "second"), events);
        assertTrue(data.getAnimationControllers().isEmpty());
        assertNull(data.getAnimationController("first"));
        assertNull(data.getAnimationController("second"));
    }

    @SuppressWarnings("rawtypes")
    private static IAnimationController controller(String name, List<String> events, RuntimeException failure) {
        return (IAnimationController)Proxy.newProxyInstance(IAnimationController.class.getClassLoader(),
            new Class<?>[] {IAnimationController.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("getName"))
                    return name;
                if (method.getName().equals("clear")) {
                    events.add(name);
                    if (failure != null)
                        throw failure;
                }
                return null;
            });
    }
}
