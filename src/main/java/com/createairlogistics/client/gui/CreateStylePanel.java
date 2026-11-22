package com.createairlogistics.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Create'in kendi Display Link arayüz dokusunu ({@code create:textures/gui/display_link.png}) dilimleyip
 * istenen genişlik/yükseklikte döşeyerek çizen panel çizici.
 *
 * <p>Doku kopyalanmaz; doğrudan Create'in kaynağından okunur, böylece kaynak paketleri (resource pack)
 * Create arayüzünü değiştirirse bu paneller de otomatik uyum sağlar.
 *
 * <pre>
 *  ┌───────────── pirinç başlık (15px) ─────────────┐
 *  │ ahşap ray │      koyu gri gövde paneli    │ ray │  (gövde, döşenir)
 *  └──────── açık gri alt bant (30px) + onay ───────┘▶
 * </pre>
 */
public final class CreateStylePanel {

    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("create", "textures/gui/display_link.png");

    /** Dokudaki başlık bandının dış genişliği (sağ gölge dahil). */
    private static final int TEX_WIDTH = 227;

    public static final int HEADER_HEIGHT = 15;
    public static final int FOOTER_HEIGHT = 30;
    /** Gövdenin ahşap ray süslemeleri (üst/alt boru başlıkları) için gereken minimum yükseklik. */
    public static final int MIN_BODY_HEIGHT = 57;

    /** Gövde içindeki koyu panelin, panel sol kenarına göre başlangıcı. */
    public static final int CONTENT_LEFT = 25;
    /** Gövde içindeki koyu panelin, panel sağ kenarına göre bitişi. */
    public static final int CONTENT_RIGHT_INSET = 16;
    /** Alt banttaki onay (kapat) butonunun panel sağ kenarına göre X ofseti. */
    public static final int CONFIRM_X_FROM_RIGHT = 25;
    /** Alt banttaki onay butonunun alt bant üstüne göre Y ofseti. */
    public static final int CONFIRM_Y_IN_FOOTER = 6;

    // Create palet renkleri (display_link.png'den ölçüldü)
    public static final int PANEL_OUTER = 0xFF6F6F6F;
    public static final int PANEL_SHADOW = 0xFF393939;
    public static final int PANEL_FILL = 0xFF575757;
    public static final int FIELD_SHADOW = 0xFF373737;
    public static final int FIELD_FILL = 0xFF8B8B8B;
    public static final int FIELD_HIGHLIGHT = 0xFFA3A3A3;
    public static final int FIELD_EDGE = 0xFFFFFFFF;
    /** Açık gri alt bant üzerindeki gömme yuva gölgesi. */
    public static final int SOCKET_SHADOW = 0xFF7A7A7A;
    /** Doluluk çubuğunun boş iz rengi. */
    public static final int METER_TRACK = 0x40000000;
    /** Pirinç başlık üzerindeki başlık yazısı rengi (Create ekranlarıyla aynı). */
    public static final int TITLE_COLOR = 0x592424;

    private CreateStylePanel() {
    }

    public static int totalHeight(int bodyHeight) {
        return HEADER_HEIGHT + Math.max(MIN_BODY_HEIGHT, bodyHeight) + FOOTER_HEIGHT;
    }

    public static int footerTop(int y, int bodyHeight) {
        return y + HEADER_HEIGHT + Math.max(MIN_BODY_HEIGHT, bodyHeight);
    }

    /** Tam paneli çizer: başlık + gövde + alt bant (onay butonu çerçevesi dahil). */
    public static void render(GuiGraphics g, int x, int y, int width, int bodyHeight) {
        int bh = Math.max(MIN_BODY_HEIGHT, bodyHeight);

        // 1) Pirinç başlık
        int headerRightU = 200;
        int headerRightW = TEX_WIDTH - headerRightU;
        g.blit(TEXTURE, x, y, 0, 0, 16, HEADER_HEIGHT);
        tileHorizontal(g, x + 16, y, width - 16 - headerRightW, 16, 0, headerRightU - 16, HEADER_HEIGHT);
        g.blit(TEXTURE, x + width - headerRightW, y, headerRightU, 0, headerRightW, HEADER_HEIGHT);

        // 2) Gövde: ahşap raylar (üst boru başlığı / döşenen orta / alt boru başlığı)
        int bodyY = y + HEADER_HEIGHT;
        int leftW = CONTENT_LEFT;
        int rightU = 211;
        int rightW = TEX_WIDTH - rightU;
        drawRail(g, x, bodyY, 0, leftW, bh);
        drawRail(g, x + width - rightW, bodyY, rightU, rightW, bh);

        // Koyu iç panel (Create çift çerçevesi: dış 6F, iç gölge 39, dolgu 57)
        int px0 = x + CONTENT_LEFT;
        int px1 = x + width - CONTENT_RIGHT_INSET;
        g.fill(px0, bodyY, px1, bodyY + bh, PANEL_OUTER);
        g.fill(px0 + 1, bodyY + 1, px1 - 1, bodyY + bh - 1, PANEL_SHADOW);
        g.fill(px0 + 2, bodyY + 2, px1 - 1, bodyY + bh - 2, PANEL_FILL);

        // 3) Açık gri alt bant + sağda onay çerçevesi ve ok ucu
        int footerY = bodyY + bh;
        int footerRightU = 190;
        int footerRightW = 234 - footerRightU;
        int footerRightX = x + width - TEX_WIDTH + footerRightU;
        g.blit(TEXTURE, x, footerY, 0, 132, 20, FOOTER_HEIGHT);
        tileHorizontal(g, x + 20, footerY, footerRightX - (x + 20), 20, 132, footerRightU - 20, FOOTER_HEIGHT);
        g.blit(TEXTURE, footerRightX, footerY, footerRightU, 132, footerRightW, FOOTER_HEIGHT);
    }

