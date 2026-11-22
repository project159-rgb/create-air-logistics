package com.createairlogistics.ponder;

import com.createairlogistics.CreateAirLogistics;
import com.createairlogistics.registry.ModBlocks;
import com.createairlogistics.registry.ModItems;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;

/**
 * Create: Air Logistics - Ponder [W] Eklenti Kaydı
 *
 * Tüm sahneler kullanıcının inşa ettiği gerçek gemi şemasını kullanır:
 * {@code assets/create_air_logistics/ponder/ponderairship2.nbt} (11x8x11).
 */
public class ModPonderPlugin implements PonderPlugin {

    /** Tüm sahnelerin ortak şeması. */
    private static final String SHIP_SCHEMATIC = "ponderairship2";
    private static final String HEAVY_THRUSTER_SCHEMATIC = "ponder_heavy_thrusters";
    private static final String LIQUID_TRANSPORT_SCHEMATIC = "ponder_liquid_transport";

    @Override
    public String getModId() {
        return CreateAirLogistics.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        // Basınç blokları: 1. sahne doğrudan Basınçlı Hava rehberi
        for (ResourceLocation id : new ResourceLocation[]{
                ModBlocks.PRESSURE_ENGINE.getId(),
                ModBlocks.PRESSURE_TANK.getId(),
                ModBlocks.BOOST_PROPELLER.getId()
        }) {
            helper.addStoryBoard(id, HEAVY_THRUSTER_SCHEMATIC, ModPonderScenes::pressureAndDockPowerGuide);
        }

        // Süper ısıtıcı: 1. sahne doğrudan Ağırlık ve Balon Isı Çarpanları rehberi
        helper.addStoryBoard(ModBlocks.AIRSHIP_SUPER_HEATER.getId(), SHIP_SCHEMATIC, ModPonderScenes::balloonPhysicsAndWeights);

        // Tüm blok ve eşyalar: Tam Gemi Kurulumu + Super Glue rehberi
        for (ResourceLocation id : new ResourceLocation[]{
                ModBlocks.SKY_DOCK.getId(),
                ModBlocks.SHIP_PORT.getId(),
                ModBlocks.PATHFINDER_PROPELLER.getId(),
                ModBlocks.BOOST_PROPELLER.getId(),
                ModBlocks.PRESSURE_ENGINE.getId(),
                ModBlocks.PRESSURE_TANK.getId(),
                ModBlocks.AIRSHIP_CARGO_HOLD.getId(),
                ModBlocks.AIRSHIP_SUPER_HEATER.getId(),
                ModItems.FLIGHT_CHART.getId(),
                ModItems.WOODEN_PROPELLER.getId(),
                ModItems.MECHANICAL_PATHFINDER.getId()
        }) {
            helper.addStoryBoard(id, SHIP_SCHEMATIC, ModPonderScenes::airshipMasterGuide);
        }
        for (DyeColor color : DyeColor.values()) {
            ResourceLocation id = ModBlocks.getBalloon(color).getId();
            helper.addStoryBoard(id, SHIP_SCHEMATIC, ModPonderScenes::airshipMasterGuide);
        }

        // Liman gücü, basınç transferi ve manometreler
        for (ResourceLocation id : new ResourceLocation[]{
                ModBlocks.SKY_DOCK.getId(),
                ModBlocks.SHIP_PORT.getId(),
                ModBlocks.PATHFINDER_PROPELLER.getId()
        }) {
            helper.addStoryBoard(id, HEAVY_THRUSTER_SCHEMATIC, ModPonderScenes::pressureAndDockPowerGuide);
        }

        // Uçuş haritası, rota ve kargo otomasyonu
        for (ResourceLocation id : new ResourceLocation[]{
                ModItems.FLIGHT_CHART.getId(),
                ModBlocks.SKY_DOCK.getId(),
                ModBlocks.PATHFINDER_PROPELLER.getId(),
                ModBlocks.AIRSHIP_CARGO_HOLD.getId(),
                ModBlocks.SHIP_PORT.getId()
        }) {
            helper.addStoryBoard(id, SHIP_SCHEMATIC, ModPonderScenes::flightChartAndDockGuide);
        }

        // Ağırlık sınıfları ve balon ısı çarpanları
        helper.addStoryBoard(ModBlocks.AIRSHIP_CARGO_HOLD.getId(), SHIP_SCHEMATIC, ModPonderScenes::balloonPhysicsAndWeights);
        for (DyeColor color : DyeColor.values()) {
            ResourceLocation id = ModBlocks.getBalloon(color).getId();
            helper.addStoryBoard(id, SHIP_SCHEMATIC, ModPonderScenes::balloonPhysicsAndWeights);
        }

        // Sıvı taşımacılığı: borular, sıvı tankları ve otomatik pompalama
        for (ResourceLocation id : new ResourceLocation[]{
                ModBlocks.SKY_DOCK.getId(),
                ModBlocks.SHIP_PORT.getId(),
                ModBlocks.AIRSHIP_CARGO_HOLD.getId()
        }) {
            helper.addStoryBoard(id, LIQUID_TRANSPORT_SCHEMATIC, ModPonderScenes::fluidTransportGuide);
        }
    }

    public static void register() {
        PonderIndex.addPlugin(new ModPonderPlugin());
    }
}
