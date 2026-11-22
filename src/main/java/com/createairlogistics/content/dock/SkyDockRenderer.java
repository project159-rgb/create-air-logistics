package com.createairlogistics.content.dock;

import com.createairlogistics.client.ModPartialModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Liman Bağlantı Köprüsü (Sky Dock Connector Renderer)
 *
 * Create'in Portable Storage Interface parçalarını (AllPartialModels.PORTABLE_STORAGE_INTERFACE_*) kullanır:
 * gemi yanaştığında PSI kafası limanın ağzından çıkıp 1 blokluk boşluğu aşarak ShipPort soketine kenetlenir.
 * Kol 3 kademeli teleskoptur (genişletilmiş kovan -> iç kademe -> kafa); kademeler iç içe kayar.
 * Kilitliyken iç kademe, Liman Vinci Redstone sinyaliyle kilitlendiğinde ise dış kovan "powered" (kırmızı ışıklı) dokuya geçer.
 */
public class SkyDockRenderer extends SafeBlockEntityRenderer<SkyDockBlockEntity> {

    /*
     * Teleskop geometrisi — piksel, liman-yerel uzay (ön yüz -Z; ShipPort ağzı z = -16).
     * Kesitler hiçbir statik yüzle eş-düzlemli değildir (z-fighting yok):
     *   SkyDock yakası 0.75..15.25 | kovan 1.25..14.75 | iç kademe 2..14 | plaka 1..15 | çekirdek 3..13
     *   ShipPort cebi 0.75..15.25, taban yerel z=1.45 (dünya -17.45) -> plaka 0.05 px boşlukla oturur.
     * Değerler her kademenin ÖN yüzünün dünya z'sidir; lerp doğrusal olduğundan uç değerlerdeki
     * örtüşmeler (kovan↔iç kademe↔çekirdek) tüm hareket boyunca korunur, aralık açılmaz.
     */
    private static final float MIDDLE_FRONT_Y = 7.0f;        // block_middle: y 0..9 -> ön yüz z = 7 - öteleme
    private static final float SLEEVE_SCALE = 13.5f / 12.0f; // 2..14 -> 1.25..14.75
    private static final float SLEEVE_FRONT_REST = 2.6f;
    private static final float SLEEVE_FRONT_LOCK = -5.5f;    // arka uç +3.5: daima liman içinde
    private static final float INNER_FRONT_REST = 2.2f;
    private static final float INNER_FRONT_LOCK = -9.6f;     // çekirdek arka ucu (-8.4) ile 1.2 px örtüşme
    private static final float PLATE_FRONT_REST = -0.05f;    // z=0 ön düzlemi ve gövde yan yüzleriyle çakışmaz
    private static final float PLATE_FRONT_LOCK = -17.4f;    // ShipPort cebinin içinde

    public SkyDockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public AABB getRenderBoundingBox(SkyDockBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(2.0);
    }

