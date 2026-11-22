package com.createairlogistics.content.dock;

import com.createairlogistics.client.gui.CreateStylePanel;
import com.createairlogistics.content.contraption.AirshipAssemblyValidator;
import com.createairlogistics.content.contraption.AirshipAssemblyValidator.DiagnosticReport;
import com.createairlogistics.content.navigation.FlightChartItem;
import com.createairlogistics.content.pressure.AirPressure;
import com.createairlogistics.registry.ModBlocks;
import com.createairlogistics.registry.ModDataComponents;
import com.createairlogistics.registry.ModDataComponents.FlightRouteData;
import com.createairlogistics.registry.ModItems;
import com.createairlogistics.registry.ModNetwork.SkyDockActionPayload;
import com.createairlogistics.registry.ModNetwork.SkyDockConfigurePayload;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
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
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Liman (Sky Dock) arayüzü — Create'in Display Link ekranıyla aynı görsel dil:
 * pirinç başlık, ahşap raylı koyu gövde, gömme değer alanları, kaydırmalı seçiciler (SelectionScrollInput),
 * istasyon adı ve paket adresi filtresi, yeşil/kırmızı Create gösterge lambaları ve sağ altta onay (kapat) butonu.
 */
public class SkyDockScreen extends AbstractSimiScreen {

    private static final int WIDTH = 276;
    private static final int BODY_HEIGHT = 194;
    private static final int CONFIG_ROW_Y = 50;
    private static final int DIVIDER_Y = 74;
    private static final int CHECK_ROW_Y = 80;
    private static final int CHECK_ROW_H = 18;

    // Alt bant (birleştir kontrolü) yerleşimi ve renkleri
    private static final int ASSEMBLE_X = 9;
    private static final int FOOTER_TEXT_X = 34;
    private static final int STAT_GAP = 11;
    private static final int STAT_COLOR = 0x666666;
    private static final int STAT_SEPARATOR = 0xFF8E8E8E;
    private static final int BUFFER_METER = 0xFFC8963E;
    private static final int FLUID_METER = 0xFF42A5F5;
    private static final int AIR_METER = 0xFF5E9ED6;
    private static final int AIR_METER_IDLE = 0xFF8C8C8C;

    // Uçuş koridoru uyarı şeridi (gömme kehribar/kırmızı, koyu gövdeyle uyumlu)
    private static final int ALERT_FILL = 0x26FF5252;
    private static final int ALERT_ACCENT = 0xFFE57373;
    private static final int ALERT_TOP_EDGE = 0x40FF8A80;
    private static final int ALERT_BOTTOM_SHADE = 0x30000000;
    private static final int ALERT_TOOLTIP_WIDTH = 220;
    /** Onaylanmamış seçici satırının sol kenar vurgusu (Create pirinç/kehribar). */
    private static final int PENDING_ACCENT = 0xFFE4B763;
    /** Tanı raporu (BFS + koridor taraması) her karede değil, bu kadar tickte bir yenilenir. */
    private static final int REPORT_REFRESH_TICKS = 5;

    private static final String KEY = "gui.create_air_logistics.sky_dock.";

    private final BlockPos dockPos;
    private final ItemStack iconSkyDock = new ItemStack(ModBlocks.SKY_DOCK.get());
    private final ItemStack iconClock = new ItemStack(Items.CLOCK);
    private final ItemStack iconNameTag = new ItemStack(Items.NAME_TAG);
    private final ItemStack iconPackageBox = PackageStyles.getDefaultBox();

    private SelectionScrollInput modeInput;
    private SelectionScrollInput departureInput;
    private EditBox nameBox;
    private EditBox filterBox;
    private Integer savedMode;
    private Integer savedDeparture;
    private String savedName;
    private String savedFilter;
    private IconButton assembleButton;
    private IconButton confirmButton;

    private DiagnosticReport cachedReport;
    private int reportAge = 0;

    public SkyDockScreen(BlockPos dockPos) {
        super(Component.translatable("block.create_air_logistics.sky_dock"));
        this.dockPos = dockPos;
    }

