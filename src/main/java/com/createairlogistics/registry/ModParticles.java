package com.createairlogistics.registry;

import com.createairlogistics.CreateAirLogistics;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Create: Air Logistics - Özel Parçacık Türleri Kaydı.
 */
public final class ModParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, CreateAirLogistics.MOD_ID);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> AIRSHIP_DOME_SMOKE =
            PARTICLE_TYPES.register("airship_dome_smoke", () -> new SimpleParticleType(false));

    private ModParticles() {
    }

    public static void register(IEventBus modEventBus) {
        PARTICLE_TYPES.register(modEventBus);
    }
}
