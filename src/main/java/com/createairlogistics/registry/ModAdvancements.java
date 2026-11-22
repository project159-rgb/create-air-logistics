package com.createairlogistics.registry;

import com.createairlogistics.CreateAirLogistics;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Kod içinden başarım (Advancement) verme yardımcısı.
 */
public final class ModAdvancements {

    private static final double NEARBY_RADIUS = 64.0;

    private ModAdvancements() {
    }

    public static void award(ServerPlayer player, String id) {
        if (player == null || player.server == null) {
            return;
        }
        AdvancementHolder holder = player.server.getAdvancements().get(CreateAirLogistics.asResource(id));
        if (holder == null) {
            return;
        }
        PlayerAdvancements advancements = player.getAdvancements();
        AdvancementProgress progress = advancements.getOrStartProgress(holder);
        if (progress.isDone()) {
            return;
        }
        List<String> remaining = new ArrayList<>();
        for (String criterion : progress.getRemainingCriteria()) {
            remaining.add(criterion);
        }
        for (String criterion : remaining) {
            advancements.award(holder, criterion);
        }
    }

    public static void awardNearby(ServerLevel level, BlockPos pos, String id) {
        if (level == null || pos == null) {
            return;
        }
        double maxDistSqr = NEARBY_RADIUS * NEARBY_RADIUS;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && pos.distToCenterSqr(player.position()) <= maxDistSqr) {
                award(player, id);
            }
        }
    }
}
