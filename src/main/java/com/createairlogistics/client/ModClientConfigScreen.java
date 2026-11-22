package com.createairlogistics.client;

import com.createairlogistics.CreateAirLogistics;
import com.createairlogistics.config.ModClientConfig;
import com.createairlogistics.config.ModServerConfig;
import net.createmod.catnip.config.ui.BaseConfigScreen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only registration for the Create Goggles / NeoForge Mods configuration screen.
 */
public final class ModClientConfigScreen {

    private ModClientConfigScreen() {
    }

    public static void register(ModContainer modContainer) {
        BaseConfigScreen.setDefaultActionFor(CreateAirLogistics.MOD_ID,
                screen -> screen.withSpecs(ModClientConfig.clientSpec(), null, ModServerConfig.serverSpec()));
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, parent) -> new BaseConfigScreen(parent, CreateAirLogistics.MOD_ID));
    }
}
