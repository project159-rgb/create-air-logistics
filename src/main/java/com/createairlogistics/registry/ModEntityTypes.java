package com.createairlogistics.registry;

import com.createairlogistics.CreateAirLogistics;
import com.createairlogistics.content.contraption.AirshipEntity;
import com.createairlogistics.content.contraption.AirshipEntityRenderer;
import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import com.tterrag.registrate.util.entry.EntityEntry;
import net.minecraft.world.entity.MobCategory;

public class ModEntityTypes {

    public static final EntityEntry<AirshipEntity> AIRSHIP = CreateAirLogistics.REGISTRATE
            .<AirshipEntity>entity("airship", AirshipEntity::new, MobCategory.MISC)
            .visual(() -> ContraptionVisual::new)
            .properties(b -> b.sized(1.8f, 1.8f).clientTrackingRange(10).updateInterval(1).fireImmune())
            .renderer(() -> AirshipEntityRenderer::new)
            .register();

    public static void register() {
        CreateAirLogistics.LOGGER.debug("Registering entity types for {}", CreateAirLogistics.MOD_ID);
    }
}
