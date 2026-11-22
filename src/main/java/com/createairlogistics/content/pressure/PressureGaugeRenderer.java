package com.createairlogistics.content.pressure;

import com.createairlogistics.client.ModPartialModels;
import com.createairlogistics.content.contraption.propulsion.PressureEngineBlock;
import com.createairlogistics.content.contraption.propulsion.PressureEngineBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Basınç Tankı ve Basınç Motoru üzerindeki manometre ibrelerini (0% Mavi → 100% Kırmızı)
 * hem dünyada blok halindeyken hem de Contraption (AirshipEntity) uçuşundayken canlı çizer.
 */
public final class PressureGaugeRenderer {

    /** Boş (0 Hava) iken ibre açısı (tam sol alt başlangıç durağı / mavi bölge dibi). */
    private static final float EMPTY_ANGLE = -76.0f;
    /** Dolu (Maks Hava) iken ibre açısı (tam sağ alt son durak / kırmızı bölge dibi). */
    private static final float FULL_ANGLE = 76.0f;

    private PressureGaugeRenderer() {
    }

    public static float ratioToAngle(float airRatio) {
        return Mth.lerp(Mth.clamp(airRatio, 0.0f, 1.0f), EMPTY_ANGLE, FULL_ANGLE);
    }

    public static void renderTankNeedles(
            BlockState state,
            float airRatio,
            PoseStack ms,
            MultiBufferSource buffer,
            int light,
            int overlay
    ) {
        if (!state.hasProperty(PressureTankBlock.AXIS)) {
            return;
        }
        Direction.Axis axis = state.getValue(PressureTankBlock.AXIS);
        float angle = ratioToAngle(airRatio);
        VertexConsumer consumer = buffer.getBuffer(RenderType.solid());

        ms.pushPose();
        ms.translate(0.5f, 0.5f, 0.5f);
        if (axis == Direction.Axis.Z) {
            ms.mulPose(Axis.XP.rotationDegrees(-90.0f));
        } else if (axis == Direction.Axis.X) {
            ms.mulPose(Axis.YP.rotationDegrees(-90.0f));
            ms.mulPose(Axis.XP.rotationDegrees(-90.0f));
        }
        ms.translate(-0.5f, -0.5f, -0.5f);

        // Kuzey ve Güney kadran ibreleri
        renderSingleNeedle(state, 8.0f, 6.85f, 1.2f, Direction.NORTH, angle, ms, consumer, light, overlay);
        renderSingleNeedle(state, 8.0f, 6.85f, 14.8f, Direction.SOUTH, angle, ms, consumer, light, overlay);

        ms.popPose();
    }

    public static void renderEngineNeedles(
            BlockState state,
            float airRatio,
            PoseStack ms,
            MultiBufferSource buffer,
            int light,
            int overlay
    ) {
        if (!state.hasProperty(PressureEngineBlock.FACING)) {
            return;
        }
        Direction facing = state.getValue(PressureEngineBlock.FACING);
        int roll = state.hasProperty(PressureEngineBlock.ROLL) ? state.getValue(PressureEngineBlock.ROLL) : 0;
        float angle = ratioToAngle(airRatio);
        VertexConsumer consumer = buffer.getBuffer(RenderType.solid());

        ms.pushPose();
        ms.translate(0.5f, 0.5f, 0.5f);
        ModPartialModels.rotateNorthTo(ms, facing);
        if (roll != 0) {
            ms.mulPose(Axis.ZP.rotationDegrees(roll * 90.0f));
        }
        ms.translate(-0.5f, -0.5f, -0.5f);

        // Doğu ve Batı kadran ibreleri
        renderSingleNeedle(state, 14.3f, 7.85f, 9.0f, Direction.EAST, angle, ms, consumer, light, overlay);
        renderSingleNeedle(state, 1.7f, 7.85f, 9.0f, Direction.WEST, angle, ms, consumer, light, overlay);

        ms.popPose();
    }

    private static void renderSingleNeedle(
            BlockState referenceState,
            float px,
            float py,
            float pz,
            Direction gaugeFacing,
            float needleAngle,
            PoseStack ms,
            VertexConsumer consumer,
            int light,
            int overlay
    ) {
        ms.pushPose();
        ms.translate(px / 16.0f, py / 16.0f, pz / 16.0f);
        ModPartialModels.rotateNorthTo(ms, gaugeFacing);
        ms.mulPose(Axis.ZP.rotationDegrees(needleAngle));
        ms.translate(-0.5f, -0.5f, -0.5f);
        ModPartialModels.render(ModPartialModels.GAUGE_NEEDLE, referenceState, ms, consumer,
                RenderType.solid(), light, overlay);
        ms.popPose();
    }

    public static class Tank extends SafeBlockEntityRenderer<PressureTankBlockEntity> {
        public Tank(BlockEntityRendererProvider.Context context) {
        }

        @Override
        protected void renderSafe(PressureTankBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
            if (be.getLevel() instanceof VirtualRenderWorld) {
                return;
            }
            renderTankNeedles(be.getBlockState(), be.getVisualAirRatio(partialTicks), ms, buffer, light, overlay);
        }
    }

    public static class Engine extends SafeBlockEntityRenderer<PressureEngineBlockEntity> {
        public Engine(BlockEntityRendererProvider.Context context) {
        }

        @Override
        protected void renderSafe(PressureEngineBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
            if (be.getLevel() instanceof VirtualRenderWorld) {
                return;
            }
            renderEngineNeedles(be.getBlockState(), be.getVisualAirRatio(partialTicks), ms, buffer, light, overlay);
        }
    }
}
