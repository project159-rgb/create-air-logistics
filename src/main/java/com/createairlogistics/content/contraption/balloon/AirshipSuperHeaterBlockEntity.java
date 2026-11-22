package com.createairlogistics.content.contraption.balloon;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Hava Gemisi Süper Isıtıcısı BlockEntity (Airship Super Heater BlockEntity):
 * - Dünya modunda bariyer kapağı açıkken (OPEN=true) içerideki Blaze kafasının oyuncuyu
 *   Create Blaze Burner ile birebir aynı yumuşak açısal interpolasyonla takip etmesini sağlar.
 * - Dedicated Server güvenliği için hiçbir istemci (net.minecraft.client.*) sınıfına doğrudan bağlanmaz.
 */
public class AirshipSuperHeaterBlockEntity extends SmartBlockEntity {

    private static final LerpedFloat.Chaser HEAD_CHASER = LerpedFloat.Chaser.exp(5);

    private final LerpedFloat headAngle = LerpedFloat.angular();
    private boolean angleInitialized = false;

    public AirshipSuperHeaterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        Direction initialFacing = state.getOptionalValue(AirshipSuperHeaterBlock.FACING).orElse(Direction.SOUTH);
        float defaultAngle = (AngleHelper.horizontalAngle(initialFacing) + 180f) % 360f;
        headAngle.startWithValue(defaultAngle);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public void tick() {
        super.tick();
        if (level != null && level.isClientSide()) {
            BlockState state = getBlockState();
            Direction facing = state.getOptionalValue(AirshipSuperHeaterBlock.FACING).orElse(Direction.SOUTH);
            float defaultAngle = (AngleHelper.horizontalAngle(facing) + 180f) % 360f;

            if (!angleInitialized) {
                headAngle.startWithValue(defaultAngle);
                angleInitialized = true;
            }

            if (state.getOptionalValue(AirshipSuperHeaterBlock.OPEN).orElse(false)) {
                float target = AirshipSuperHeaterClientHelper.resolvePlayerTargetAngle(worldPosition, isVirtual(), defaultAngle);
                target = headAngle.getValue() + AngleHelper.getShortestAngleDiff(headAngle.getValue(), target);
                headAngle.chase(target, 0.25f, HEAD_CHASER);
                headAngle.tickChaser();
            } else {
                headAngle.startWithValue(defaultAngle);
            }
        }
    }

    public float getHeadAngle(float partialTicks) {
        if (!angleInitialized) {
            Direction facing = getBlockState().getOptionalValue(AirshipSuperHeaterBlock.FACING).orElse(Direction.SOUTH);
            return (AngleHelper.horizontalAngle(facing) + 180f) % 360f;
        }
        return headAngle.getValue(partialTicks);
    }
}
