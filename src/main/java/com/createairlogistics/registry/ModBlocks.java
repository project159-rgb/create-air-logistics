package com.createairlogistics.registry;

import com.createairlogistics.CreateAirLogistics;
import com.createairlogistics.content.contraption.balloon.AirshipSuperHeaterBlock;
import com.createairlogistics.content.contraption.balloon.CanvasBalloonBlock;
import com.createairlogistics.content.contraption.propulsion.BoostPropellerBlock;
import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerBlock;
import com.createairlogistics.content.contraption.propulsion.PressureEngineBlock;
import com.createairlogistics.content.dock.ShipPortBlock;
import com.createairlogistics.content.dock.SkyDockBlock;
import com.createairlogistics.content.logistics.cargo.AirshipCargoHoldBlock;
import com.createairlogistics.content.pressure.AirPressure;
import com.createairlogistics.content.pressure.PressureTankBlock;
import com.simibubi.create.AllMountedStorageTypes;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorageType;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.foundation.data.SharedProperties;
import com.tterrag.registrate.util.entry.BlockEntry;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static com.simibubi.create.foundation.data.TagGen.axeOrPickaxe;
import static com.simibubi.create.foundation.data.TagGen.pickaxeOnly;

/**
 * Create: Air Logistics - Temel Blok Kayıtları:
 * 1. SKY_DOCK (Liman Vinci)
 * 2. SHIP_PORT (Gemi Yanaşma Kapısı)
 * 3. PATHFINDER_PROPELLER (Yön Bulucu Pervane)
 * 4. BOOST_PROPELLER (Takviye Pervanesi)
 * 5. PRESSURE_ENGINE (Basınç Motoru)
 * 6. PRESSURE_TANK (Basınç Tankı)
 * 7. CANVAS_BALLOON (Kanvas Balon)
 * 8. AIRSHIP_CARGO_HOLD (Gemi Ambarı)
 * 9. AIRSHIP_SUPER_HEATER (Hava Gemisi Süper Isıtıcısı)
 */
public class ModBlocks {

    public static final BlockEntry<SkyDockBlock> SKY_DOCK = CreateAirLogistics.REGISTRATE
            .block("sky_dock", SkyDockBlock::new)
            .initialProperties(SharedProperties::copperMetal)
            .properties(p -> p.mapColor(MapColor.COLOR_ORANGE).sound(SoundType.COPPER).noOcclusion())
            .transform(pickaxeOnly())
            .transform(DisplaySource.displaySource(ModDisplaySources.SKY_DOCK_STATUS))
            // Şaft soketi Copper Backtank ile aynı stres etkisine sahiptir (4.0 SU / RPM)
            .onRegister(block -> BlockStressValues.IMPACTS.register(block, () -> AirPressure.DOCK_STRESS_IMPACT))
            .simpleItem()
            .register();

    public static final BlockEntry<ShipPortBlock> SHIP_PORT = CreateAirLogistics.REGISTRATE
            .block("ship_port", ShipPortBlock::new)
            .initialProperties(SharedProperties::copperMetal)
            .properties(p -> p.mapColor(MapColor.COLOR_BROWN).sound(SoundType.WOOD))
            .transform(axeOrPickaxe())
            .simpleItem()
            .register();

    public static final BlockEntry<PathfinderPropellerBlock> PATHFINDER_PROPELLER = CreateAirLogistics.REGISTRATE
            .block("pathfinder_propeller", PathfinderPropellerBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.GOLD).sound(SoundType.NETHERITE_BLOCK).noOcclusion())
            .transform(axeOrPickaxe())
            .simpleItem()
            .register();

    public static final BlockEntry<BoostPropellerBlock> BOOST_PROPELLER = CreateAirLogistics.REGISTRATE
            .block("boost_propeller", BoostPropellerBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.COLOR_ORANGE).sound(SoundType.COPPER).noOcclusion())
            .transform(axeOrPickaxe())
            .simpleItem()
            .register();

    public static final BlockEntry<PressureEngineBlock> PRESSURE_ENGINE = CreateAirLogistics.REGISTRATE
            .block("pressure_engine", PressureEngineBlock::new)
            .initialProperties(SharedProperties::copperMetal)
            .properties(p -> p.mapColor(MapColor.COLOR_ORANGE).sound(SoundType.COPPER).noOcclusion())
            .transform(pickaxeOnly())
            .simpleItem()
            .register();

    public static final BlockEntry<PressureTankBlock> PRESSURE_TANK = CreateAirLogistics.REGISTRATE
            .block("pressure_tank", PressureTankBlock::new)
            .initialProperties(SharedProperties::copperMetal)
            .properties(p -> p.mapColor(MapColor.COLOR_ORANGE).sound(SoundType.COPPER).noOcclusion())
            .transform(pickaxeOnly())
            .simpleItem()
            .register();

    public static final BlockEntry<CanvasBalloonBlock> CANVAS_BALLOON = CreateAirLogistics.REGISTRATE
            .block("canvas_balloon", p -> new CanvasBalloonBlock(p, DyeColor.WHITE))
            .properties(p -> p.mapColor(DyeColor.WHITE.getMapColor()).strength(0.8f).sound(SoundType.WOOL))
            .simpleItem()
            .register();

    public static final Map<DyeColor, BlockEntry<CanvasBalloonBlock>> DYED_CANVAS_BALLOONS;

    static {
        Map<DyeColor, BlockEntry<CanvasBalloonBlock>> map = new EnumMap<>(DyeColor.class);
        map.put(DyeColor.WHITE, CANVAS_BALLOON);
        for (DyeColor color : DyeColor.values()) {
            if (color == DyeColor.WHITE) {
                continue;
            }
            BlockEntry<CanvasBalloonBlock> entry = CreateAirLogistics.REGISTRATE
                    .block(color.getSerializedName() + "_canvas_balloon", p -> new CanvasBalloonBlock(p, color))
                    .properties(p -> p.mapColor(color.getMapColor()).strength(0.8f).sound(SoundType.WOOL))
                    .simpleItem()
                    .register();
            map.put(color, entry);
        }
        DYED_CANVAS_BALLOONS = Collections.unmodifiableMap(map);
    }

    public static BlockEntry<CanvasBalloonBlock> getBalloon(DyeColor color) {
        return DYED_CANVAS_BALLOONS.getOrDefault(color, CANVAS_BALLOON);
    }

    public static final BlockEntry<AirshipCargoHoldBlock> AIRSHIP_CARGO_HOLD = CreateAirLogistics.REGISTRATE
            .block("airship_cargo_hold", AirshipCargoHoldBlock::new)
            .initialProperties(SharedProperties::wooden)
            .properties(p -> p.mapColor(MapColor.WOOD).sound(SoundType.WOOD))
            .transform(axeOrPickaxe())
            .transform(MountedItemStorageType.mountedItemStorage(AllMountedStorageTypes.SIMPLE))
            .simpleItem()
            .register();

    public static final BlockEntry<AirshipSuperHeaterBlock> AIRSHIP_SUPER_HEATER = CreateAirLogistics.REGISTRATE
            .block("airship_super_heater", AirshipSuperHeaterBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.GOLD)
                    .strength(3.5f, 6.0f)
                    .sound(SoundType.METAL)
                    .lightLevel(s -> 15)
                    .noOcclusion()
                    .requiresCorrectToolForDrops())
            .transform(pickaxeOnly())
            .simpleItem()
            .register();

    public static void register() {
        CreateAirLogistics.LOGGER.debug("Registering core blocks for {}", CreateAirLogistics.MOD_ID);
    }
}