    private SkyDockBlockEntity getDockBE() {
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        return minecraft.level.getBlockEntity(dockPos) instanceof SkyDockBlockEntity be ? be : null;
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

    @Override
    protected void init() {
        if (modeInput != null) {
            savedMode = modeInput.getState();
        }
        if (departureInput != null) {
            savedDeparture = departureInput.getState();
        }
        if (nameBox != null) {
            savedName = nameBox.getValue();
        }
        if (filterBox != null) {
            savedFilter = filterBox.getValue();
        }
        setWindowSize(WIDTH, CreateStylePanel.totalHeight(BODY_HEIGHT));
        super.init();
        clearWidgets();

        SkyDockBlockEntity be = getDockBE();
        if (be == null) {
            return;
        }

        int initialModeOrd = savedMode != null ? savedMode : be.getDockMode().ordinal();
        int initialDepOrd = savedDeparture != null ? savedDeparture : be.getDepartureCondition().ordinal();
        SkyDockBlockEntity.DockMode initialMode = SkyDockBlockEntity.DockMode.values()[
                Math.floorMod(initialModeOrd, SkyDockBlockEntity.DockMode.values().length)];

        int cl = contentLeft();
        int cr = contentRight();
        int fieldX = cl + 23;
        int fieldW = cr - fieldX;

        // 1) İstasyon adı (sol yarı) + Karton Paket adresi filtresi (sağ yarı)
        // Not: EditBox bileşenleri GL Scissor kullandığından seçicilerden ve butonlardan ÖNCE eklenir;
        // böylece hiçbir üst katman ipucu (tooltip) metni EditBox kırpma alanından etkilenmez.
        int mid = (cl + cr) / 2;
        int configY = bodyTop() + CONFIG_ROW_Y;
        int leftFieldX = cl + 23;
        int leftFieldW = (mid - 3) - leftFieldX;
        int rightFieldX = (mid + 3) + 23;
        int rightFieldW = cr - rightFieldX;

        nameBox = createConfigBox(leftFieldX, configY, leftFieldW,
                Component.translatable(KEY + "station_hint"),
                savedName != null ? savedName : be.getCustomName());
        filterBox = createConfigBox(rightFieldX, configY, rightFieldW,
                Component.translatable(KEY + "filter_hint"),
                savedFilter != null ? savedFilter : be.getPackageFilter());

        // 2) Transfer modu (YÜKLE / BOŞALT)
        List<Component> modes = new ArrayList<>();
        for (SkyDockBlockEntity.DockMode m : SkyDockBlockEntity.DockMode.values()) {
            modes.add(Component.translatable(m.getTranslationKey()));
        }
        Label modeLabel = new Label(fieldX + 5, bodyTop() + 6 + 5, CommonComponents.EMPTY).withShadow();
        modeInput = createSelector(fieldX, bodyTop() + 6, fieldW, modes, modeLabel,
                Component.translatable(KEY + "mode_title"), initialModeOrd);
        // Seçim yalnızca taslaktır; mod değiştikçe kalkış şartı etiketleri anında güncellenir
        modeInput.calling(this::refreshDepartureOptions);
        addRenderableWidget(modeLabel);
        addRenderableWidget(modeInput);

        // 3) Kalkış şartı
        List<Component> conditions = buildDepartureOptions(initialMode);
        Label depLabel = new Label(fieldX + 5, bodyTop() + 28 + 5, CommonComponents.EMPTY).withShadow();
        departureInput = createSelector(fieldX, bodyTop() + 28, fieldW, conditions, depLabel,
                Component.translatable(KEY + "departure_title"), initialDepOrd);
        departureInput.calling(state -> {});
        addRenderableWidget(depLabel);
        addRenderableWidget(departureInput);

        // 4) Alt bant: Birleştir / Sök  +  Onay (kapat)
        int footerY = CreateStylePanel.footerTop(guiTop, BODY_HEIGHT);
        ItemStack propellerIcon = new ItemStack(ModBlocks.PATHFINDER_PROPELLER.get());
        // Onay butonuyla aynı yatay eksende; gömme yuvasının içine oturur (bkz. renderAssembleFooter)
        assembleButton = new IconButton(guiLeft + ASSEMBLE_X, footerY + CreateStylePanel.CONFIRM_Y_IN_FOOTER,
                (g, x, y) -> g.renderItem(propellerIcon, x, y));
        assembleButton.setToolTip(Component.translatable(KEY + "assemble"));
        assembleButton.withCallback(() -> {
            // Bekleyen mod / kalkış şartı / istasyon ayarları önce uygulanır ki gemi doğru ayarlarla birleşsin
            applyPendingChanges();
            PacketDistributor.sendToServer(new SkyDockActionPayload(dockPos, 2));
            onClose();
        });
        addRenderableWidget(assembleButton);

        confirmButton = new IconButton(guiLeft + WIDTH - CreateStylePanel.CONFIRM_X_FROM_RIGHT,
                footerY + CreateStylePanel.CONFIRM_Y_IN_FOOTER, AllIcons.I_CONFIRM);
        confirmButton.withCallback(this::confirmAndClose);
        addRenderableWidget(confirmButton);
    }

    private EditBox createConfigBox(int fieldX, int rowY, int fieldW, Component hint, String initialValue) {
        EditBox box = new EditBox(font, fieldX + 5, rowY + 5, fieldW - 13, 10, hint) {
            @Override
            public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
                g.flush();
                g.enableScissor(fieldX + 2, rowY + 2, fieldX + fieldW - 2, rowY + 16);
                try {
                    super.renderWidget(g, mouseX, mouseY, partialTicks);
                    g.flush();
                } finally {
                    g.disableScissor();
                }
            }
        };
        box.setBordered(false);
        box.setTextColor(0xFFFFFF);
        box.setMaxLength(32);
        String clippedHint = font.plainSubstrByWidth(hint.getString(), fieldW - 10);
        box.setHint(Component.literal(clippedHint).withColor(0xC8C8C8));
        box.setValue(initialValue);
        addRenderableWidget(box);
        return box;
    }

