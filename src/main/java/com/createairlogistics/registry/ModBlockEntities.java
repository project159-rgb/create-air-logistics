package com.createairlogistics.registry;

import com.createairlogistics.CreateAirLogistics;
import com.createairlogistics.content.contraption.balloon.AirshipSuperHeaterBlockEntity;
import com.createairlogistics.content.contraption.balloon.AirshipSuperHeaterRenderer;
import com.createairlogistics.content.contraption.propulsion.BoostPropellerBlockEntity;
import com.createairlogistics.content.contraption.propulsion.BoostPropellerRenderer;
import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerBlockEntity;
import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerRenderer;
import com.createairlogistics.content.contraption.propulsion.PressureEngineBlockEntity;
import com.createairlogistics.content.dock.SkyDockBlockEntity;
import com.createairlogistics.content.dock.SkyDockRenderer;
import com.createairlogistics.content.logistics.cargo.AirshipCargoHoldBlockEntity;
import com.createairlogistics.content.pressure.PressureGaugeRenderer;
import com.createairlogistics.content.pressure.PressureTankBlockEntity;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

public class ModBlockEntities {

    public static final BlockEntityEntry<SkyDockBlockEntity> SKY_DOCK = CreateAirLogistics.REGISTRATE
            .blockEntity("sky_dock", SkyDockBlockEntity::new)
            .validBlocks(ModBlocks.SKY_DOCK)
            .renderer(() -> SkyDockRenderer::new)
            .register();

    public static final BlockEntityEntry<PathfinderPropellerBlockEntity> PATHFINDER_PROPELLER = CreateAirLogistics.REGISTRATE
            .blockEntity("pathfinder_propeller", PathfinderPropellerBlockEntity::new)
            .validBlocks(ModBlocks.PATHFINDER_PROPELLER)
            .renderer(() -> PathfinderPropellerRenderer::new)
            .register();

    public static final BlockEntityEntry<BoostPropellerBlockEntity> BOOST_PROPELLER = CreateAirLogistics.REGISTRATE
            .blockEntity("boost_propeller", BoostPropellerBlockEntity::new)
            .validBlocks(ModBlocks.BOOST_PROPELLER)
            .renderer(() -> BoostPropellerRenderer::new)
            .register();

    public static final BlockEntityEntry<PressureEngineBlockEntity> PRESSURE_ENGINE = CreateAirLogistics.REGISTRATE
            .blockEntity("pressure_engine", PressureEngineBlockEntity::new)
            .validBlocks(ModBlocks.PRESSURE_ENGINE)
            .renderer(() -> PressureGaugeRenderer.Engine::new)
            .register();

    public static final BlockEntityEntry<PressureTankBlockEntity> PRESSURE_TANK = CreateAirLogistics.REGISTRATE
            .blockEntity("pressure_tank", PressureTankBlockEntity::new)
            .validBlocks(ModBlocks.PRESSURE_TANK)
            .renderer(() -> PressureGaugeRenderer.Tank::new)
            .register();

    public static final BlockEntityEntry<AirshipCargoHoldBlockEntity> AIRSHIP_CARGO_HOLD = CreateAirLogistics.REGISTRATE
            .blockEntity("airship_cargo_hold", AirshipCargoHoldBlockEntity::new)
            .validBlocks(ModBlocks.AIRSHIP_CARGO_HOLD)
            .register();

    public static final BlockEntityEntry<AirshipSuperHeaterBlockEntity> AIRSHIP_SUPER_HEATER = CreateAirLogistics.REGISTRATE
            .blockEntity("airship_super_heater", AirshipSuperHeaterBlockEntity::new)
            .validBlocks(ModBlocks.AIRSHIP_SUPER_HEATER)
            .renderer(() -> AirshipSuperHeaterRenderer::new)
            .register();

    public static void register() {
        CreateAirLogistics.LOGGER.debug("Registering block entities for {}", CreateAirLogistics.MOD_ID);
    }
}
