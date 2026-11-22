package com.createairlogistics.content.dock;

import com.createairlogistics.content.navigation.SkyNetworkSavedData;
import com.createairlogistics.content.navigation.TravellingAirship;
import com.createairlogistics.content.pressure.AirPressure;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Sky Dock için Create Display Link kaynağı (Display Board, Nixie Tube ve tabelalarla uyumlu).
 */
public class SkyDockDisplaySource extends DisplaySource {

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        if (!(context.getSourceBlockEntity() instanceof SkyDockBlockEntity be)) {
            return EMPTY;
        }

        int dockAirPct = Math.round(100f * be.getAir() / Math.max(1, AirPressure.dockCapacity()));
        TravellingAirship mooredShip = null;
        if (context.level() instanceof ServerLevel serverLevel) {
            mooredShip = SkyNetworkSavedData.get(serverLevel).getDockedAirshipAt(be.getBlockPos()).orElse(null);
        }

        int shipAirPct = mooredShip != null
                ? Math.round(100f * (float) mooredShip.getAir() / Math.max(1, mooredShip.getAirCapacity()))
                : 0;

        if (stats.maxRows() <= 1) {
            if (mooredShip != null) {
                return List.of(Component.translatable("create_air_logistics.display_source.moored", mooredShip.getName())
                        .append(" (" + shipAirPct + "%)"));
            }
            return List.of(Component.translatable("create_air_logistics.display_source.dock_air_only", dockAirPct));
        }

        List<MutableComponent> lines = new ArrayList<>(4);
        lines.add(be.hasCustomName()
                ? Component.literal(be.getCustomName())
                : Component.translatable("create_air_logistics.chart.default_dock_name", be.getBlockPos().toShortString()));
        lines.add(mooredShip != null
                ? Component.translatable("create_air_logistics.display_source.moored", mooredShip.getName())
                : Component.translatable("create_air_logistics.display_source.idle"));
        lines.add(mooredShip != null
                ? Component.translatable("create_air_logistics.display_source.air_line", dockAirPct, shipAirPct)
                : Component.translatable("create_air_logistics.display_source.dock_air_only", dockAirPct));
        lines.add(be.getRouteObstaclePos() != null
                ? Component.translatable("create_air_logistics.display_source.corridor_blocked")
                : Component.translatable("create_air_logistics.display_source.corridor_clear"));

        int limit = Math.min(stats.maxRows(), lines.size());
        return new ArrayList<>(lines.subList(0, limit));
    }

    @Override
    public Component getName() {
        return Component.translatable("create_air_logistics.display_source.sky_dock_status");
    }

    @Override
    public int getPassiveRefreshTicks() {
        return 20;
    }
}