    /**
     * Seçicilerdeki ve metin alanlarındaki taslak değerleri sunucuya gönderir. Yalnızca Onay / Birleştir ile çağrılır;
     * Esc ile çıkışta hiçbir değişiklik uygulanmaz.
     */
    private void applyPendingChanges() {
        SkyDockBlockEntity dock = getDockBE();
        if (dock == null) {
            return;
        }
        if (modeInput != null && dock.getDockMode().ordinal() != modeInput.getState()) {
            dock.toggleDockMode();
            PacketDistributor.sendToServer(new SkyDockActionPayload(dockPos, 0));
        }
        if (departureInput != null && dock.getDepartureCondition().ordinal() != departureInput.getState()) {
            int count = SkyDockBlockEntity.DepartureCondition.values().length;
            int steps = Math.floorMod(departureInput.getState() - dock.getDepartureCondition().ordinal(), count);
            for (int i = 0; i < steps; i++) {
                dock.cycleDepartureCondition();
                PacketDistributor.sendToServer(new SkyDockActionPayload(dockPos, 1));
            }
        }
        if (nameBox != null && filterBox != null) {
            String nextName = nameBox.getValue().trim();
            String nextFilter = filterBox.getValue().trim();
            if (!nextName.equals(dock.getCustomName()) || !nextFilter.equals(dock.getPackageFilter())) {
                dock.setConfiguration(nextName, nextFilter);
                PacketDistributor.sendToServer(new SkyDockConfigurePayload(dockPos, nextName, nextFilter));
            }
        }
    }

    private void confirmAndClose() {
        applyPendingChanges();
        onClose();
    }

    /** Seçicilerden veya metin alanlarından herhangi biri limanın mevcut ayarından farklıysa (onaylanmamış taslak) true. */
    private boolean hasPendingChanges() {
        SkyDockBlockEntity be = getDockBE();
        if (be == null) {
            return false;
        }
        boolean modeChanged = modeInput != null && modeInput.getState() != be.getDockMode().ordinal();
        boolean depChanged = departureInput != null && departureInput.getState() != be.getDepartureCondition().ordinal();
        boolean nameChanged = nameBox != null && !nameBox.getValue().trim().equals(be.getCustomName());
        boolean filterChanged = filterBox != null && !filterBox.getValue().trim().equals(be.getPackageFilter());
        return modeChanged || depChanged || nameChanged || filterChanged;
    }

    /** Create SelectionScrollInput: kaydırarak veya tıklayarak seçenekler arasında döner. */
    private SelectionScrollInput createSelector(int x, int y, int w, List<Component> options, Label label,
                                                Component title, int initial) {
        SelectionScrollInput input = new SelectionScrollInput(x, y, w, 18);
        input.forOptions(options);
        input.titled(title.copy());
        input.writingTo(label);
        input.setState(initial);
        input.onChanged();
        input.withCallback(() -> {
            input.setState((input.getState() + 1) % options.size());
            input.onChanged();
        });
        return input;
    }

    private List<Component> buildDepartureOptions(SkyDockBlockEntity.DockMode mode) {
        List<Component> options = new ArrayList<>();
        for (SkyDockBlockEntity.DepartureCondition c : SkyDockBlockEntity.DepartureCondition.values()) {
            options.add(Component.translatable(c.getTranslationKey(mode)));
        }
        return options;
    }

    private void refreshDepartureOptions(int modeOrdinal) {
        if (departureInput == null) {
            return;
        }
        SkyDockBlockEntity.DockMode[] modes = SkyDockBlockEntity.DockMode.values();
        SkyDockBlockEntity.DockMode mode = modes[Math.floorMod(modeOrdinal, modes.length)];
        int currentDep = departureInput.getState();
        departureInput.forOptions(buildDepartureOptions(mode));
        departureInput.setState(currentDep);
        departureInput.onChanged();
    }

