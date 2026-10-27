package com.elfmcys.ysm.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.AuthModelsCapability;
import com.elfmcys.ysm.capability.AuthModelsCapabilityProvider;
import com.elfmcys.ysm.capability.ClientLazyCapabilityProvider;
import com.elfmcys.ysm.capability.ModelInfoCapability;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.ModelInfoSyncAssembler;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.ProjectileAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.ProjectileModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.StarModelsCapability;
import com.elfmcys.ysm.capability.StarModelsCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleModelInfoCapabilityProvider;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.MinecraftStateHandler;
import com.elfmcys.ysm.network.forge.PlayerStateHandler;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
import com.elfmcys.ysm.network.protocol.StarredModelSnapshots;
import com.elfmcys.ysm.proto.network.PlayerStateUpdateProto;

import java.util.Optional;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod.EventBusSubscriber
@SuppressWarnings("removal")
public final class CapabilityEvent {
    private static final ResourceLocation MODEL_INFO_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "model_id");
    private static final ResourceLocation PROJECTILE_MODEL_INFO_CAP =
        new ResourceLocation(YesSteveModel.MOD_ID, "projectile_model_id");
    private static final ResourceLocation VEHICLE_MODEL_INFO_CAP =
        new ResourceLocation(YesSteveModel.MOD_ID, "vehicle_model_id");
    private static final ResourceLocation AUTH_MODELS_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "own_models");
    private static final ResourceLocation STAR_MODELS_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "star_models");
    private static final ResourceLocation ANIMATABLE_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "animatable");
    private static final ResourceLocation PROJECTILE_ANIMATABLE_CAP =
        new ResourceLocation(YesSteveModel.MOD_ID, "projectile_animatable");
    private static final ResourceLocation VEHICLE_ANIMATABLE_CAP =
        new ResourceLocation(YesSteveModel.MOD_ID, "vehicle_animatable");
    private static final ResourceLocation CLIENT_LAZY_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "client_lazy");

    @SubscribeEvent
    @SuppressWarnings("resource")
    public static void onAttachCapabilityEvent(AttachCapabilitiesEvent<Entity> event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }

        Entity entity = event.getObject();
        if (entity instanceof Player player) {
            if (!entity.level().isClientSide()
                && !player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).isPresent()
                && !event.getCapabilities().containsKey(MODEL_INFO_CAP)) {
                event.addCapability(MODEL_INFO_CAP, new ModelInfoCapabilityProvider());
            }
            if (!player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP).isPresent()
                && !event.getCapabilities().containsKey(AUTH_MODELS_CAP)) {
                event.addCapability(AUTH_MODELS_CAP, new AuthModelsCapabilityProvider());
            }
            if (!player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP).isPresent()
                && !event.getCapabilities().containsKey(STAR_MODELS_CAP)) {
                event.addCapability(STAR_MODELS_CAP, new StarModelsCapabilityProvider());
            }
        } else if (entity instanceof Projectile) {
            if (!entity.level().isClientSide()
                && !entity.getCapability(ProjectileModelInfoCapabilityProvider.CAP).isPresent()
                && !event.getCapabilities().containsKey(PROJECTILE_MODEL_INFO_CAP)) {
                event.addCapability(PROJECTILE_MODEL_INFO_CAP, new ProjectileModelInfoCapabilityProvider());
            }
        } else {
            if (!entity.level().isClientSide()
                && !entity.getCapability(VehicleModelInfoCapabilityProvider.CAP).isPresent()
                && !event.getCapabilities().containsKey(VEHICLE_MODEL_INFO_CAP)) {
                event.addCapability(VEHICLE_MODEL_INFO_CAP, new VehicleModelInfoCapabilityProvider());
            }
        }

        if (FMLEnvironment.dist == Dist.CLIENT && entity.level().isClientSide()) {
            // 客户端玩家
            if (entity instanceof AbstractClientPlayer clientPlayer
                && !clientPlayer.getCapability(PlayerAnimatableCapabilityProvider.CAP).isPresent()
                && !event.getCapabilities().containsKey(ANIMATABLE_CAP)) {
                event.addCapability(ANIMATABLE_CAP, new PlayerAnimatableCapabilityProvider(clientPlayer));
                return;
            }

            // 客户端懒加载 cap
            if (!entity.getCapability(ClientLazyCapabilityProvider.CAP).isPresent()
                && !event.getCapabilities().containsKey(CLIENT_LAZY_CAP)) {
                var vehicleAnimatable = new VehicleAnimatableCapabilityProvider(entity);
                event.addCapability(VEHICLE_ANIMATABLE_CAP, vehicleAnimatable);

                ProjectileAnimatableCapabilityProvider projectileAnimatable = null;
                if (entity instanceof Projectile projectile) {
                    projectileAnimatable = new ProjectileAnimatableCapabilityProvider(projectile);
                    event.addCapability(PROJECTILE_ANIMATABLE_CAP, projectileAnimatable);
                }

                event.addCapability(CLIENT_LAZY_CAP,
                    new ClientLazyCapabilityProvider(vehicleAnimatable, projectileAnimatable));
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerCloned(PlayerEvent.Clone event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        event.getOriginal().reviveCaps();
        LazyOptional<ModelInfoCapability> oldModelInfoCap = getModelInfoCap(event.getOriginal());
        LazyOptional<AuthModelsCapability> oldAuthModelsCap = getAuthModelsCap(event.getOriginal());
        LazyOptional<StarModelsCapability> oldStarModelsCap = getStarModelsCap(event.getOriginal());
        event.getOriginal().invalidateCaps();

        LazyOptional<ModelInfoCapability> newModelInfoCap = getModelInfoCap(event.getEntity());
        LazyOptional<AuthModelsCapability> newAuthModelsCap = getAuthModelsCap(event.getEntity());
        LazyOptional<StarModelsCapability> newStarModelsCap = getStarModelsCap(event.getEntity());

        newModelInfoCap.ifPresent((newModelInfo) -> oldModelInfoCap.ifPresent(newModelInfo::moveFrom));
        newAuthModelsCap.ifPresent((newAuthModels) -> oldAuthModelsCap.ifPresent(newAuthModels::copyFrom));
        newStarModelsCap.ifPresent((newStarModels) -> oldStarModelsCap.ifPresent(newStarModels::copyFrom));
    }

    @SubscribeEvent
    public static void onTrackingPlayer(PlayerEvent.StartTracking event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (event.getTarget() instanceof ServerPlayer trackPlayer) {
            final Player player = event.getEntity();
            getModelInfoCap(trackPlayer).ifPresent(cap -> {
                if (!NetworkHandler.isPlayerChannelPresent(trackPlayer) && !cap.isMandatory()) {
                    return;
                }
                buildModelInfoPacket(trackPlayer, cap).ifPresent(packet -> {
                    PlayerStateHandler.sendToObserver(packet, player);
                });
            });
        } else if (event.getTarget() instanceof Projectile projectile) {
            projectile.getCapability(ProjectileModelInfoCapabilityProvider.CAP).ifPresent(cap -> {
                if (cap.isInitialized()) {
                    NetworkHandler.sendToClientPlayer(MinecraftStateHandler.projectile(projectile.getId(), cap),
                        event.getEntity());
                }
            });
        } else if (event.getTarget() != null) {
            event.getTarget().getCapability(VehicleModelInfoCapabilityProvider.CAP).ifPresent(cap -> {
                if (cap.isInitialized()) {
                    NetworkHandler.sendToClientPlayer(MinecraftStateHandler.vehicle(event.getTarget().getId(), cap),
                        event.getEntity());
                }
            });
        }
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinLevelEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            getModelInfoCap(serverPlayer).ifPresent(modelInfoCap -> {
                if (!NetworkHandler.isPlayerChannelPresent(serverPlayer) && !modelInfoCap.isMandatory()) {
                    modelInfoCap.consumeDirty();
                    return;
                }
                modelInfoCap.stopAnimation(serverPlayer);
                modelInfoCap.consumeDirty();
            });

            getAuthModelsCap(serverPlayer)
                .ifPresent(authModelsCap -> SessionProtocolHandler.refreshGrants(serverPlayer));

            getStarModelsCap(serverPlayer).ifPresent(starModelCap -> {
                NetworkHandler.sendToClientPlayer(StarredModelSnapshots.create(starModelCap.getStarModels()),
                    serverPlayer);
            });
        }
    }

    /**
     * 同步客户端服务端数据
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (event.phase == TickEvent.Phase.END) {
            var players = event.getServer().getPlayerList().getPlayers();
            var lowBandwidthUsage = ServerConfig.LOW_BANDWIDTH_USAGE.get();
            for (ServerPlayer player : players) {
                getModelInfoCap(player).ifPresent(cap -> {
                    if (!NetworkHandler.isPlayerChannelPresent(player) && !cap.isMandatory()) {
                        cap.consumeDirty();
                        return;
                    }
                    if (cap.consumeDirty()) {
                        cap.getPropertiesTracker().tick(player, false, lowBandwidthUsage);
                        buildModelInfoPacket(player, cap).ifPresent(packet -> {
                            PlayerStateHandler.broadcast(player, packet);
                            if (player.getVehicle() != null && player.getVehicle().getFirstPassenger() == player) {
                                CapabilityEvent.onVehicleSetModel(player.getVehicle(), player);
                            }
                        });
                    } else {
                        cap.getPropertiesTracker().tick(player, true, lowBandwidthUsage);
                    }
                });
            }
            ServerModelService.current().ifPresent(service -> {
                if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
                    var system = ModelRuntime.system();
                    system.tickCatalog();
                    system.tickServerRuntime();
                }
                service.tick();
            });
        }
    }

    public static void onProjectileSetOwner(Projectile projectile, ServerPlayer owner) {
        owner.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(ownerCap -> {
            if (!NetworkHandler.isPlayerChannelPresent(owner) && !ownerCap.isMandatory()) {
                return;
            }
            projectile.getCapability(ProjectileModelInfoCapabilityProvider.CAP).ifPresent(cap -> {
                ownerCap.executeWithMolangVars(molangVars -> {
                    cap.init(ownerCap.getModelId(), molangVars);
                    NetworkHandler.broadcastToVisiblePlayers(MinecraftStateHandler.projectile(projectile.getId(), cap),
                        projectile);
                });
            });
        });
    }

    public static void onVehicleSetModel(Entity vehicle, ServerPlayer owner) {
        owner.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(ownerCap -> {
            if (!NetworkHandler.isPlayerChannelPresent(owner) && !ownerCap.isMandatory()) {
                return;
            }
            vehicle.getCapability(VehicleModelInfoCapabilityProvider.CAP).ifPresent(cap -> {
                // 失败就丢弃
                ownerCap.getMolangVars().ifPresent(molangVars -> {
                    cap.update(ownerCap.getModelId(), molangVars);
                    NetworkHandler.broadcastToVisiblePlayers(MinecraftStateHandler.vehicle(vehicle.getId(), cap),
                        vehicle);
                });
            });
        });
    }

    private static LazyOptional<ModelInfoCapability> getModelInfoCap(Player player) {
        return player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP);
    }

    private static Optional<PlayerStateUpdateProto> buildModelInfoPacket(ServerPlayer player,
        ModelInfoCapability capability) {
        return ServerModelService.current().flatMap(ServerModelService::catalog)
            .flatMap(snapshot -> ModelInfoSyncAssembler.build(player, capability, snapshot));
    }

    private static LazyOptional<AuthModelsCapability> getAuthModelsCap(Player player) {
        return player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP);
    }

    private static LazyOptional<StarModelsCapability> getStarModelsCap(Player player) {
        return player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP);
    }
}
