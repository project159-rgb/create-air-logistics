package com.createairlogistics.registry;

import com.createairlogistics.CreateAirLogistics;
import com.createairlogistics.content.contraption.AirshipContraption;
import com.simibubi.create.AllContraptionTypes;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.api.registry.CreateRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModContraptionTypes {

    public static final DeferredRegister<ContraptionType> CONTRAPTION_TYPES =
            DeferredRegister.create(CreateRegistries.CONTRAPTION_TYPE, CreateAirLogistics.MOD_ID);

    public static final DeferredHolder<ContraptionType, ContraptionType> AIRSHIP =
            CONTRAPTION_TYPES.register("airship", () -> {
                ContraptionType type = new ContraptionType(AirshipContraption::new);
                AllContraptionTypes.BY_LEGACY_NAME.put("airship", type);
                AllContraptionTypes.BY_LEGACY_NAME.put(CreateAirLogistics.MOD_ID + ":airship", type);
                return type;
            });

    public static void register(IEventBus eventBus) {
        CONTRAPTION_TYPES.register(eventBus);
    }
}