    @Override
    protected void renderWindow(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        int x = guiLeft;
        int y = guiTop;
        CreateStylePanel.render(g, x, y, WIDTH, BODY_HEIGHT);

        g.drawString(font, title, x + (WIDTH - 8) / 2 - font.width(title) / 2, y + 4,
                CreateStylePanel.TITLE_COLOR, false);

        int cl = contentLeft();
        int cr = contentRight();
        int by = bodyTop();

        // Seçici satırları: ikon yuvası ◀ değer alanı (onaylanmamış satır kehribar vurgulu)
        SkyDockBlockEntity be = getDockBE();
        boolean modeDirty = be != null && modeInput != null && modeInput.getState() != be.getDockMode().ordinal();
        boolean depDirty = be != null && departureInput != null
                && departureInput.getState() != be.getDepartureCondition().ordinal();
        boolean nameDirty = be != null && nameBox != null && !nameBox.getValue().trim().equals(be.getCustomName());
        boolean filterDirty = be != null && filterBox != null && !filterBox.getValue().trim().equals(be.getPackageFilter());
        drawSelectorRow(g, cl, cr, by + 6, iconSkyDock, modeDirty);
        drawSelectorRow(g, cl, cr, by + 28, iconClock, depDirty);

        int mid = (cl + cr) / 2;
        int configY = by + CONFIG_ROW_Y;
        drawSelectorRow(g, cl, mid - 3, configY, iconNameTag, nameDirty);
        drawSelectorRow(g, mid + 3, cr, configY, iconPackageBox, filterDirty);

        CreateStylePanel.drawDivider(g, cl - 3, cr + 3, by + DIVIDER_Y);

        renderChecklist(g, cl, cr, by + CHECK_ROW_Y, mouseX, mouseY);

        // Alt bant: birleştir kontrolü + tampon / sıvı / hava göstergeleri
        renderAssembleFooter(g, x, CreateStylePanel.footerTop(y, BODY_HEIGHT));

        if (mouseY >= configY && mouseY < configY + 18) {
            if (mouseX >= cl && mouseX < cl + 18) {
                g.renderTooltip(font, font.split(Component.translatable(KEY + "station_tooltip"), ALERT_TOOLTIP_WIDTH), mouseX, mouseY);
            } else if (mouseX >= mid + 3 && mouseX < mid + 21) {
                g.renderTooltip(font, font.split(Component.translatable(KEY + "filter_tooltip"), ALERT_TOOLTIP_WIDTH), mouseX, mouseY);
            }
        }
    }

    /**
     * Alt bant düzeni (onay butonuyla aynı dikey merkez):
     * <pre>
     *  ┌──┐ Gemiyi Birleştir / Sök
     *  │⚙ │ Tampon: 3 / 18 │ Sıvı: 4.0B │ Hava: 900 / 1800
     *  └──┘ ▬▬▬▭▭▭▭▭▭▭▭▭▭   ▬▬▬▬▭▭▭▭   ▬▬▬▬▬▬▭▭▭▭▭▭▭▭▭▭
     * </pre>
     * Buton gömme bir yuvaya oturur; her değerin altında kendi genişliğinde doluluk çubuğu bulunur.
     * Metin alanı onay butonuna taşarsa satır, hiçbir bilgi kırpılmadan orantılı küçültülür.
     */
    private void renderAssembleFooter(GuiGraphics g, int x, int footerY) {
        int btnY = footerY + CreateStylePanel.CONFIRM_Y_IN_FOOTER;
        CreateStylePanel.drawButtonSocket(g, x + ASSEMBLE_X, btnY);

        int textX = x + FOOTER_TEXT_X;
        int textRight = x + WIDTH - CreateStylePanel.CONFIRM_X_FROM_RIGHT - 6;

        // Başlık: buton üzerine gelindiğinde pirinç başlık rengine geçer (etiket ↔ buton bağı)
        boolean hot = assembleButton != null && assembleButton.isHovered();
        g.drawString(font, Component.translatable(KEY + "assemble_short"), textX, btnY - 2,
                hot ? CreateStylePanel.TITLE_COLOR : AllGuiTextures.FONT_COLOR, false);

        SkyDockBlockEntity be = getDockBE();
        if (be == null) {
            return;
        }
        int occupied = 0;
        for (int i = 0; i < be.getItemBuffer().getSlots(); i++) {
            if (!be.getItemBuffer().getStackInSlot(i).isEmpty()) {
                occupied++;
            }
        }
        int capacity = AirPressure.dockCapacity();
        Component bufferText = Component.translatable(KEY + "buffer", occupied, SkyDockBlockEntity.BUFFER_SLOTS);
        boolean hasFluid = !be.getFluidBuffer().isEmpty();
        Component fluidText = hasFluid
                ? Component.translatable(KEY + "fluid", String.format(Locale.ROOT, "%.1f", be.getFluidBuffer().getFluidAmount() / 1000.0f))
                : CommonComponents.EMPTY;
        Component tankText = Component.translatable(KEY + (be.hasShaftSocket() ? "tank" : "tank_no_socket"),
                be.getAir(), capacity);

        float bufferFill = occupied / (float) SkyDockBlockEntity.BUFFER_SLOTS;
        float fluidFill = hasFluid
                ? Mth.clamp(be.getFluidBuffer().getFluidAmount() / (float) SkyDockBlockEntity.FLUID_CAPACITY_MB, 0f, 1f)
                : 0f;
        float airFill = capacity > 0 ? Mth.clamp(be.getAir() / (float) capacity, 0f, 1f) : 0f;

        int bw = font.width(bufferText);
        int fw = hasFluid ? font.width(fluidText) : 0;
        int tw = font.width(tankText);
        int total = bw + (hasFluid ? STAT_GAP + fw : 0) + STAT_GAP + tw;
        float scale = Math.min(1f, (textRight - textX) / (float) total);

        g.pose().pushPose();
        g.pose().translate(textX, btnY + 8, 0);
        g.pose().scale(scale, scale, 1f);

        int cursorX = 0;
        g.drawString(font, bufferText, cursorX, 0, STAT_COLOR, false);
        CreateStylePanel.drawMeter(g, cursorX, 10, bw, bufferFill, BUFFER_METER);
        cursorX += bw;

        if (hasFluid) {
            int sepFluidX = cursorX + STAT_GAP / 2;
            g.fill(sepFluidX, 0, sepFluidX + 1, 7, STAT_SEPARATOR);
            cursorX += STAT_GAP;
            g.drawString(font, fluidText, cursorX, 0, STAT_COLOR, false);
            CreateStylePanel.drawMeter(g, cursorX, 10, fw, fluidFill, FLUID_METER);
            cursorX += fw;
        }

        int sepTankX = cursorX + STAT_GAP / 2;
        g.fill(sepTankX, 0, sepTankX + 1, 7, STAT_SEPARATOR);
        cursorX += STAT_GAP;

        g.drawString(font, tankText, cursorX, 0, STAT_COLOR, false);
        CreateStylePanel.drawMeter(g, cursorX, 10, tw, airFill, be.hasShaftSocket() ? AIR_METER : AIR_METER_IDLE);

        g.pose().popPose();
    }

