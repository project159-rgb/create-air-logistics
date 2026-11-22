package com.createairlogistics.content.navigation;

import com.createairlogistics.client.gui.CreateStylePanel;
import com.createairlogistics.registry.ModBlocks;
import com.createairlogistics.registry.ModDataComponents;
import com.createairlogistics.registry.ModDataComponents.FlightRouteData;
import com.createairlogistics.registry.ModNetwork.FlightChartActionPayload;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import net.createmod.catnip.gui.AbstractSimiScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Uçuş Haritası arayüzü — Create'in Display Link ekranıyla aynı görsel dil.
 * Liman A/B bağlantısı, kaydırmalı irtifa seçici (ScrollInput), X/Y/Z gömme alanları ile ara nokta ekleme
 * ve kaydırılabilir ara nokta listesi (her satırda Create çöp kutusu butonu).
 */
public class FlightChartScreen extends AbstractSimiScreen {

    private static final int WIDTH = 240;
    private static final int BODY_HEIGHT = 184;
    private static final int LIST_TOP = 102;
    private static final int LIST_ROW_H = 19;
    private static final int LIST_ROWS = 4;
    private static final int MIN_ALTITUDE = 5;
    private static final int MAX_ALTITUDE = 60;

    private static final String KEY = "gui.create_air_logistics.chart.";
    private static final Pattern COORD_PATTERN = Pattern.compile("-?\\d{0,7}");

    private final ItemStack iconSkyDock = new ItemStack(ModBlocks.SKY_DOCK.get());
    private final ItemStack iconFeather = new ItemStack(Items.FEATHER);

    private EditBox inputX;
    private EditBox inputY;
    private EditBox inputZ;
    private String savedX;
    private String savedY;
    private String savedZ;
    private int scrollOffset = 0;

    public FlightChartScreen() {
        super(Component.translatable("item.create_air_logistics.flight_chart"));
    }

