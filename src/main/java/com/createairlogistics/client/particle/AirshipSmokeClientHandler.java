package com.createairlogistics.client.particle;

import com.createairlogistics.client.particle.AirshipDomeSmokeParticle.SmokeProfile;
import com.createairlogistics.config.ModClientConfig;
import com.createairlogistics.content.contraption.AirshipEntity;
import com.createairlogistics.content.contraption.balloon.AirshipSuperHeaterBlock;
import com.createairlogistics.registry.ModParticles;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Yalnızca istemci (Dist.CLIENT) tarafında yüklenen duman parçacık kaydı ve
 * hava gemisi blok animasyon yöneticisi. Dedicated Server güvenliğini korur.
 */
public final class AirshipSmokeClientHandler {

    private static final double MAX_ANIMATION_DISTANCE_SQR = 64.0 * 64.0;

    @Nullable
    private static volatile SpriteSet cachedSmokeSprites;

    private AirshipSmokeClientHandler() {
    }

    public static void registerModBusListeners(IEventBus modEventBus) {
        modEventBus.addListener(AirshipSmokeClientHandler::onRegisterParticleProviders);
    }

    private static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.AIRSHIP_DOME_SMOKE.get(), sprites -> {
            cachedSmokeSprites = sprites;
            return new AirshipDomeSmokeParticle.FallbackProvider(sprites);
        });
    }

    public static void tickAirshipBlockAnimations(AirshipEntity entity) {
        Contraption contraption = entity.getContraption();
        if (contraption == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel clientLevel = mc.level;
        if (clientLevel == null || mc.isPaused()) {
            return;
        }

        Player clientPlayer = mc.player;
        if (clientPlayer != null && clientPlayer.distanceToSqr(entity) > MAX_ANIMATION_DISTANCE_SQR) {
            return;
        }

        RandomSource random = clientLevel.random;
        SpriteSet smokeSprites = cachedSmokeSprites;

        for (StructureTemplate.StructureBlockInfo info : contraption.getBlocks().values()) {
            BlockPos localPos = info.pos();
            BlockState state = info.state();

            boolean isLitCampfire = state.getBlock() instanceof CampfireBlock
                    && state.hasProperty(CampfireBlock.LIT)
                    && state.getValue(CampfireBlock.LIT);

            if (isLitCampfire) {
                boolean signalFire = state.hasProperty(CampfireBlock.SIGNAL_FIRE)
                        && state.getValue(CampfireBlock.SIGNAL_FIRE);
                float spawnChance = signalFire ? 0.20f : 0.14f;

                if (smokeSprites != null && random.nextFloat() < spawnChance && ModClientConfig.shouldSpawnFlightParticle(random)) {
                    double lx = localPos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.4;
                    double ly = localPos.getY() + 0.45;
                    double lz = localPos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.4;

                    if (!AirshipDomeSmokeParticle.isSmokeBlocked(clientLevel, contraption, lx, ly, lz)) {
                        SmokeProfile profile = signalFire
                                ? SmokeProfile.SIGNAL_CAMPFIRE
                                : SmokeProfile.COSY_CAMPFIRE;
                        mc.particleEngine.add(new AirshipDomeSmokeParticle(
                                clientLevel, entity, localPos, lx, ly, lz, profile, smokeSprites
                        ));
                    }
                }

                // Kamp ateşi çıtırtı sesi ve küçük kıvılcımlar (yüksek duman üretmez)
                if (random.nextInt(5) == 0) {
                    Vec3 globalCenter = entity.toGlobalVector(Vec3.atCenterOf(localPos), 1.0f);
                    state.getBlock().animateTick(state, clientLevel, BlockPos.containing(globalCenter), random);
                }
                continue;
            }

            // Hava Gemisi Süper Isıtıcısı: yoğun kubbe dumanı, baca alevleri ve kor kıvılcımları üretir
            if (state.getBlock() instanceof AirshipSuperHeaterBlock) {
                if (smokeSprites != null) {
                    if (random.nextFloat() < 0.72f && ModClientConfig.shouldSpawnSuperHeaterParticle(random)) {
                        double lx = localPos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.30;
                        double ly = localPos.getY() + 0.96;
                        double lz = localPos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.30;
                        if (!AirshipDomeSmokeParticle.isSmokeBlocked(clientLevel, contraption, lx, ly, lz)) {
                            mc.particleEngine.add(new AirshipDomeSmokeParticle(
                                    clientLevel, entity, localPos, lx, ly, lz, SmokeProfile.SUPER_HEATER, smokeSprites
                            ));
                        }
                    }
                    if (random.nextFloat() < 0.45f && ModClientConfig.shouldSpawnSuperHeaterParticle(random)) {
                        double lx = localPos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.26;
                        double ly = localPos.getY() + 0.96;
                        double lz = localPos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.26;
                        if (!AirshipDomeSmokeParticle.isSmokeBlocked(clientLevel, contraption, lx, ly, lz)) {
                            mc.particleEngine.add(new AirshipDomeSmokeParticle(
                                    clientLevel, entity, localPos, lx, ly, lz, SmokeProfile.SUPER_HEATER, smokeSprites
                            ));
                        }
                    }
                    if (random.nextFloat() < 0.22f && ModClientConfig.shouldSpawnSuperHeaterParticle(random)) {
                        double lx = localPos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.20;
                        double ly = localPos.getY() + 0.96;
                        double lz = localPos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.20;
                        if (!AirshipDomeSmokeParticle.isSmokeBlocked(clientLevel, contraption, lx, ly, lz)) {
                            mc.particleEngine.add(new AirshipDomeSmokeParticle(
                                    clientLevel, entity, localPos, lx, ly, lz, SmokeProfile.SUPER_HEATER, smokeSprites
                            ));
                        }
                    }
                }

                Vec3 shipVel = entity.getDeltaMovement();
                if (random.nextFloat() < 0.22f && ModClientConfig.shouldSpawnSuperHeaterParticle(random)) {
                    Vec3 localFlame = new Vec3(
                            localPos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.30,
                            localPos.getY() + 0.95,
                            localPos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.30
                    );
                    Vec3 worldFlame = entity.toGlobalVector(localFlame, 1.0f);
                    clientLevel.addParticle(
                            ParticleTypes.SMALL_FLAME,
                            worldFlame.x, worldFlame.y, worldFlame.z,
                            shipVel.x * 0.75 + (random.nextDouble() - 0.5) * 0.01,
                            shipVel.y * 0.75 + 0.022 + random.nextDouble() * 0.02,
                            shipVel.z * 0.75 + (random.nextDouble() - 0.5) * 0.01
                    );
                }

                if (random.nextFloat() < 0.12f && ModClientConfig.shouldSpawnSuperHeaterParticle(random)) {
                    Vec3 localFlame = new Vec3(
                            localPos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.26,
                            localPos.getY() + 0.95,
                            localPos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.26
                    );
                    Vec3 worldFlame = entity.toGlobalVector(localFlame, 1.0f);
                    clientLevel.addParticle(
                            ParticleTypes.FLAME,
                            worldFlame.x, worldFlame.y, worldFlame.z,
                            shipVel.x * 0.75 + (random.nextDouble() - 0.5) * 0.008,
                            shipVel.y * 0.75 + 0.02 + random.nextDouble() * 0.018,
                            shipVel.z * 0.75 + (random.nextDouble() - 0.5) * 0.008
                    );
                }

                if (random.nextFloat() < 0.06f && ModClientConfig.shouldSpawnSuperHeaterParticle(random)) {
                    Vec3 localSpark = new Vec3(
                            localPos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.24,
                            localPos.getY() + 0.95,
                            localPos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.24
                    );
                    Vec3 worldSpark = entity.toGlobalVector(localSpark, 1.0f);
                    clientLevel.addParticle(
                            ParticleTypes.LAVA,
                            worldSpark.x, worldSpark.y, worldSpark.z,
                            0.0, 0.02, 0.0
                    );
                }

                if (random.nextInt(24) == 0) {
                    Vec3 globalCenter = entity.toGlobalVector(Vec3.atCenterOf(localPos), 1.0f);
                    clientLevel.playLocalSound(
                            globalCenter.x, globalCenter.y, globalCenter.z,
                            SoundEvents.BLASTFURNACE_FIRE_CRACKLE,
                            SoundSource.BLOCKS,
                            0.45f + random.nextFloat() * 0.2f,
                            0.85f + random.nextFloat() * 0.3f,
                            false
                    );
                }
                continue;
            }

            if (random.nextInt(24) == 0) {
                Vec3 globalCenter = entity.toGlobalVector(Vec3.atCenterOf(localPos), 1.0f);
                state.getBlock().animateTick(state, clientLevel, BlockPos.containing(globalCenter), random);
            }
        }
    }
}
