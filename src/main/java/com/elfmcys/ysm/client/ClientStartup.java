package com.elfmcys.ysm.client;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.AnimationRegister;
import com.elfmcys.ysm.client.compat.ARCompat;
import com.elfmcys.ysm.client.compat.CosmeticArmorCompat;
import com.elfmcys.ysm.client.compat.ElytraSlotCompat;
import com.elfmcys.ysm.client.compat.FirstPersonCompat;
import com.elfmcys.ysm.client.compat.ImmersiveAircraftCompat;
import com.elfmcys.ysm.client.compat.IrisCompat;
import com.elfmcys.ysm.client.compat.OptifineCompat;
import com.elfmcys.ysm.client.compat.PlayerAnimatorCompat;
import com.elfmcys.ysm.client.compat.SimplePlaneCompat;
import com.elfmcys.ysm.client.compat.backpack.sophisticated.SophisticatedCompat;
import com.elfmcys.ysm.client.compat.bettercombat.BetterCombatCompat;
import com.elfmcys.ysm.client.compat.carryon.CarryOnCompat;
import com.elfmcys.ysm.client.compat.create.CreateCompat;
import com.elfmcys.ysm.client.compat.curios.CuriosCompat;
import com.elfmcys.ysm.client.compat.immersivemelodies.ImmersiveMelodiesCompat;
import com.elfmcys.ysm.client.compat.ironsspellbooks.IronsSpellBooksCompat;
import com.elfmcys.ysm.client.compat.parcool.ParCoolCompat;
import com.elfmcys.ysm.client.compat.realcamera.RealCameraCompat;
import com.elfmcys.ysm.client.compat.simplehat.SimpleHatsCompat;
import com.elfmcys.ysm.client.compat.slashblade.SlashBladeCompat;
import com.elfmcys.ysm.client.compat.swarfare.SWarfareCompat;
import com.elfmcys.ysm.client.compat.swem.SwemCompat;
import com.elfmcys.ysm.client.compat.tacz.TACZCompat;
import com.elfmcys.ysm.client.model.locator.FirstPersonLocator;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.client.model.locator.ProjectileLocator;
import com.elfmcys.ysm.client.model.locator.VehicleLocator;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.expression.host.OptionalCapability;
import com.elfmcys.ysm.expression.host.OptionalCapabilityAdmission;
import com.elfmcys.ysm.model.ModelSystem;
import com.elfmcys.ysm.model.service.ClientModelService;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Optional;
import java.util.TreeMap;

/** Owns the client setup order before the model service becomes visible. */
public final class ClientStartup {
    private ClientStartup() {}

    public static void start(ModelSystem system) {
        AnimationRegister.registerAnimationState();
        var mods = ModList.get();
        var versions = new TreeMap<String, String>();
        mods.getMods().forEach(mod -> versions.put(mod.getModId(), mod.getVersion().toString()));
        var builtinAdmission = new OptionalCapabilityAdmission(mods::isLoaded, versions,
            ClientStartup.class.getClassLoader(), !FMLEnvironment.production);

        builtinAdmission.initialize(OptionalCapability.CURIOS, CuriosCompat::init, CuriosCompat::isInstalled);
        FirstPersonCompat.init();
        RealCameraCompat.init();
        PlayerAnimatorCompat.init();
        builtinAdmission.initialize(OptionalCapability.BETTER_COMBAT, BetterCombatCompat::init,
            BetterCombatCompat::isInstalled);
        IrisCompat.init();
        ARCompat.init();
        OptifineCompat.init();
        CosmeticArmorCompat.init();
        ElytraSlotCompat.init();
        builtinAdmission.initialize(OptionalCapability.TACZ, TACZCompat::init, TACZCompat::isInstalled);
        SWarfareCompat.init();
        builtinAdmission.initialize(OptionalCapability.CARRY_ON, CarryOnCompat::init, CarryOnCompat::isInstalled);
        builtinAdmission.initialize(OptionalCapability.PARCOOL, ParCoolCompat::init, ParCoolCompat::isInstalled);
        builtinAdmission.initialize(OptionalCapability.SLASH_BLADE, SlashBladeCompat::init,
            SlashBladeCompat::isSlashBladeLoaded);
        SwemCompat.init();
        CreateCompat.init();
        builtinAdmission.initialize(OptionalCapability.SOPHISTICATED_BACKPACKS, SophisticatedCompat::init,
            SophisticatedCompat::isInstalled);
        SimpleHatsCompat.init();
        builtinAdmission.initialize(OptionalCapability.IMMERSIVE_MELODIES, ImmersiveMelodiesCompat::init,
            ImmersiveMelodiesCompat::isLoaded);
        IronsSpellBooksCompat.init();
        SimplePlaneCompat.init();
        ImmersiveAircraftCompat.init();

        checkCompatibility(ParCoolCompat.getCompatibilityWarning());
        checkCompatibility(SophisticatedCompat.getCompatibilityWarning());
        var builtinCapabilities = builtinAdmission.finish();
        builtinCapabilities.diagnostics()
            .forEach(message -> YesSteveModel.LOGGER.error("Molang optional capability admission: {}", message));
        if (ClientConfig.DISABLE_SELF_MODEL.get() && ClientConfig.DISABLE_OTHER_MODEL.get()
            && ClientConfig.DISABLE_SELF_HANDS.get()) {
            informIncompatible("epicfight", "Epic Fight");
        }

        PlayerLocator.init();
        FirstPersonLocator.init();
        ProjectileLocator.init();
        VehicleLocator.init();

        ClientModelService.start(system, builtinCapabilities.availability());
    }

    private static void checkCompatibility(Optional<Pair<String, String>> infoHolder) {
        infoHolder.ifPresent(info -> {
            ModLoader.get().addWarning(new ModLoadingWarning(
                LoadingModList.get().getModFileById(YesSteveModel.MOD_ID).getMods().get(0), ModLoadingStage.SIDED_SETUP,
                "error.yes_steve_model.incompatible_mod_version", info.getKey(), info.getValue()));
        });
    }

    private static void informIncompatible(String modId, String modName) {
        if (LoadingModList.get().getModFileById(modId) != null) {
            ModLoader.get().addWarning(
                new ModLoadingWarning(LoadingModList.get().getModFileById(YesSteveModel.MOD_ID).getMods().get(0),
                    ModLoadingStage.SIDED_SETUP, "error.yes_steve_model.incompatible_mod", modName));
        }
    }

}
