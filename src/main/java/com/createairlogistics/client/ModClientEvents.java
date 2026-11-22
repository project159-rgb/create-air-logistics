package com.createairlogistics.client;

import com.createairlogistics.client.particle.AirshipSmokeClientHandler;
import com.createairlogistics.content.navigation.SkyRouteClientRenderer;
import com.createairlogistics.ponder.ModPonderPlugin;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class ModClientEvents {

    private ModClientEvents() {
    }

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        ModPartialModels.init();
        ModClientConfigScreen.register(modContainer);
        AirshipSmokeClientHandler.registerModBusListeners(modEventBus);
        modEventBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(ModPonderPlugin::register));
        NeoForge.EVENT_BUS.addListener(SkyRouteClientRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> SkyRouteClientRenderer.clearCache());
    }
}
