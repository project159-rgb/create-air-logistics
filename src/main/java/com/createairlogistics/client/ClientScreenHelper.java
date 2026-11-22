package com.createairlogistics.client;

import com.createairlogistics.content.dock.SkyDockScreen;
import com.createairlogistics.content.navigation.FlightChartScreen;
import net.minecraft.core.BlockPos;

public final class ClientScreenHelper {
    private ClientScreenHelper() {}

    public static void openSkyDockScreen(BlockPos pos) {
        SkyDockScreen.open(pos);
    }

    public static void openFlightChartScreen() {
        FlightChartScreen.open();
    }
}
