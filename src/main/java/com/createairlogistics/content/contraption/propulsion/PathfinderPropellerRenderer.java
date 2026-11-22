package com.createairlogistics.content.contraption.propulsion;

import com.createairlogistics.client.ModPartialModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Yön Bulucu Pervane Görsel Renderer (Pathfinder Propeller Renderer)
 *
 * Create'in kendi BER yaklaşımıyla (PartialModel) çalışır:
 * - Gövde (brass/andesite nacelle) statik blok modelidir.
 * - 3x3 rotor ({@link ModPartialModels#PROPELLER_ROTOR}) bloğun ön yüzündeki gimbal noktası etrafında
 *   rotanın dönüş (Yaw ±25°) ve irtifa (Pitch ±20°) açısına göre eğilir ve kendi ekseninde döner.
 *   Rotor düzlemi, montaj doğrulayıcısının boş olmasını şart koştuğu "1 blok önündeki 3x3 hücre" ile birebir örtüşür.
 */
public class PathfinderPropellerRenderer extends SafeBlockEntityRenderer<PathfinderPropellerBlockEntity> {

    public PathfinderPropellerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(PathfinderPropellerBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        if (be.getLevel() instanceof VirtualRenderWorld) {
            return;
        }
        BlockState state = be.getBlockState();
        Direction facing = state.getValue(PathfinderPropellerBlock.FACING);

        // Dünyada (gemi kurulmadan önce) rotor durur: dönüş gücü yalnızca uçuşta Basınç Motorundan gelir.
        renderPropellerAssembly(state, facing, be.getVisualSteerYaw(), be.getVisualSteerPitch(),
                0.0f, ms, buffer, light, overlay);
    }

    @Override
    public boolean shouldRenderOffScreen(PathfinderPropellerBlockEntity be) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(PathfinderPropellerBlockEntity be) {
        // Rotor, bloğun önündeki 3x3 alanı tarar
        return new AABB(be.getBlockPos()).inflate(2.0);
    }

    public static void renderPropellerAssembly(
            BlockState referenceState,
            Direction facing,
            float steerYaw,
            float steerPitch,
            float bladeAngle,
            PoseStack ms,
            MultiBufferSource buffer,
            int light,
            int overlay
    ) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.cutout());

        ms.pushPose();
        ms.translate(0.5f, 0.5f, 0.5f);
        ModPartialModels.rotateNorthTo(ms, facing);

        // Gimbal pivotu: gövdenin ön yüz merkezi (kuzeye bakan model uzayında z = 0)
        // NOT: Pervane her zaman geminin ARKASINA bakar (shipForward = propFacing.getOpposite()).
        // Model uzayında +XP "ön ucu yukarı", +YP "ön ucu sola (saat yönü tersi)" çevirir; ön uç geriyi
        // gösterdiği için rotanın tırmanış/dönüş yönünü izlemesi adına iki açı da ters işaretle uygulanır.
        ms.translate(0.0f, 0.0f, -0.5f);
        ms.mulPose(Axis.YP.rotationDegrees(-steerYaw));
        ms.mulPose(Axis.XP.rotationDegrees(-steerPitch));
        ms.mulPose(Axis.ZP.rotationDegrees(bladeAngle));
        ms.translate(-0.5f, -0.5f, 0.0f);

        PartialModel rotor = (referenceState.getBlock() instanceof BoostPropellerBlock)
                ? ModPartialModels.BOOST_PROPELLER_ROTOR
                : ModPartialModels.PROPELLER_ROTOR;
        ModPartialModels.render(rotor, referenceState, ms, consumer,
                RenderType.cutout(), light, overlay);

        ms.popPose();
    }
}
