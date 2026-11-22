package com.createairlogistics;

import com.createairlogistics.config.ModClientConfig;
import com.createairlogistics.config.ModServerConfig;
import com.createairlogistics.content.contraption.AirshipEntity;
import com.createairlogistics.content.navigation.SkyNetworkSavedData;
import com.createairlogistics.registry.ModBlockEntities;
import com.createairlogistics.registry.ModBlocks;
import com.createairlogistics.registry.ModContraptionTypes;
import com.createairlogistics.registry.ModCreativeTabs;
import com.createairlogistics.registry.ModDataComponents;
import com.createairlogistics.registry.ModDisplaySources;
import com.createairlogistics.registry.ModEntityTypes;
import com.createairlogistics.registry.ModItems;
import com.createairlogistics.registry.ModNetwork;
import com.createairlogistics.registry.ModParticles;
import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

@Mod(CreateAirLogistics.MOD_ID)
public class CreateAirLogistics {

    public static final String MOD_ID = "create_air_logistics";
    public static final String NAME = "Create: Air Logistics";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MOD_ID)
            .defaultCreativeTab((ResourceKey<CreativeModeTab>) null)
            .setTooltipModifierFactory(item -> TooltipModifier.mapNull(KineticStats.create(item)));

    public CreateAirLogistics(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Initializing {} (Create v0.6+ Sky-Track & Airship Logistics)", NAME);

        REGISTRATE.registerEventListeners(modEventBus);

        ModDataComponents.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModContraptionTypes.register(modEventBus);
        ModParticles.register(modEventBus);
        ModClientConfig.register(modContainer, modEventBus);
        ModServerConfig.register(modContainer, modEventBus);
        ModDisplaySources.register();
        ModBlocks.register();
        ModItems.register();
        ModBlockEntities.register();
        ModEntityTypes.register();

        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(ModNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onLevelTick);
        NeoForge.EVENT_BUS.addListener(this::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(this::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(this::onExplosionDetonate);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.createairlogistics.client.ModClientEvents.init(modEventBus, modContainer);
        }
    }

    private void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        Vec3 center = event.getExplosion().center();
        float radius = event.getExplosion().radius();
        AABB searchBox = new AABB(BlockPos.containing(center)).inflate(radius + 16.0);
        List<AirshipEntity> airships = serverLevel.getEntitiesOfClass(AirshipEntity.class, searchBox);
        for (AirshipEntity airship : airships) {
            if (airship.isRemoved()) continue;
            if (airship.isHitByExplosion(center, radius, event.getAffectedBlocks())) {
                airship.getAirshipId().ifPresent(id -> {
                    SkyNetworkSavedData network = SkyNetworkSavedData.get(serverLevel);
                    Set<BlockPos> placedBlocks = network.disassembleAirshipAndGetBlocks(serverLevel, id, null);
                    double blastRangeSq = (radius + 0.75) * (radius + 0.75);
                    for (BlockPos placedPos : placedBlocks) {
                        if (placedPos.distToCenterSqr(center) <= blastRangeSq && !event.getAffectedBlocks().contains(placedPos)) {
                            event.getAffectedBlocks().add(placedPos);
                        }
                    }
                });
            }
        }
    }

    private void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof Level level) {
            if (isOccupiedByAirship(level, event.getPos())) {
                event.setCanceled(true);
            }
        }
    }

    private void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (event.getItemStack().getItem() instanceof BlockItem && event.getFace() != null) {
            BlockPos targetPos = level.getBlockState(event.getPos()).canBeReplaced()
                    ? event.getPos()
                    : event.getPos().relative(event.getFace());
            if (isOccupiedByAirship(level, targetPos)) {
                event.setCanceled(true);
            }
        }
    }

    private static boolean isOccupiedByAirship(Level level, BlockPos pos) {
        List<AirshipEntity> airships = level.getEntitiesOfClass(
                AirshipEntity.class,
                new AABB(pos).inflate(16.0)
        );
        for (AirshipEntity airship : airships) {
            if (airship.occupiesWorldBlock(pos)) {
                return true;
            }
        }
        return false;
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.SKY_DOCK.get(),
                (be, side) -> be.getItemBuffer()
        );
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                ModBlockEntities.SKY_DOCK.get(),
                (be, side) -> be.getFluidBuffer()
        );
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.AIRSHIP_CARGO_HOLD.get(),
                (be, side) -> be.getInventory()
        );
    }

    private void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            SkyNetworkSavedData.get(serverLevel).tick(serverLevel);
        }
    }

    /** C-2/C-3: Sunucu kapanmadan önce tüm canlı gemi contraption'larını SavedData'ya yaz (son kayıt güncel olsun). */
    private void onServerStopping(ServerStoppingEvent event) {
        for (ServerLevel serverLevel : event.getServer().getAllLevels()) {
            SkyNetworkSavedData.get(serverLevel).syncAllLiveEntities(serverLevel);
        }
    }

    /** C-5: Çıkış/kapanış sırasında gemi koltuğunda olan oyuncuyu aynı koltuğa geri oturt. */
    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerLevel serverLevel = sp.serverLevel();
            SkyNetworkSavedData.get(serverLevel).tryRemountLoggingInPlayer(serverLevel, sp);
        }
    }

    /** C-5: Vanilla çıkışta oyuncuyu araçtan indirir; indirilmeden önce koltuk indeksini kaydet. */
    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            SkyNetworkSavedData.get(sp.serverLevel()).recordLoggingOutPlayer(sp);
        }
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
