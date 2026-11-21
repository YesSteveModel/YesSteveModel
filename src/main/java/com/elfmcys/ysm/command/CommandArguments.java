package com.elfmcys.ysm.command;

import com.elfmcys.ysm.YesSteveModel;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.registries.DeferredRegister;

public final class CommandArguments {
    public static final DeferredRegister<ArgumentTypeInfo<?, ?>> TYPES =
        DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, YesSteveModel.MOD_ID);
    static {
        TYPES.register("model_selector", () -> ArgumentTypeInfos.registerByClass(ModelSelectorArgument.class,
            SingletonArgumentInfo.contextFree(ModelSelectorArgument::selector)));
    }

    private CommandArguments() {}
}