    private void drawSelectorRow(GuiGraphics g, int cl, int cr, int rowY, ItemStack icon, boolean dirty) {
        CreateStylePanel.drawSlot(g, cl, rowY);
        g.renderItem(icon, cl + 1, rowY + 1);
        CreateStylePanel.drawSlotArrow(g, cl + 19, rowY);
        int fieldX = cl + 23;
        CreateStylePanel.drawField(g, fieldX, rowY, cr - fieldX);
        if (dirty) {
            // Onaylanmamış değişiklik: değer alanının sol kenarında 1px kehribar vurgu
            g.fill(fieldX, rowY + 1, fieldX + 1, rowY + 17, PENDING_ACCENT);
        }
    }

    /**
     * Kontrol listesi satırı. {@code alert} satırları gömme uyarı şeridiyle çizilir; {@code extraTooltip}
     * doluysa imleç satırın üzerindeyken Create tarzı çok satırlı açıklama gösterilir.
     */
    private record CheckLine(ItemStack icon, Component text, boolean ok, boolean alert, List<Component> extraTooltip) {
        CheckLine(ItemStack icon, Component text, boolean ok) {
            this(icon, text, ok, false, List.of());
        }
    }

    private void renderChecklist(GuiGraphics g, int cl, int cr, int top, int mouseX, int mouseY) {
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        if (cachedReport == null || reportAge >= REPORT_REFRESH_TICKS) {
            cachedReport = AirshipAssemblyValidator.inspectAtDock(minecraft.level, dockPos);
            reportAge = 0;
        }
        List<CheckLine> lines = buildChecklist(cachedReport);
        // İpucu, tüm satırlar ve gösterge lambaları çizildikten sonra en üst katmanda çizilir
        List<FormattedCharSequence> pendingTooltip = null;

        for (int i = 0; i < lines.size(); i++) {
            CheckLine line = lines.get(i);
            int rowY = top + i * CHECK_ROW_H;
            if (line.alert()) {
                drawAlertStrip(g, cl, cr, rowY);
            }
            g.renderItem(line.icon(), cl + 1, rowY + 1);
            Component text = line.text();
            int maxW = cr - 22 - (cl + 22);
            boolean wasClipped = font.width(text) > maxW;
            String display = wasClipped
                    ? font.plainSubstrByWidth(text.getString(), Math.max(0, maxW - font.width("..."))) + "..."
                    : text.getString();
            g.drawString(font, display, cl + 22, rowY + 5, line.ok() ? 0xFFFFFF : 0xFFFF8A80, true);
            (line.ok() ? AllGuiTextures.INDICATOR_GREEN : AllGuiTextures.INDICATOR_RED).render(g, cr - 18, rowY + 6);

            boolean hovered = mouseX >= cl && mouseX < cr && mouseY >= rowY && mouseY < rowY + CHECK_ROW_H;
            if (!hovered) {
                continue;
            }
            if (!line.extraTooltip().isEmpty()) {
                // Uzun ipucu satırı tek satıra yayılmasın: Create tooltip genişliğinde sarılır
                List<FormattedCharSequence> wrapped = new ArrayList<>();
                for (Component tip : line.extraTooltip()) {
                    wrapped.addAll(font.split(tip, ALERT_TOOLTIP_WIDTH));
                }
                pendingTooltip = wrapped;
            } else if (wasClipped) {
                // Kırpılan satırların tamamı imleçle gösterilir
                pendingTooltip = font.split(text, ALERT_TOOLTIP_WIDTH);
            }
        }

        if (pendingTooltip != null && !pendingTooltip.isEmpty()) {
            g.renderTooltip(font, pendingTooltip, mouseX, mouseY);
        }
    }

