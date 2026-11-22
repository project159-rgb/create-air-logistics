package com.createairlogistics.content.contraption;

import com.createairlogistics.content.contraption.balloon.AirshipSuperHeaterBlock;
import com.createairlogistics.content.contraption.balloon.AirshipSuperHeaterRenderer;
import com.createairlogistics.content.contraption.propulsion.BoostPropellerBlock;
import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerBlock;
import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerRenderer;
import com.createairlogistics.content.contraption.propulsion.PressureEngineBlock;
import com.createairlogistics.content.pressure.AirPressure;
import com.createairlogistics.content.pressure.PressureGaugeRenderer;
import com.createairlogistics.content.pressure.PressureTankBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.api.contraption.storage.SyncedMountedStorage;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.render.ClientContraption;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

/**
 * 3B Uçan Gemi Görsel Çizicisi (Create Native ContraptionEntityRenderer)
 *
 * Geminin tüm bloklarını, yatak/sandık/kasa/fan/vb. hareketli parçalarını Create'in kendi
 * VirtualRenderWorld ve Flywheel ContraptionVisual motoruyla çizer; ek olarak Yön Bulucu Pervane'nin
 * (Pathfinder Propeller) 4 kanatlı rotorunu ve ±25° Yaw / ±20° Pitch Gimbal göbeğini çizer.
 */
public class AirshipEntityRenderer extends ContraptionEntityRenderer<AirshipEntity> {

    public AirshipEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(AirshipEntity entity, Frustum camera, double camX, double camY, double camZ) {
        if (entity.distanceToSqr(camX, camY, camZ) >= 16384.0) { // 128 blok görüş mesafesi
            return false;
        }
        return super.shouldRender(entity, camera, camX, camY, camZ);
    }

    @Override
    public void render(AirshipEntity entity, float entityYaw, float partialTicks, PoseStack ms, MultiBufferSource buffer, int packedLight) {
        Contraption rawContraption = entity.getContraption();
        if (rawContraption instanceof AirshipContraption preSyncContraption) {
            syncClientMountedStorageIfNeeded(preSyncContraption);
        }

        super.render(entity, entityYaw, partialTicks, ms, buffer, packedLight);

        if (!(rawContraption instanceof AirshipContraption airshipContraption)) {
            return;
        }

        float steerYaw = entity.getVisualSteerYaw(partialTicks);
        float steerPitch = entity.getVisualSteerPitch(partialTicks);
        float bladeAngle = entity.getBladeAngle(partialTicks);
        float airRatio = entity.getVisualAirRatio(partialTicks);

        for (StructureTemplate.StructureBlockInfo info : airshipContraption.getRenderableSpecialBlocks()) {
            boolean isPathfinder = info.state().getBlock() instanceof PathfinderPropellerBlock;
            boolean isBoost = info.state().getBlock() instanceof BoostPropellerBlock;
            boolean isTank = info.state().getBlock() instanceof PressureTankBlock;
            boolean isEngine = info.state().getBlock() instanceof PressureEngineBlock;
            boolean isSuperHeater = info.state().getBlock() instanceof AirshipSuperHeaterBlock;
            if (!isPathfinder && !isBoost && !isTank && !isEngine && !isSuperHeater) {
                continue;
            }

            BlockPos localPos = info.pos();
            Vec3 globalBlockVec = entity.toGlobalVector(Vec3.atCenterOf(localPos), partialTicks);
            int worldLight = LevelRenderer.getLightColor(entity.level(), BlockPos.containing(globalBlockVec));

            ms.pushPose();
            entity.applyLocalTransforms(ms, partialTicks);
            ms.translate(localPos.getX(), localPos.getY(), localPos.getZ());

            if (isPathfinder || isBoost) {
                if (info.state().hasProperty(PathfinderPropellerBlock.FACING)) {
                    Direction propFacing = info.state().getValue(PathfinderPropellerBlock.FACING);
                    boolean active = isPathfinder || airshipContraption.isBoostPropellerActive(localPos);
                    PathfinderPropellerRenderer.renderPropellerAssembly(
                            info.state(),
                            propFacing,
                            isPathfinder ? steerYaw : (active ? steerYaw * 0.5f : 0.0f),
                            isPathfinder ? steerPitch : (active ? steerPitch * 0.5f : 0.0f),
                            active ? bladeAngle : 0.0f,
                            ms,
                            buffer,
                            worldLight,
                            OverlayTexture.NO_OVERLAY
                    );
                }
            } else if (isTank) {
                PressureGaugeRenderer.renderTankNeedles(
                        info.state(),
                        airRatio,
                        ms,
                        buffer,
                        worldLight,
                        OverlayTexture.NO_OVERLAY
                );
            } else if (isEngine) {
                float engineRatio = airshipContraption.isEngineActive(localPos)
                        ? airRatio
                        : readStaticEngineAirRatio(info);
                PressureGaugeRenderer.renderEngineNeedles(
                        info.state(),
                        engineRatio,
                        ms,
                        buffer,
                        worldLight,
                        OverlayTexture.NO_OVERLAY
                );
            } else if (isSuperHeater) {
                AirshipSuperHeaterRenderer.renderInContraption(
                        info.state(),
                        entity.level(),
                        localPos,
                        ms,
                        buffer
                );
            }

            ms.popPose();
        }
    }

    private static void syncClientMountedStorageIfNeeded(AirshipContraption contraption) {
        ClientContraption clientContraption = contraption.getOrCreateClientContraptionLazy();
        int currentVersion = clientContraption.structureVersion();
        if (contraption.getSyncedClientStructureVersion() == currentVersion) {
            return;
        }
        contraption.setSyncedClientStructureVersion(currentVersion);

        try {
            for (var entry : contraption.getStorage().getAllItemStorages().entrySet()) {
                if (entry.getValue() instanceof SyncedMountedStorage synced) {
                    synced.afterSync(contraption, entry.getKey());
                }
            }
            for (var entry : contraption.getStorage().getFluids().storages.entrySet()) {
                BlockPos localPos = entry.getKey();
                if (entry.getValue() instanceof SyncedMountedStorage synced) {
                    synced.afterSync(contraption, localPos);
                    BlockEntity clientBe = contraption.getBlockEntityClientSide(localPos);
                    if (clientBe instanceof FluidTankBlockEntity tankBe) {
                        float fillState = tankBe.getFillState();
                        tankBe.setFluidLevel(LerpedFloat.linear().startWithValue(fillState));
                    }
                }
            }
        } catch (IllegalStateException ignored) {
            // MountedStorageManager henüz başlatılmadı; sonraki karede tekrar dene
            contraption.setSyncedClientStructureVersion(-1);
        }
    }

    private static float readStaticEngineAirRatio(StructureTemplate.StructureBlockInfo info) {
        if (info.nbt() == null || AirPressure.engineCapacity() <= 0) {
            return 0.0f;
        }
        return Mth.clamp(info.nbt().getInt("Air") / (float) AirPressure.engineCapacity(), 0.0f, 1.0f);
    }
}
