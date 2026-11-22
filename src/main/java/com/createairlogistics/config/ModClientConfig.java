package com.createairlogistics.config;

import net.createmod.catnip.config.ConfigBase;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Client-side configuration for Create: Air Logistics particle density and category toggles.
 * Safe to load on a dedicated server (contains no client-only imports).
 */
public final class ModClientConfig extends ConfigBase {

    private static final ModClientConfig CLIENT;

    static {
        Pair<ModClientConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(builder -> {
            ModClientConfig config = new ModClientConfig();
            config.registerAll(builder);
            return config;
        });
        CLIENT = pair.getLeft();
        CLIENT.specification = pair.getRight();
    }

    public final ConfigFloat totalParticleDensity = f(1.0f, 0.0f, 1.0f, "totalParticleDensity", Comments.totalParticleDensity);
    public final ConfigBool superHeaterParticles = b(true, "superHeaterParticles", Comments.superHeaterParticles);
    public final ConfigBool airshipFlightParticles = b(true, "airshipFlightParticles", Comments.airshipFlightParticles);
    public final ConfigBool skyDockParticles = b(true, "skyDockParticles", Comments.skyDockParticles);

    private ModClientConfig() {
    }

    @Override
    public String getName() {
        return "client";
    }

    public static void register(ModContainer modContainer, IEventBus modEventBus) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, CLIENT.specification);
        modEventBus.addListener((ModConfigEvent.Loading event) -> {
            if (event.getConfig().getSpec() == CLIENT.specification) {
                CLIENT.onLoad();
            }
        });
        modEventBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == CLIENT.specification) {
                CLIENT.onReload();
            }
        });
    }

    public static ModConfigSpec clientSpec() {
        return CLIENT.specification;
    }

    private static boolean isLoaded() {
        return CLIENT.specification != null && CLIENT.specification.isLoaded();
    }

    public static float getParticleDensity() {
        if (!isLoaded()) {
            return 1.0f;
        }
        return Mth.clamp(CLIENT.totalParticleDensity.getF(), 0.0f, 1.0f);
    }

    public static boolean shouldSpawn(boolean categoryEnabled, RandomSource random) {
        if (!categoryEnabled) {
            return false;
        }
        float density = getParticleDensity();
        if (density <= 0.0f) {
            return false;
        }
        return density >= 1.0f || random.nextFloat() < density;
    }

    public static boolean shouldSpawnSuperHeaterParticle(RandomSource random) {
        boolean enabled = !isLoaded() || CLIENT.superHeaterParticles.get();
        return shouldSpawn(enabled, random);
    }

    public static boolean shouldSpawnFlightParticle(RandomSource random) {
        boolean enabled = !isLoaded() || CLIENT.airshipFlightParticles.get();
        return shouldSpawn(enabled, random);
    }

    public static boolean shouldSpawnSkyDockParticle(RandomSource random) {
        boolean enabled = !isLoaded() || CLIENT.skyDockParticles.get();
        return shouldSpawn(enabled, random);
    }

    private static final class Comments {
        static final String totalParticleDensity =
                "Global multiplier (0.0 - 1.0) for Create: Air Logistics client particles. 0 disables all mod particles; 1 spawns 100%.";
        static final String superHeaterParticles =
                "Enable smoke, flame, and ember particles from Airship Super Heaters (placed and mounted on airships).";
        static final String airshipFlightParticles =
                "Enable contraption campfire dome smoke and airship takeoff steam cloud particles.";
        static final String skyDockParticles =
                "Enable inward air intake particles while a Sky Dock compressor is filling its air tank.";
    }
}
