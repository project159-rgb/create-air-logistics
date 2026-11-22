package com.createairlogistics.content.pressure;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Engineer's Goggles satırları için ortak hava göstergesi (Create tooltip diliyle: 4 boşluk girinti,
 * gri etiket, aqua dolu / koyu gri boş çubuk ve sayısal değer).
 */
public final class AirTooltip {

    private static final int BAR_LENGTH = 12;

    private AirTooltip() {
    }

    public static void header(List<Component> tooltip, Component title) {
        tooltip.add(Component.literal("    ").append(title.copy().withStyle(ChatFormatting.WHITE)));
    }

    public static void airBar(List<Component> tooltip, Component label, double air, int capacity) {
        int filled = capacity <= 0 ? 0 : (int) Math.round(BAR_LENGTH * Math.min(1.0, Math.max(0.0, air / capacity)));
        MutableComponent line = Component.literal("    ").append(label.copy().withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" "));
        line.append(Component.literal("|".repeat(filled)).withStyle(ChatFormatting.AQUA));
        line.append(Component.literal("|".repeat(BAR_LENGTH - filled)).withStyle(ChatFormatting.DARK_GRAY));
        line.append(Component.literal(" " + (int) Math.floor(air) + " / " + capacity).withStyle(ChatFormatting.AQUA));
        tooltip.add(line);
    }

    public static void status(List<Component> tooltip, Component text, boolean ok) {
        tooltip.add(Component.literal("    ").append(text.copy().withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.GOLD)));
    }
}
