package com.createairlogistics.content.contraption.balloon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.AllSpriteShifts;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Hava Gemisi Süper Isıtıcısı Görsel Çizicisi (Airship Super Heater Renderer):
 *
 * Bariyer kapağı açıkken (OPEN=true) iç haznede Create'in yerel Blaze modellerini çizer:
 * - Dünya Modu (Idle): Gözleri açık Blaze (BLAZE_IDLE) oyuncuyu izler, çubukları alçakta sakin salınır.
 * - Contraption Modu (Active): Gözleri kapalı odaklanmış Blaze (BLAZE_ACTIVE), çubukları/elleri
 *   tavana doğru kalkmış halde ve çevresinde akan sarı Create alev halkalarıyla (BLAZE_BURNER_FLAME) yanar.
 */
public class AirshipSuperHeaterRenderer extends SafeBlockEntityRenderer<AirshipSuperHeaterBlockEntity> {

    public AirshipSuperHeaterRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(
            AirshipSuperHeaterBlockEntity be,
            float partialTicks,
            PoseStack ms,
            MultiBufferSource buffer,
            int light,
            int overlay
    ) {
        if (be.getLevel() instanceof VirtualRenderWorld) {
            return;
        }
        BlockState state = be.getBlockState();
        if (!state.getOptionalValue(AirshipSuperHeaterBlock.OPEN).orElse(false)) {
            return;
        }
        float horizontalAngle = AngleHelper.rad(be.getHeadAngle(partialTicks));
        renderBlazeChamber(state, be.getLevel(), horizontalAngle, false, System.identityHashCode(be), ms, buffer);
    }

    public static void renderInContraption(
            BlockState state,
            Level level,
            BlockPos localPos,
            PoseStack ms,
            MultiBufferSource buffer
    ) {
        if (!state.getOptionalValue(AirshipSuperHeaterBlock.OPEN).orElse(false)) {
            return;
        }
        Direction facing = state.getOptionalValue(AirshipSuperHeaterBlock.FACING).orElse(Direction.NORTH);
        float horizontalAngle = AngleHelper.rad((AngleHelper.horizontalAngle(facing) + 180f) % 360f);
        renderBlazeChamber(state, level, horizontalAngle, true, localPos.hashCode(), ms, buffer);
    }

    public static void renderBlazeChamber(
            BlockState state,
            @Nullable Level level,
            float horizontalAngle,
            boolean activeContraptionMode,
            int hashCode,
            PoseStack ms,
            MultiBufferSource buffer
    ) {
        float time = level != null ? AnimationTickHolder.getRenderTime(level) : AnimationTickHolder.getRenderTime();
        float renderTick = time + (int) (Math.abs((long) hashCode) % 13L) * 16f;
        float offset = Mth.sin((float) ((renderTick / 16f) % (2 * Math.PI))) / 64f;
        float offset1 = Mth.sin((float) ((renderTick / 16f + Math.PI) % (2 * Math.PI))) / 64f;
        float offset2 = Mth.sin((float) ((renderTick / 16f + Math.PI / 2) % (2 * Math.PI))) / 64f;

        VertexConsumer solid = buffer.getBuffer(RenderType.solid());

        // A. Kafa (AllPartialModels.BLAZE_ACTIVE [contraption, gözler kapalı] vs AllPartialModels.BLAZE_IDLE [dünya, gözler açık])
        PartialModel blazeModel = activeContraptionMode ? AllPartialModels.BLAZE_ACTIVE : AllPartialModels.BLAZE_IDLE;
        float headY = (activeContraptionMode ? -2.30f : -2.15f) / 16f + offset;
        CachedBuffers.partial(blazeModel, state)
                .translate(0, headY, 0)
                .rotateCentered(horizontalAngle, Direction.UP)
                .light(LightTexture.FULL_BRIGHT)
                .renderInto(ms, solid);

        // B. Çubuklar / Eller (AllPartialModels.BLAZE_BURNER_RODS ve AllPartialModels.BLAZE_BURNER_RODS_2)
        float smallRodsBaseY = (activeContraptionMode ? 7.5f : 4.0f) / 16f + offset1;
        ms.pushPose();
        ms.translate(0.5f, smallRodsBaseY, 0.5f);
        ms.scale(0.86f, 1.0f, 0.86f);
        ms.translate(-0.5f, -4.5f / 16f, -0.5f);
        CachedBuffers.partial(AllPartialModels.BLAZE_BURNER_RODS, state)
                .light(LightTexture.FULL_BRIGHT)
                .renderInto(ms, solid);
        ms.popPose();

        float largeRodsBaseY = (activeContraptionMode ? 6.1f : 3.8f) / 16f + offset2;
        ms.pushPose();
        ms.translate(0.5f, largeRodsBaseY, 0.5f);
        ms.scale(0.86f, 0.70f, 0.86f);
        ms.translate(-0.5f, -0.5f, -0.5f);
        CachedBuffers.partial(AllPartialModels.BLAZE_BURNER_RODS_2, state)
                .light(LightTexture.FULL_BRIGHT)
                .renderInto(ms, solid);
        ms.popPose();

        // C. Contraption Modu Sarı Alev Halkaları (AllPartialModels.BLAZE_BURNER_FLAME + AllSpriteShifts.BURNER_FLAME)
        if (activeContraptionMode) {
            SpriteShiftEntry spriteShift = AllSpriteShifts.BURNER_FLAME;
            float spriteWidth = spriteShift.getTarget().getU1() - spriteShift.getTarget().getU0();
            float spriteHeight = spriteShift.getTarget().getV1() - spriteShift.getTarget().getV0();
            float speed = 1f / 32f + 1f / 64f * 2f;
            double vScroll = speed * time;
            vScroll = vScroll - Math.floor(vScroll);
            vScroll = vScroll * spriteHeight / 2.0;
            double uScroll = speed * time / 2f;
            uScroll = uScroll - Math.floor(uScroll);
            uScroll = uScroll * spriteWidth / 2.0;

            ms.pushPose();
            ms.translate(0.5f, 3.6f / 16f, 0.5f);
            ms.scale(0.90f, 8.4f / 12.0f, 0.90f);
            ms.translate(-0.5f, -4.0f / 16f, -0.5f);
            CachedBuffers.partial(AllPartialModels.BLAZE_BURNER_FLAME, state)
                    .shiftUVScrolling(spriteShift, (float) uScroll, (float) vScroll)
                    .rotateCentered(horizontalAngle, Direction.UP)
                    .light(LightTexture.FULL_BRIGHT)
                    .renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));
            ms.popPose();
        }
    }
}
