package com.createairlogistics.content.contraption.propulsion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Takviye Pervanesi Görsel Renderer (Boost Propeller Renderer):
 * 3x3 kanatlı rotoru (ModPartialModels.BOOST_PROPELLER_ROTOR) bloğun önünde çizer.
 */
public class BoostPropellerRenderer extends SafeBlockEntityRenderer<BoostPropellerBlockEntity> {

    public BoostPropellerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(BoostPropellerBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        if (be.getLevel() instanceof VirtualRenderWorld) {
            return;
        }
        BlockState state = be.getBlockState();
        Direction facing = state.getValue(BoostPropellerBlock.FACING);
        PathfinderPropellerRenderer.renderPropellerAssembly(state, facing, 0.0f, 0.0f, 0.0f, ms, buffer, light, overlay);
    }

    @Override
    public boolean shouldRenderOffScreen(BoostPropellerBlockEntity be) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(BoostPropellerBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(2.0);
    }
}