    /** Create koyu gövdesine gömülü uyarı şeridi: yarı saydam dolgu, sol vurgu çizgisi, üst ışık / alt gölge. */
    private static void drawAlertStrip(GuiGraphics g, int cl, int cr, int rowY) {
        g.fill(cl - 1, rowY, cr + 1, rowY + CHECK_ROW_H - 1, ALERT_FILL);
        g.fill(cl - 2, rowY + 1, cl, rowY + CHECK_ROW_H - 2, ALERT_ACCENT);
        g.fill(cl - 1, rowY, cr + 1, rowY + 1, ALERT_TOP_EDGE);
        g.fill(cl - 1, rowY + CHECK_ROW_H - 2, cr + 1, rowY + CHECK_ROW_H - 1, ALERT_BOTTOM_SHADE);
    }

    private List<CheckLine> buildChecklist(DiagnosticReport r) {
        List<CheckLine> lines = new ArrayList<>();
        String c = KEY + "check.";

        // Gemi yanaşıkken blokları AirshipEntity'ye taşındığından dünya taraması boş döner;
        // bu durumda sunucunun senkronladığı gerçek gemi değerleri kullanılır.
        SkyDockBlockEntity be = getDockBE();
        boolean docked = be != null && be.isShipPressureKnown();
        if (docked) {
            lines.add(new CheckLine(new ItemStack(ModBlocks.SHIP_PORT.get()),
                    Component.translatable(c + "port_docked"), true));
            lines.add(new CheckLine(AllItems.SUPER_GLUE.asStack(),
                    Component.translatable(c + "glue_ok", Math.max(2, be.getShipBlockCount())), true));
            lines.add(new CheckLine(new ItemStack(ModBlocks.PRESSURE_ENGINE.get()),
                    Component.translatable(c + "prop_ok", String.format(Locale.ROOT, "%.1f", be.getShipCruiseSpeed() * 20.0f)), true));
            lines.add(buildRouteLine(r, c, true));
            lines.add(new CheckLine(new ItemStack(ModBlocks.CANVAS_BALLOON.get()),
                    Component.translatable(c + "lift_ok", be.getShipLift(), be.getShipWeight()), true));
            lines.add(buildPressureLine(r, c));
            return lines;
        }

        lines.add(new CheckLine(new ItemStack(ModBlocks.SHIP_PORT.get()),
                Component.translatable(c + (r.hasShipPort() ? "port_ok" : "port_missing")), r.hasShipPort()));

        Component glueText;
        boolean glueOk;
        if (r.unmovableBlock() != null) {
            glueText = Component.translatable(c + "glue_unmovable", r.unmovableBlock().getX(), r.unmovableBlock().getY(), r.unmovableBlock().getZ());
            glueOk = false;
        } else if (r.blockCount() > AirshipAssemblyValidator.getEffectiveMaxShipBlocks()) {
            glueText = Component.translatable(c + "glue_too_large", r.blockCount(), AirshipAssemblyValidator.getEffectiveMaxShipBlocks());
            glueOk = false;
        } else {
            glueText = r.hasSuperGlue()
                    ? Component.translatable(c + "glue_ok", r.blockCount())
                    : Component.translatable(c + "glue_missing");
            glueOk = r.hasSuperGlue();
        }
        lines.add(new CheckLine(AllItems.SUPER_GLUE.asStack(), glueText, glueOk));

        boolean propOk = r.pathfinderCount() == 1 && r.propellerClear() && r.engineMounted();
        Component propText;
        if (r.pathfinderCount() != 1) {
            propText = Component.translatable(c + "prop_count", r.pathfinderCount());
        } else if (!r.propellerClear()) {
            propText = Component.translatable(c + "prop_blocked");
        } else if (!r.engineMounted()) {
            propText = Component.translatable(c + "prop_engine");
        } else {
            propText = Component.translatable(c + (r.isHeavy() ? "prop_ok_heavy" : "prop_ok"),
                    String.format(Locale.ROOT, "%.1f", r.cruiseSpeed() * 20.0));
        }
        lines.add(new CheckLine(new ItemStack(propOk ? ModBlocks.PRESSURE_ENGINE.get() : ModBlocks.PATHFINDER_PROPELLER.get()),
                propText, propOk));

        boolean routeOk = (be != null && (be.getRouteObstaclePos() != null || be.getMissingDestinationDockPos() != null))
                || r.hasValidRoute() || playerHoldsLinkedChart();
        lines.add(buildRouteLine(r, c, routeOk));

        boolean liftOk = r.hasShipPort() && r.hasSuperGlue() && r.hasEnoughLift();
        Component liftText;
        ItemStack liftIcon = new ItemStack(ModBlocks.CANVAS_BALLOON.get());
        if (!r.balloonsProperlyPlaced()) {
            liftText = Component.translatable(c + "lift_dome");
        } else if (!r.hasActiveHeat()) {
            liftText = Component.translatable(c + "lift_heat");
            liftIcon = new ItemStack(Blocks.CAMPFIRE);
        } else if (r.totalLift() < r.totalWeight()) {
            liftText = Component.translatable(c + "lift_low", r.totalLift(), r.totalWeight());
        } else {
            liftText = Component.translatable(c + "lift_ok", r.totalLift(), r.totalWeight());
        }
        lines.add(new CheckLine(liftIcon, liftText, liftOk));
        lines.add(buildPressureLine(r, c));
        return lines;
    }

