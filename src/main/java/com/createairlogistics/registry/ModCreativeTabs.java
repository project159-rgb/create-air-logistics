package com.createairlogistics.registry;

import com.createairlogistics.CreateAirLogistics;
import com.simibubi.create.AllCreativeModeTabs;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {

    private static final DyeColor[] BALLOON_SPECTRUM_ORDER = {
            DyeColor.WHITE,
            DyeColor.LIGHT_GRAY,
            DyeColor.GRAY,
            DyeColor.BLACK,
            DyeColor.BROWN,
            DyeColor.RED,
            DyeColor.ORANGE,
            DyeColor.YELLOW,
            DyeColor.LIME,
            DyeColor.GREEN,
            DyeColor.CYAN,
            DyeColor.LIGHT_BLUE,
            DyeColor.BLUE,
            DyeColor.PURPLE,
            DyeColor.MAGENTA,
            DyeColor.PINK
    };

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateAirLogistics.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.create_air_logistics.main"))
                    .withTabsBefore(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .icon(ModBlocks.PATHFINDER_PROPELLER::asStack)
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.SKY_DOCK.asStack());
                        output.accept(ModBlocks.SHIP_PORT.asStack());
                        output.accept(ModBlocks.PATHFINDER_PROPELLER.asStack());
                        output.accept(ModBlocks.BOOST_PROPELLER.asStack());
                        output.accept(ModBlocks.PRESSURE_ENGINE.asStack());
                        output.accept(ModBlocks.PRESSURE_TANK.asStack());
                        output.accept(ModBlocks.AIRSHIP_CARGO_HOLD.asStack());
                        output.accept(ModBlocks.AIRSHIP_SUPER_HEATER.asStack());
                        output.accept(ModItems.FLIGHT_CHART.asStack());
                        output.accept(ModItems.WOODEN_PROPELLER.asStack());
                        output.accept(ModItems.MECHANICAL_PATHFINDER.asStack());
                        for (DyeColor color : BALLOON_SPECTRUM_ORDER) {
                            output.accept(ModBlocks.getBalloon(color).asStack());
                        }
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
