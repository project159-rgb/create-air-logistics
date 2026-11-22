package com.createairlogistics.registry;

import com.createairlogistics.CreateAirLogistics;
import com.createairlogistics.content.navigation.FlightChartItem;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyItem;
import com.tterrag.registrate.util.entry.ItemEntry;
import net.minecraft.world.item.Item;

/**
 * Create: Air Logistics - Temel Eşya Kayıtları:
 * 1. MECHANICAL_PATHFINDER (Mekanik Yön Bulucu): Sequenced Assembly ile üretilen ve Yön Bulucu Pervane yapımında kullanılan mekanik çekirdek.
 * 2. INCOMPLETE_MECHANICAL_PATHFINDER (Tamamlanmamış Mekanik Yön Bulucu): Bant üzerindeki ara üretim eşyası.
 * 3. FLIGHT_CHART (Uçuş Haritası): İki Liman Vincini (Sky Dock) ve ara irtifa noktalarını bağlayıp Yön Bulucu Pervaneye filtre gibi takılan harita.
 */
public class ModItems {

    public static final ItemEntry<Item> MECHANICAL_PATHFINDER = CreateAirLogistics.REGISTRATE
            .item("mechanical_pathfinder", Item::new)
            .register();

    public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_MECHANICAL_PATHFINDER = CreateAirLogistics.REGISTRATE
            .item("incomplete_mechanical_pathfinder", SequencedAssemblyItem::new)
            .register();

    public static final ItemEntry<FlightChartItem> FLIGHT_CHART = CreateAirLogistics.REGISTRATE
            .item("flight_chart", FlightChartItem::new)
            .properties(p -> p.stacksTo(1))
            .register();

    public static final ItemEntry<Item> WOODEN_PROPELLER = CreateAirLogistics.REGISTRATE
            .item("wooden_propeller", Item::new)
            .register();

    public static void register() {
        CreateAirLogistics.LOGGER.debug("Registering items for {}", CreateAirLogistics.MOD_ID);
    }
}