    /**
     * Rota satırı. Gemi yanaşıksa sunucunun senkronladığı koridor engeli / eksik varış limanı,
     * değilse kurulum tahmini kullanılır. Koridor kapalıysa veya hedef liman kırılmışsa satır uyarı
     * şeridiyle vurgulanır ve imleçte koordinat + çözüm gösterilir.
     */
    private CheckLine buildRouteLine(DiagnosticReport r, String c, boolean routeOk) {
        ItemStack icon = new ItemStack(ModItems.FLIGHT_CHART.get());
        BlockPos missingDock = findMissingDestinationDock(r);
        if (missingDock != null) {
            List<Component> tooltip = List.of(
                    Component.translatable(c + "route_dock_missing_title").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    Component.translatable(c + "route_dock_missing_coord", missingDock.getX(), missingDock.getY(), missingDock.getZ())
                            .withStyle(ChatFormatting.GOLD),
                    Component.translatable(c + "route_dock_missing_hint").withStyle(ChatFormatting.GRAY)
            );
            return new CheckLine(icon,
                    Component.translatable(c + "route_dock_missing", missingDock.getX(), missingDock.getY(), missingDock.getZ()),
                    false, true, tooltip);
        }
        if (!routeOk) {
            return new CheckLine(icon, Component.translatable(c + "route_missing"), false);
        }
        SkyDockBlockEntity be = getDockBE();
        BlockPos obstacle = (be != null && be.getRouteObstaclePos() != null) ? be.getRouteObstaclePos() : r.routeObstacle();
        if (obstacle == null) {
            return new CheckLine(icon, Component.translatable(c + "route_ok"), true);
        }
        List<Component> tooltip = List.of(
                Component.translatable(c + "route_blocked_title").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                Component.translatable(c + "route_blocked_coord", obstacle.getX(), obstacle.getY(), obstacle.getZ())
                        .withStyle(ChatFormatting.GOLD),
                Component.translatable(c + "route_blocked_hint").withStyle(ChatFormatting.GRAY)
        );
        return new CheckLine(icon,
                Component.translatable(c + "route_blocked", obstacle.getX(), obstacle.getY(), obstacle.getZ()),
                false, true, tooltip);
    }

