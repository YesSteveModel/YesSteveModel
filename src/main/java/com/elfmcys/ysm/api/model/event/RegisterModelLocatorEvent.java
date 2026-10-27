package com.elfmcys.ysm.api.model.event;

import com.elfmcys.ysm.api.model.ModelKind;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocator;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.event.IModBusEvent;

import java.util.function.Function;

public class RegisterModelLocatorEvent extends Event implements IModBusEvent {
    private final ModelKind kind;
    private final Function<String, GeoLocator> registerFunction;

    public RegisterModelLocatorEvent(ModelKind kind, Function<String, GeoLocator> registerFunction) {
        this.kind = kind;
        this.registerFunction = registerFunction;
    }

    public ModelKind kind() {
        return kind;
    }

    public GeoLocator register(String locatorName) {
        return registerFunction.apply(locatorName);
    }
}
