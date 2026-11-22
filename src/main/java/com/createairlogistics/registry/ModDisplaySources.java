package com.createairlogistics.registry;

import com.createairlogistics.CreateAirLogistics;
import com.createairlogistics.content.dock.SkyDockDisplaySource;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.tterrag.registrate.util.entry.RegistryEntry;

/**
 * Create Display Link veri kaynakları kaydı.
 */
public final class ModDisplaySources {

    public static final RegistryEntry<DisplaySource, SkyDockDisplaySource> SKY_DOCK_STATUS =
            CreateAirLogistics.REGISTRATE.displaySource("sky_dock_status", SkyDockDisplaySource::new)
                    .register();

    private ModDisplaySources() {
    }

    public static void register() {
        CreateAirLogistics.LOGGER.debug("Registering display sources for {}", CreateAirLogistics.MOD_ID);
    }
}