    @Nullable
    private BlockPos findMissingDestinationDock(DiagnosticReport r) {
        SkyDockBlockEntity be = getDockBE();
        if (be != null && be.getMissingDestinationDockPos() != null) {
            return be.getMissingDestinationDockPos();
        }
        if (minecraft != null && minecraft.level != null) {
            if (r != null && r.isDestinationDockMissing(minecraft.level)) {
                return r.destinationDock();
            }
            if ((r == null || !r.hasValidRoute()) && minecraft.player != null) {
                for (ItemStack stack : new ItemStack[]{minecraft.player.getMainHandItem(), minecraft.player.getOffhandItem()}) {
                    if (stack.getItem() instanceof FlightChartItem) {
                        FlightRouteData data = stack.getOrDefault(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY);
                        BlockPos missing = AirshipAssemblyValidator.findMissingDestinationDock(minecraft.level, dockPos, data);
                        if (missing != null) {
                            return missing;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Basınç satırı. Gemi yanaşıksa sunucunun senkronladığı gerçek havuz değerleri, değilse kurulum tahmini
     * (motor + backtank havası + liman tankı) gösterilir. Varış limanının güç durumu istemcide yüklü değilse
     * en kötü durum (dönüş bacağı dahil) varsayılır.
     */
    private CheckLine buildPressureLine(DiagnosticReport r, String c) {
        ItemStack icon = ModBlocks.PRESSURE_TANK.asStack();
        SkyDockBlockEntity be = getDockBE();
        if (be != null && be.isShipPressureKnown()) {
            int air = be.getShipAir();
            int capacity = be.getShipAirCapacity();
            int required = be.getShipAirRequired();
            if (capacity < required) {
                return new CheckLine(icon, Component.translatable(c + "air_range", capacity, required), false);
            }
            return new CheckLine(icon, Component.translatable(c + (air >= required ? "air_ok" : "air_filling"), air, required),
                    air >= required);
        }
        if (!r.hasShipPort() || r.air().mountedEngines() == 0) {
            return new CheckLine(icon, Component.translatable(c + "air_no_engine"), false);
        }
        if (r.routeLength() <= 0.0) {
            return new CheckLine(icon, Component.translatable(c + "air_no_route", r.air().capacity()), false);
        }
        int required = r.requiredAir(isDestinationPoweredClient(r));
        int available = r.air().storedAir() + (be != null ? Math.min(be.getAir(), r.air().capacity() - r.air().storedAir()) : 0);
        if (r.air().capacity() < required) {
            return new CheckLine(icon, Component.translatable(c + "air_range", r.air().capacity(), required), false);
        }
        return new CheckLine(icon, Component.translatable(c + (available >= required ? "air_ok" : "air_low"), available, required),
                available >= required);
    }

    private boolean isDestinationPoweredClient(DiagnosticReport r) {
        if (minecraft == null || minecraft.level == null || r.destinationDock() == null) {
            return false;
        }
        BlockPos dest = r.destinationDock();
        return minecraft.level.isLoaded(dest)
                && minecraft.level.getBlockEntity(dest) instanceof SkyDockBlockEntity destBe
                && destBe.getSpeed() != 0.0f;
    }

    private boolean playerHoldsLinkedChart() {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return false;
        }
        for (ItemStack stack : new ItemStack[]{minecraft.player.getMainHandItem(), minecraft.player.getOffhandItem()}) {
            if (stack.getItem() instanceof FlightChartItem) {
                FlightRouteData data = stack.getOrDefault(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY);
                if (AirshipAssemblyValidator.isChartValidForDock(minecraft.level, dockPos, data)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        // Oyuncu limandan 8 bloktan fazla uzaklaşırsa menü kapanır
        if (minecraft != null && minecraft.player != null
                && minecraft.player.distanceToSqr(dockPos.getX() + 0.5, dockPos.getY() + 0.5, dockPos.getZ() + 0.5) > 64.0) {
            onClose();
            return;
        }
        reportAge++;
        SkyDockBlockEntity be = getDockBE();
        if (be == null) {
            onClose();
            return;
        }
        // Seçiciler taslak tutar (sunucu değerine geri senkronlanmaz); bekleyen değişiklik varsa onay yeşil yanar
        if (confirmButton != null) {
            confirmButton.green = hasPendingChanges();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (nameBox != null && filterBox != null) {
            int cl = contentLeft();
            int cr = contentRight();
            int mid = (cl + cr) / 2;
            int configY = bodyTop() + CONFIG_ROW_Y;
            boolean inNameField = mouseX >= cl + 23 && mouseX < mid - 3 && mouseY >= configY && mouseY < configY + 18;
            boolean inFilterField = mouseX >= mid + 26 && mouseX < cr && mouseY >= configY && mouseY < configY + 18;

            if (!inNameField && nameBox.isFocused()) {
                nameBox.setFocused(false);
            }
            if (!inFilterField && filterBox.isFocused()) {
                filterBox.setFocused(false);
            }
            if (button == 1) {
                if (inNameField) {
                    nameBox.setValue("");
                    setFocused(nameBox);
                    nameBox.setFocused(true);
                    return true;
                }
                if (inFilterField) {
                    filterBox.setValue("");
                    setFocused(filterBox);
                    filterBox.setFocused(true);
                    return true;
                }
            }
            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            if (button == 0) {
                if (inNameField && !nameBox.isFocused()) {
                    setFocused(nameBox);
                    nameBox.setFocused(true);
                    return true;
                }
                if (inFilterField && !filterBox.isFocused()) {
                    setFocused(filterBox);
                    filterBox.setFocused(true);
                    return true;
                }
            }
            return handled;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 258 && nameBox != null && filterBox != null) {
            EditBox target;
            if (nameBox.isFocused()) {
                nameBox.setFocused(false);
                target = filterBox;
            } else if (filterBox.isFocused()) {
                filterBox.setFocused(false);
                target = nameBox;
            } else {
                target = (modifiers & 1) != 0 ? filterBox : nameBox;
            }
            setFocused(target);
            target.setFocused(true);
            return true;
        }
        EditBox activeBox = (nameBox != null && nameBox.isFocused()) ? nameBox
                : (filterBox != null && filterBox.isFocused()) ? filterBox : null;
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

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new SkyDockScreen(pos));
    }
}