    private static void drawRail(GuiGraphics g, int x, int y, int u, int w, int height) {
        int topV = 15, topH = 30;      // üst boru başlığı
        int midV = 45, midH = 60;      // düz boru (döşenir)
        int botV = 105, botH = 27;     // alt boru başlığı
        g.blit(TEXTURE, x, y, u, topV, w, topH);
        int remaining = height - topH - botH;
        int cy = y + topH;
        while (remaining > 0) {
            int h = Math.min(midH, remaining);
            g.blit(TEXTURE, x, cy, u, midV, w, h);
            cy += h;
            remaining -= h;
        }
        g.blit(TEXTURE, x, y + height - botH, u, botV, w, botH);
    }

    private static void tileHorizontal(GuiGraphics g, int x, int y, int width, int u, int v, int segmentW, int h) {
        int drawn = 0;
        while (drawn < width) {
            int w = Math.min(segmentW, width - drawn);
            g.blit(TEXTURE, x + drawn, y, u, v, w, h);
            drawn += w;
        }
    }

    /** Create'in gömme (recessed) değer alanı: 18px yüksek, üst/sol gölge, alt/sağ beyaz kenar. */
    public static void drawField(GuiGraphics g, int x, int y, int width) {
        int h = 18;
        g.fill(x, y, x + width, y + h, FIELD_FILL);
        g.fill(x, y, x + width, y + 1, FIELD_SHADOW);
        g.fill(x, y, x + 1, y + h, FIELD_SHADOW);
        g.fill(x + 1, y + 2, x + width - 1, y + 4, FIELD_HIGHLIGHT);
        g.fill(x + 1, y + 14, x + width - 1, y + 16, FIELD_HIGHLIGHT);
        g.fill(x, y + h - 1, x + width, y + h, FIELD_EDGE);
        g.fill(x + width - 1, y, x + width, y + h, FIELD_EDGE);
    }

    /** Create'in eşya/ikon yuvası: 18x18 gömme kare. */
    public static void drawSlot(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, FIELD_FILL);
        g.fill(x, y, x + 18, y + 1, FIELD_SHADOW);
        g.fill(x, y, x + 1, y + 18, FIELD_SHADOW);
        g.fill(x, y + 17, x + 18, y + 18, FIELD_EDGE);
        g.fill(x + 17, y, x + 18, y + 18, FIELD_EDGE);
    }

    /** Yuva ile alan arasındaki küçük "◀" bağlantı oku (Display Link'teki gibi). */
    public static void drawSlotArrow(GuiGraphics g, int x, int y) {
        for (int i = 0; i < 4; i++) {
            g.fill(x + i, y + 9 - i, x + i + 1, y + 9 + i + 1, FIELD_FILL);
        }
        g.fill(x, y + 9, x + 1, y + 10, FIELD_EDGE);
    }

    /**
     * Açık gri alt bantta 18x18'lik bir butonu çevreleyen 1px gömme yuva (üst/sol gölge, alt/sağ beyaz kenar).
     * (x, y) butonun kendisinin sol üst köşesidir; yuva bir piksel dışına çizilir.
     */
    public static void drawButtonSocket(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 19, y, SOCKET_SHADOW);
        g.fill(x - 1, y - 1, x, y + 19, SOCKET_SHADOW);
        g.fill(x - 1, y + 18, x + 19, y + 19, FIELD_EDGE);
        g.fill(x + 18, y - 1, x + 19, y + 19, FIELD_EDGE);
    }

    /** 2px yüksek doluluk çubuğu: hafif gömme iz + üstte renk, altta koyulaştırılmış ton. */
    public static void drawMeter(GuiGraphics g, int x, int y, int width, float fraction, int color) {
        g.fill(x, y, x + width, y + 2, METER_TRACK);
        int filled = Math.round(width * Math.max(0f, Math.min(1f, fraction)));
        if (filled > 0) {
            g.fill(x, y, x + filled, y + 1, color);
            g.fill(x, y + 1, x + filled, y + 2, darken(color));
        }
    }

    private static int darken(int argb) {
        int r = ((argb >> 16) & 0xFF) * 3 / 4;
        int gr = ((argb >> 8) & 0xFF) * 3 / 4;
        int b = (argb & 0xFF) * 3 / 4;
        return (argb & 0xFF000000) | (r << 16) | (gr << 8) | b;
    }

    /** Gövde içinde yatay ayırıcı (gölge + parlak çizgi). */
    public static void drawDivider(GuiGraphics g, int x0, int x1, int y) {
        g.fill(x0, y, x1, y + 1, PANEL_SHADOW);
        g.fill(x0, y + 1, x1, y + 2, PANEL_OUTER);
    }
}
