package com.createairlogistics.config;

import net.createmod.catnip.config.ConfigBase;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Server/world gameplay configuration for Create: Air Logistics.
 * Linked directly into Create's "Access Configs of Other Mods -> Gameplay Settings" screen.
 * Safe to load on a dedicated server (contains zero client-only imports).
 */
public final class ModServerConfig extends ConfigBase {

    private static final ModServerConfig SERVER;

    static {
        Pair<ModServerConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(builder -> {
            ModServerConfig config = new ModServerConfig();
            config.registerAll(builder);
            return config;
        });
        SERVER = pair.getLeft();
        SERVER.specification = pair.getRight();
    }

    public final SkyDockConfig skyDock = nested(0, SkyDockConfig::new, Comments.skyDock);
    public final BalloonsConfig balloons = nested(0, BalloonsConfig::new, Comments.balloons);

    private ModServerConfig() {
    }

    @Override
    public String getName() {
        return "server";
    }

    public static void register(ModContainer modContainer, IEventBus modEventBus) {
        modContainer.registerConfig(ModConfig.Type.SERVER, SERVER.specification);
        modEventBus.addListener((ModConfigEvent.Loading event) -> {
            if (event.getConfig().getSpec() == SERVER.specification) {
                SERVER.onLoad();
            }
        });
        modEventBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == SERVER.specification) {
                SERVER.onReload();
            }
        });
    }

    public static ModConfigSpec serverSpec() {
        return SERVER.specification;
    }

    private static boolean isLoaded() {
        return SERVER.specification != null && SERVER.specification.isLoaded();
    }

    private static int readInt(ConfigInt entry, int fallback, int min, int max) {
        if (!isLoaded()) {
            return fallback;
        }
        try {
            return Mth.clamp(entry.get(), min, max);
        } catch (IllegalStateException ignored) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- Sky Dock accessors

    public static int dockTransferIntervalTicks() {
        return readInt(SERVER.skyDock.transferIntervalTicks, 4, 1, 100);
    }

    public static int dockItemTransferBatch() {
        return readInt(SERVER.skyDock.itemTransferBatch, 64, 1, 512);
    }

    public static int dockFluidTransferBatchMb() {
        return readInt(SERVER.skyDock.fluidTransferBatchMb, 4000, 100, 64000);
    }

    // ---------------------------------------------------------------- Balloon & Heat accessors

    public static int campfireLiftPerBalloon() {
        return readInt(SERVER.balloons.campfireLiftPerBalloon, 4, 1, 64);
    }

    public static int campfireMaxBalloons() {
        return readInt(SERVER.balloons.campfireMaxBalloons, 16, 1, 256);
    }

    public static int superHeaterLiftPerBalloon() {
        return readInt(SERVER.balloons.superHeaterLiftPerBalloon, 6, 1, 128);
    }

    public static int superHeaterMaxBalloons() {
        return readInt(SERVER.balloons.superHeaterMaxBalloons, 32, 1, 512);
    }

    // ---------------------------------------------------------------- Nested Config Groups

    public static final class SkyDockConfig extends ConfigBase {
        public final ConfigInt transferIntervalTicks =
                i(4, 1, 100, "transferIntervalTicks", Comments.inTicks, Comments.transferIntervalTicks);
        public final ConfigInt itemTransferBatch =
                i(64, 1, 512, "itemTransferBatch", Comments.itemTransferBatch);
        public final ConfigInt fluidTransferBatchMb =
                i(4000, 100, 64000, "fluidTransferBatchMb", Comments.inMillibuckets, Comments.fluidTransferBatchMb);

        @Override
        public String getName() {
            return "skyDock";
        }
    }

    public static final class BalloonsConfig extends ConfigBase {
        public final ConfigInt campfireLiftPerBalloon =
                i(4, 1, 64, "campfireLiftPerBalloon", Comments.campfireLiftPerBalloon);
        public final ConfigInt campfireMaxBalloons =
                i(16, 1, 256, "campfireMaxBalloons", Comments.campfireMaxBalloons);
        public final ConfigInt superHeaterLiftPerBalloon =
                i(6, 1, 128, "superHeaterLiftPerBalloon", Comments.superHeaterLiftPerBalloon);
        public final ConfigInt superHeaterMaxBalloons =
                i(32, 1, 512, "superHeaterMaxBalloons", Comments.superHeaterMaxBalloons);

        @Override
        public String getName() {
            return "balloons";
        }
    }

    private static final class Comments {
        static final String inTicks = "[in Ticks]";
        static final String inMillibuckets = "[in Millibuckets]";

        static final String skyDock = "Sky Dock item and fluid transfer rates.";
        static final String transferIntervalTicks = "Ticks between each item/fluid transfer cycle while an airship is locked to a Sky Dock.";
        static final String itemTransferBatch = "Maximum items transferred per cycle between a Sky Dock and a docked airship.";
        static final String fluidTransferBatchMb = "Maximum fluid (mB) transferred per cycle between a Sky Dock and a docked airship.";

        static final String balloons = "Balloon lift values and heater capacities.";
        static final String campfireLiftPerBalloon = "Lift provided by each Canvas Balloon heated by a lit Campfire or Soul Campfire.";
        static final String campfireMaxBalloons = "Maximum number of Canvas Balloons a single lit Campfire can heat.";
        static final String superHeaterLiftPerBalloon = "Lift provided by each Canvas Balloon heated by an Airship Super Heater.";
        static final String superHeaterMaxBalloons = "Maximum number of Canvas Balloons a single Airship Super Heater can heat.";
    }
}