    private ItemStack getHeldChartStack() {
        if (minecraft == null || minecraft.player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack main = minecraft.player.getMainHandItem();
        if (main.getItem() instanceof FlightChartItem) {
            return main;
        }
        ItemStack off = minecraft.player.getOffhandItem();
        return off.getItem() instanceof FlightChartItem ? off : ItemStack.EMPTY;
    }

    private FlightRouteData getRouteData() {
        ItemStack stack = getHeldChartStack();
        return stack.isEmpty() ? FlightRouteData.EMPTY
                : stack.getOrDefault(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY);
    }

    private void updateLocal(FlightRouteData data) {
        ItemStack stack = getHeldChartStack();
        if (!stack.isEmpty()) {
            stack.set(ModDataComponents.FLIGHT_ROUTE.get(), data);
        }
    }

    private int contentLeft() {
        return guiLeft + CreateStylePanel.CONTENT_LEFT + 5;
    }

    private int contentRight() {
        return guiLeft + WIDTH - CreateStylePanel.CONTENT_RIGHT_INSET - 5;
    }

    private int bodyTop() {
        return guiTop + CreateStylePanel.HEADER_HEIGHT;
    }

    private BlockPos playerWaypointPos(FlightRouteData data) {
        return (minecraft != null && minecraft.player != null)
                ? minecraft.player.blockPosition().above(data.altitudeOffset())
                : new BlockPos(0, 80, 0);
    }

    @Override
    protected void init() {
        if (inputX != null) {
            savedX = inputX.getValue();
            savedY = inputY.getValue();
            savedZ = inputZ.getValue();
        }
        setWindowSize(WIDTH, CreateStylePanel.totalHeight(BODY_HEIGHT));
        super.init();
        clearWidgets();

        FlightRouteData data = getRouteData();
        int cl = contentLeft();
        int cr = contentRight();
        int by = bodyTop();

        // 1) İrtifa (Create ScrollInput: kaydır = ±1, Shift = ±5)
        int fieldX = cl + 23;
        Label altitudeLabel = new Label(fieldX + 5, by + 46 + 5, CommonComponents.EMPTY).withShadow();
        ScrollInput altitude = new ScrollInput(fieldX, by + 46, cr - fieldX, 18)
                .withRange(MIN_ALTITUDE, MAX_ALTITUDE + 1)
                .withShiftStep(5)
                .titled(Component.translatable(KEY + "altitude_title"))
                .format(v -> Component.translatable(KEY + "altitude_value", v))
                .writingTo(altitudeLabel);
        altitude.setState(Mth.clamp(data.altitudeOffset(), MIN_ALTITUDE, MAX_ALTITUDE));
        altitude.onChanged();
        altitude.calling(v -> {
            updateLocal(getRouteData().withAltitudeOffset(v));
            PacketDistributor.sendToServer(new FlightChartActionPayload(0, v));
        });
        addRenderableWidget(altitudeLabel);
        addRenderableWidget(altitude);

        // 2) X / Y / Z gömme alanları
        BlockPos here = playerWaypointPos(data);
        int rowY = by + 74;
        inputX = coordinateBox(cl, rowY, 44, "X", savedX != null ? savedX : String.valueOf(here.getX()));
        inputY = coordinateBox(cl + 47, rowY, 38, "Y", savedY != null ? savedY : String.valueOf(here.getY()));
        inputZ = coordinateBox(cl + 88, rowY, 44, "Z", savedZ != null ? savedZ : String.valueOf(here.getZ()));

        IconButton fillHere = new IconButton(cr - 38, rowY, AllIcons.I_TARGET);
        fillHere.setToolTip(Component.translatable(KEY + "here_tooltip"));
        fillHere.withCallback(() -> {
            BlockPos p = playerWaypointPos(getRouteData());
            inputX.setValue(String.valueOf(p.getX()));
            inputY.setValue(String.valueOf(p.getY()));
            inputZ.setValue(String.valueOf(p.getZ()));
        });
        addRenderableWidget(fillHere);

        IconButton add = new IconButton(cr - 18, rowY, AllIcons.I_ADD);
        add.green = true;
        add.setToolTip(Component.translatable(KEY + "add_tooltip"));
        add.withCallback(() -> {
            if (minecraft == null || minecraft.player == null || minecraft.level == null) {
                return;
            }
            FlightRouteData current = getRouteData();
            if (current.waypoints().size() >= FlightRouteData.MAX_WAYPOINTS) {
                minecraft.player.displayClientMessage(
                        Component.translatable("message.create_air_logistics.chart.waypoint_limit",
                                        FlightRouteData.MAX_WAYPOINTS)
                                .withStyle(ChatFormatting.RED),
                        true
                );
                return;
            }
            BlockPos raw = parseWaypoint(playerWaypointPos(current));
            if (!current.isWaypointInRange(raw, minecraft.player.blockPosition())) {
                minecraft.player.displayClientMessage(
                        Component.translatable("message.create_air_logistics.chart.waypoint_out_of_range",
                                        FlightRouteData.MAX_WAYPOINT_RANGE)
                                .withStyle(ChatFormatting.RED),
                        true
                );
                return;
            }
            BlockPos valid = current.sanitizeWaypoint(
                    raw,
                    minecraft.player.blockPosition(),
                    minecraft.level.getMinBuildHeight(),
                    minecraft.level.getMaxBuildHeight()
            );
            if (valid == null) {
                return;
            }
            if (valid.getY() != raw.getY() && inputY != null) {
                inputY.setValue(String.valueOf(valid.getY()));
            }
            updateLocal(current.withAddedWaypoint(valid));
            PacketDistributor.sendToServer(new FlightChartActionPayload(3, 0, valid));
            int size = getRouteData().waypoints().size();
            scrollOffset = Math.max(0, size - LIST_ROWS);
            rebuildWidgets();
        });
        addRenderableWidget(add);

        // 3) Ara nokta listesi: her satırda Create çöp kutusu
        List<BlockPos> waypoints = data.waypoints();
        scrollOffset = Mth.clamp(scrollOffset, 0, Math.max(0, waypoints.size() - LIST_ROWS));
        for (int row = 0; row < LIST_ROWS; row++) {
            int index = scrollOffset + row;
            if (index >= waypoints.size()) {
                break;
            }
            IconButton remove = new IconButton(cr - 18, by + LIST_TOP + row * LIST_ROW_H, AllIcons.I_TRASH);
            remove.setToolTip(Component.translatable(KEY + "remove_tooltip"));
            remove.withCallback(() -> removeWaypoint(index));
            addRenderableWidget(remove);
        }

        // 4) Alt bant: haritayı sıfırla + onay
        int footerY = CreateStylePanel.footerTop(guiTop, BODY_HEIGHT);
        IconButton clear = new IconButton(guiLeft + 8, footerY + 6, AllIcons.I_DISABLE);
        clear.setToolTip(Component.translatable(KEY + "clear_all"));
        clear.withCallback(() -> {
            updateLocal(FlightRouteData.EMPTY);
            PacketDistributor.sendToServer(new FlightChartActionPayload(2, 0));
            scrollOffset = 0;
            rebuildWidgets();
        });
        addRenderableWidget(clear);

        IconButton confirm = new IconButton(guiLeft + WIDTH - CreateStylePanel.CONFIRM_X_FROM_RIGHT,
                footerY + CreateStylePanel.CONFIRM_Y_IN_FOOTER, AllIcons.I_CONFIRM);
        confirm.withCallback(this::onClose);
        addRenderableWidget(confirm);
    }

    private EditBox coordinateBox(int x, int y, int w, String hint, String value) {
        EditBox box = new EditBox(font, x + 5, y + 5, w - 8, 10, Component.literal(hint));
        box.setBordered(false);
        box.setTextColor(0xFFFFFF);
        box.setMaxLength(8);
        box.setFilter(s -> COORD_PATTERN.matcher(s).matches());
        box.setValue(value);
        box.setHint(Component.literal(hint).withColor(0xD0D0D0));
        addRenderableWidget(box);
        return box;
    }

    private void removeWaypoint(int index) {
        FlightRouteData current = getRouteData();
        List<BlockPos> updated = new ArrayList<>(current.waypoints());
        if (index < updated.size()) {
            updated.remove(index);
            updateLocal(new FlightRouteData(current.dockA(), current.dockAName(),
                    current.dockB(), current.dockBName(), updated, current.altitudeOffset()));
        }
        PacketDistributor.sendToServer(new FlightChartActionPayload(1, index));
        rebuildWidgets();
    }

    private BlockPos parseWaypoint(BlockPos fallback) {
        try {
            return new BlockPos(
                    Integer.parseInt(inputX.getValue().trim()),
                    Integer.parseInt(inputY.getValue().trim()),
                    Integer.parseInt(inputZ.getValue().trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Override
    protected void renderWindow(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        int x = guiLeft;
        int y = guiTop;
        CreateStylePanel.render(g, x, y, WIDTH, BODY_HEIGHT);
        g.drawString(font, title, x + (WIDTH - 8) / 2 - font.width(title) / 2, y + 4,
                CreateStylePanel.TITLE_COLOR, false);

        FlightRouteData data = getRouteData();
        int cl = contentLeft();
        int cr = contentRight();
        int by = bodyTop();

        // Liman A / B
        drawDockRow(g, cl, cr, by + 6, iconSkyDock, "dock_a", data.dockA().isPresent(), data.resolveDockADisplayName());
        drawDockRow(g, cl, cr, by + 26, iconSkyDock, "dock_b", data.dockB().isPresent(), data.resolveDockBDisplayName());

        // İrtifa satırı
        CreateStylePanel.drawSlot(g, cl, by + 46);
        g.renderItem(iconFeather, cl + 1, by + 47);
        CreateStylePanel.drawSlotArrow(g, cl + 19, by + 46);
        CreateStylePanel.drawField(g, cl + 23, by + 46, cr - (cl + 23));

        CreateStylePanel.drawDivider(g, cl - 3, cr + 3, by + 68);

        // X / Y / Z alanları
        CreateStylePanel.drawField(g, cl, by + 74, 44);
        CreateStylePanel.drawField(g, cl + 47, by + 74, 38);
        CreateStylePanel.drawField(g, cl + 88, by + 74, 44);

        CreateStylePanel.drawDivider(g, cl - 3, cr + 3, by + 96);

        // Ara nokta listesi
        List<BlockPos> waypoints = data.waypoints();
        if (waypoints.isEmpty()) {
            List<FormattedCharSequence> lines =
                    font.split(Component.translatable(KEY + "empty"), cr - cl - 8);
            int ty = by + LIST_TOP + 22 - lines.size() * 5;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, (cl + cr) / 2 - font.width(line) / 2, ty, 0xA0A0A0, false);
                ty += 10;
            }
        } else {
            for (int row = 0; row < LIST_ROWS; row++) {
                int index = scrollOffset + row;
                if (index >= waypoints.size()) {
                    break;
                }
                BlockPos wp = waypoints.get(index);
                int rowY = by + LIST_TOP + row * LIST_ROW_H;
                CreateStylePanel.drawField(g, cl, rowY, cr - 21 - cl);
                g.drawString(font, "#" + (index + 1), cl + 5, rowY + 5, 0xFFE4B763, true);
                String coordText = wp.getX() + ", " + wp.getY() + ", " + wp.getZ();
                // Silme butonuna (cr - 21) taşmasın
                g.drawString(font, font.plainSubstrByWidth(coordText, cr - 24 - (cl + 26)), cl + 26, rowY + 5, 0xFFFFFF, true);
            }
        }

        // Alt bant
        int footerY = CreateStylePanel.footerTop(y, BODY_HEIGHT);
        g.drawString(font, Component.translatable(KEY + "clear_all"), x + 30, footerY + 6, AllGuiTextures.FONT_COLOR, false);
        Component count = waypoints.size() > LIST_ROWS
                ? Component.translatable(KEY + "list_range", scrollOffset + 1,
                Math.min(waypoints.size(), scrollOffset + LIST_ROWS), waypoints.size())
                : Component.translatable(KEY + "waypoint_count", waypoints.size());
        g.drawString(font, count, x + 30, footerY + 16, 0x7A7A7A, false);
    }

    private void drawDockRow(GuiGraphics g, int cl, int cr, int rowY, ItemStack icon, String key, boolean linked, Component name) {
        CreateStylePanel.drawSlot(g, cl, rowY);
        g.renderItem(icon, cl + 1, rowY + 1);
        CreateStylePanel.drawSlotArrow(g, cl + 19, rowY);
        CreateStylePanel.drawField(g, cl + 23, rowY, cr - (cl + 23) - 22);
        Component text = linked
                ? Component.translatable(KEY + key, name)
                : Component.translatable(KEY + key, Component.translatable(KEY + "unlinked"));
        String clipped = font.plainSubstrByWidth(text.getString(), cr - (cl + 23) - 32);
        g.drawString(font, clipped, cl + 28, rowY + 5, linked ? 0xFFFFFF : 0xD0D0D0, true);
        (linked ? AllGuiTextures.INDICATOR_GREEN : AllGuiTextures.INDICATOR_RED).render(g, cr - 18, rowY + 6);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int by = bodyTop();
        boolean overList = mouseX >= contentLeft() && mouseX < contentRight()
                && mouseY >= by + LIST_TOP && mouseY < by + LIST_TOP + LIST_ROWS * LIST_ROW_H;
        int size = getRouteData().waypoints().size();
        if (overList && size > LIST_ROWS) {
            int next = Mth.clamp(scrollOffset - (int) Math.signum(scrollY), 0, size - LIST_ROWS);
            if (next != scrollOffset) {
                scrollOffset = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void tick() {
        super.tick();
        // Harita elden bırakılırsa (atma, slot değiştirme) ekran kapanır
        if (getHeldChartStack().isEmpty()) {
            onClose();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (inputX != null && inputY != null && inputZ != null) {
            int cl = contentLeft();
            int rowY = bodyTop() + 74;
            boolean inX = mouseX >= cl && mouseX < cl + 44 && mouseY >= rowY && mouseY < rowY + 18;
            boolean inY = mouseX >= cl + 47 && mouseX < cl + 85 && mouseY >= rowY && mouseY < rowY + 18;
            boolean inZ = mouseX >= cl + 88 && mouseX < cl + 132 && mouseY >= rowY && mouseY < rowY + 18;

            if (!inX && inputX.isFocused()) {
                inputX.setFocused(false);
            }
            if (!inY && inputY.isFocused()) {
                inputY.setFocused(false);
            }
            if (!inZ && inputZ.isFocused()) {
                inputZ.setFocused(false);
            }
            if (!inX && !inY && !inZ && getFocused() instanceof EditBox) {
                setFocused(null);
            }

            EditBox clickedBox = inX ? inputX : inY ? inputY : inZ ? inputZ : null;
            if (button == 1 && clickedBox != null) {
                clickedBox.setValue("");
                setFocused(clickedBox);
                clickedBox.setFocused(true);
                return true;
            }

            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            if (button == 0 && clickedBox != null) {
                setFocused(clickedBox);
                clickedBox.setFocused(true);
                return true;
            }
            return handled;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 258 && inputX != null && inputY != null && inputZ != null) {
            boolean reverse = (modifiers & 1) != 0;
            EditBox target;
            if (inputX.isFocused()) {
                target = reverse ? inputZ : inputY;
            } else if (inputY.isFocused()) {
                target = reverse ? inputX : inputZ;
            } else if (inputZ.isFocused()) {
                target = reverse ? inputY : inputX;
            } else {
                target = reverse ? inputZ : inputX;
            }
            inputX.setFocused(false);
            inputY.setFocused(false);
            inputZ.setFocused(false);
            setFocused(target);
            target.setFocused(true);
            return true;
        }
        // Koordinat yazarken envanter tuşu (E) ekranı kapatmasın
        EditBox activeBox = (inputX != null && inputX.isFocused()) ? inputX
                : (inputY != null && inputY.isFocused()) ? inputY
                : (inputZ != null && inputZ.isFocused()) ? inputZ
                : (getFocused() instanceof EditBox box && box.isFocused()) ? box : null;
        if (activeBox != null && keyCode != 256) {
            if (activeBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
                return true;
            }
            if (activeBox.canConsumeInput()) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new FlightChartScreen());
    }
}
