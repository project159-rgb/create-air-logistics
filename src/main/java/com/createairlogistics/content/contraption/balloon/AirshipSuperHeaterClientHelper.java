package com.createairlogistics.content.contraption.balloon;

import net.createmod.catnip.math.AngleHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * Yalnızca istemci tarafında (level.isClientSide() dalından) çağrılan oyuncu bakış açısı yardımcısı.
 */
public final class AirshipSuperHeaterClientHelper {

    private AirshipSuperHeaterClientHelper() {
    }

    public static float resolvePlayerTargetAngle(BlockPos pos, boolean isVirtual, float fallbackAngle) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && !player.isInvisible()) {
            double px;
            double pz;
            if (isVirtual) {
                px = -4.0;
                pz = -10.0;
            } else {
                px = player.getX();
                pz = player.getZ();
            }
            double dx = px - (pos.getX() + 0.5);
            double dz = pz - (pos.getZ() + 0.5);
            return AngleHelper.deg(-Mth.atan2(dz, dx)) - 90f;
        }
        return fallbackAngle;
    }
}