    @Override
    protected void renderSafe(SkyDockBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        renderShaftSocket(be, ms, buffer);

        float extension = be.getCraneExtension(partialTicks);
        if (extension <= 0.005f) {
            return;
        }

        // Create PSI hissi: yumuşak kalkış/duruş (smoothstep)
        float eased = extension * extension * (3.0f - 2.0f * extension);

        BlockState state = be.getBlockState();
        Direction facing = state.hasProperty(SkyDockBlock.FACING)
                ? state.getValue(SkyDockBlock.FACING)
                : Direction.NORTH;

        // Opak liman bloğunun içi karanlıktır; köprünün bulunduğu boşluk hücresinin ışığını kullan
        BlockPos gapPos = be.getBlockPos().relative(facing);
        int bridgeLight = be.getLevel() != null ? LevelRenderer.getLightColor(be.getLevel(), gapPos) : light;

        boolean redstonePowered = be.getLevel() != null && be.getLevel().hasNeighborSignal(be.getBlockPos());
        PartialModel sleeve = redstonePowered
                ? AllPartialModels.PORTABLE_STORAGE_INTERFACE_MIDDLE_POWERED
                : AllPartialModels.PORTABLE_STORAGE_INTERFACE_MIDDLE;

        boolean locked = extension >= 0.99f;
        PartialModel middle = locked
                ? AllPartialModels.PORTABLE_STORAGE_INTERFACE_MIDDLE_POWERED
                : AllPartialModels.PORTABLE_STORAGE_INTERFACE_MIDDLE;

        VertexConsumer consumer = buffer.getBuffer(RenderType.cutout());

        ms.pushPose();
        ms.translate(0.5f, 0.5f, 0.5f);
        ModPartialModels.rotateNorthTo(ms, facing);

        // PSI modelleri +Y yönüne uzanır; +Y'yi limanın ön yüzüne (kuzey modeli için -Z) çevir.
        // Bu uzayda: dünya_z(px) = 16 - model_y(px) - öteleme(px)
        ms.pushPose();
        ms.mulPose(Axis.XP.rotationDegrees(-90.0f));
        ms.translate(-0.5f, -0.5f, -0.5f);

        // 1) Dış kovan: limanın içinde köklü kalır, yakadan (0.75..15.25) 0.5 px boşlukla çıkar
        float sleeveFront = Mth.lerp(eased, SLEEVE_FRONT_REST, SLEEVE_FRONT_LOCK);
        ms.pushPose();
        ms.translate(0.5f, (MIDDLE_FRONT_Y - sleeveFront) / 16.0f, 0.5f);
        ms.scale(SLEEVE_SCALE, 1.0f, SLEEVE_SCALE);
        ms.translate(-0.5f, 0.0f, -0.5f);
        ModPartialModels.render(sleeve, state, ms, consumer,
                RenderType.cutout(), bridgeLight, overlay);
        ms.popPose();

        // 2) İç kademe (kilitliyken kırmızı ışıklı): kovanın içinden kayar, kafa çekirdeğini yutar
        float innerFront = Mth.lerp(eased, INNER_FRONT_REST, INNER_FRONT_LOCK);
        ms.pushPose();
        ms.translate(0.0f, (MIDDLE_FRONT_Y - innerFront) / 16.0f, 0.0f);
        ModPartialModels.render(middle, state, ms, consumer, RenderType.cutout(), bridgeLight, overlay);
        ms.popPose();

        // 3) Kafa: plaka ShipPort cebine girer, dişler cebin dudağını kavrar
        float plateFront = Mth.lerp(eased, PLATE_FRONT_REST, PLATE_FRONT_LOCK);
        ms.pushPose();
        ms.translate(0.0f, -plateFront / 16.0f, 0.0f);
        ModPartialModels.render(AllPartialModels.PORTABLE_STORAGE_INTERFACE_TOP, state, ms, consumer,
                RenderType.cutout(), bridgeLight, overlay);
        ms.popPose();

        ms.popPose();
        ms.popPose();
    }

    /**
     * Wrench ile açılan soketin içindeki yarım Create şaftı (SHAFT_HALF) ve daha geniş döner kavrama göbeği
     * (DOCK_SHAFT_HUB). 8x8 açık yuva sayesinde dışarıdan standart 4x4 şaft bağlandığında bile içerideki
     * kamalı pirinç/andezit göbek ve şaftın dönüşü net biçimde görünür; öteleme yapılmadığı için komşu
     * şaftla z-fighting oluşmaz.
     */
    private static void renderShaftSocket(SkyDockBlockEntity be, PoseStack ms, MultiBufferSource buffer) {
        BlockState state = be.getBlockState();
        if (!state.hasProperty(SkyDockBlock.SHAFT)) {
            return;
        }
        Direction socket = state.getValue(SkyDockBlock.SHAFT).direction();
        if (socket == null || be.getLevel() == null) {
            return;
        }
        int socketLight = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().relative(socket));
        VertexConsumer solid = buffer.getBuffer(RenderType.solid());

        SuperByteBuffer shaftHalf = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, socket);
        KineticBlockEntityRenderer.standardKineticRotationTransform(shaftHalf, be, socketLight)
                .renderInto(ms, solid);

        SuperByteBuffer shaftHub = CachedBuffers.partialFacing(ModPartialModels.DOCK_SHAFT_HUB, state, socket);
        KineticBlockEntityRenderer.standardKineticRotationTransform(shaftHub, be, socketLight)
                .renderInto(ms, solid);
    }
}
