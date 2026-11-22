package com.createairlogistics.registry;

import com.createairlogistics.CreateAirLogistics;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Gövde ağırlık sınıfları (Hafif: 1, Orta: 2, Ağır: 3) ve ekstra hız pervaneleri için etiketler.
 */
public class ModTags {

    public static final TagKey<Block> LIGHT_HULL_BLOCKS = blockTag("light_hull_blocks");
    public static final TagKey<Block> MEDIUM_HULL_BLOCKS = blockTag("medium_hull_blocks");
    public static final TagKey<Block> HEAVY_HULL_BLOCKS = blockTag("heavy_hull_blocks");

    /**
     * Geminin yönünü değiştirmeyen, sadece seyir hızını (+Speed) artıran ekstra pervaneler
     * (Create ve Create: Aeronautics pervaneleri dahil).
     */
    public static final TagKey<Block> EXTRA_THRUSTERS = blockTag("extra_thrusters");

    /**
     * Hava gemisinde kaldırma kuvveti (Lift) üreten balon blokları
     * (Canvas Balloon ve Create: Aeronautics Hot Air Envelope blokları dahil).
     */
    public static final TagKey<Block> BALLOON_BLOCKS = blockTag("balloon_blocks");

    private static TagKey<Block> blockTag(String path) {
        return TagKey.create(Registries.BLOCK, CreateAirLogistics.asResource(path));
    }
}
