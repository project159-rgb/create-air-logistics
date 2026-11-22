package com.createairlogistics.content.navigation;

import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerBlock;
import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerBlockEntity;
import com.createairlogistics.content.dock.SkyDockBlock;
import com.createairlogistics.registry.ModDataComponents;
import com.createairlogistics.registry.ModDataComponents.FlightRouteData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Gökyüzü Hava Koridoru ve 3B Halka Görselleştiricisi (SkyRoute Client Renderer)
 *
 * Oyuncu elinde Uçuş Haritası (FlightChartItem) tutarken veya harita takılı bir
 * Yön Bulucu Pervaneye (Pathfinder Propeller) bakarken:
 * - Liman A (Altın) ve Liman B (Yeşil) kutularını,
 * - Ara İrtifa Noktalarını (Turkuaz 3B Gökyüzü Halkaları + dikey irtifa izdüşüm çizgisi),
 * - Henüz rota çizilirken oyuncunun baktığı bloğun +irtifa üstünde canlı "Önizleme Halkasını",
 * - Tamamlanmış rotada Gidiş (Altın) ve Sağ Şeritli Dönüş (Mavi) 3B Catmull-Rom eğrilerini çizer.
 */
public final class SkyRouteClientRenderer {

    private static final UUID PREVIEW_ID = new UUID(0L, 1L);
    private static final int MAX_PREVIEW_SEGMENTS = 512;
    private static final Vec3 LIFT = new Vec3(0, 0.5, 0);

    @Nullable
    private static ResourceKey<Level> cachedDimension;
    @Nullable
    private static BlockPos cachedDockA;
    @Nullable
    private static BlockPos cachedDockB;
    @Nullable
    private static List<BlockPos> cachedWaypoints;
    @Nullable
    private static Direction cachedFacingA;
    @Nullable
    private static Direction cachedFacingB;
    @Nullable
    private static SkyRoute cachedSpline;
    @Nullable
    private static Vec3[] cachedForwardPoints;
    @Nullable
    private static Vec3[] cachedReversePoints;

    private SkyRouteClientRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }

        FlightRouteData routeData = findActiveRouteData(mc, player);
        if (routeData == null || routeData.dockA().isEmpty()
                || !routeData.dockA().get().dimension().equals(mc.level.dimension())) {
            return;
        }

        boolean holdingChart = player.getMainHandItem().getItem() instanceof FlightChartItem
                || player.getOffhandItem().getItem() instanceof FlightChartItem;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack ms = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());

        ms.pushPose();
        ms.translate(-camPos.x, -camPos.y, -camPos.z);

        // 1. Liman A seçiliyse Altın Kutu çiz (kırılmışsa Kırmızı)
        BlockPos dockAPos = routeData.dockA().get().pos();
        renderDockBox(ms, lineConsumer, mc.level, dockAPos, 1.0f, 0.75f, 0.1f);

        // 2. Ara Noktaları (Waypoints) 3B Halka + Dikey İrtifa Çizgisi olarak çiz
        List<BlockPos> waypoints = routeData.waypoints();
        for (BlockPos wp : waypoints) {
            LevelRenderer.renderLineBox(ms, lineConsumer, new AABB(wp).inflate(0.35), 0.1f, 0.9f, 1.0f, 0.9f);
            // Zemine doğru referans çizgisi (oyuncunun yüksekliği anlaması için)
            drawLine(ms, lineConsumer, Vec3.atCenterOf(wp), Vec3.atCenterOf(wp).subtract(0, routeData.altitudeOffset(), 0),
                    0.1f, 0.8f, 1.0f, 0.45f);
        }

        // 3. Elinde Uçuş Haritası tutarken bir zemin/yapı bloğuna bakıyorsa +irtifa üstünde canlı Önizleme Halkası göster!
        if (holdingChart && waypoints.size() < FlightRouteData.MAX_WAYPOINTS
                && mc.hitResult instanceof BlockHitResult blockHit && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            Block targetBlock = mc.level.getBlockState(blockHit.getBlockPos()).getBlock();
            if (!(targetBlock instanceof SkyDockBlock) && !(targetBlock instanceof PathfinderPropellerBlock)) {
                BlockPos previewWp = blockHit.getBlockPos().above(routeData.altitudeOffset());
                LevelRenderer.renderLineBox(ms, lineConsumer, new AABB(previewWp).inflate(0.4), 1.0f, 1.0f, 0.3f, 0.85f);
                drawLine(ms, lineConsumer, Vec3.atCenterOf(blockHit.getBlockPos()), Vec3.atCenterOf(previewWp),
                        1.0f, 1.0f, 0.3f, 0.55f);

                // Son noktadan önizleme halkasına kesikli bağlantı çizgisi
                BlockPos lastPoint = waypoints.isEmpty() ? dockAPos : waypoints.getLast();
                drawLine(ms, lineConsumer, Vec3.atCenterOf(lastPoint), Vec3.atCenterOf(previewWp),
                        1.0f, 0.85f, 0.2f, 0.7f);
                if (routeData.isComplete()) {
                    drawLine(ms, lineConsumer, Vec3.atCenterOf(previewWp), Vec3.atCenterOf(routeData.dockB().get().pos()),
                            1.0f, 0.85f, 0.2f, 0.45f);
                }
            }
        }

        // 4. Rota tamamlanmışsa: Liman B kutusunu ve Gidiş/Dönüş 3B Catmull-Rom Spline koridorunu çiz!
        if (routeData.isComplete()) {
            BlockPos dockBPos = routeData.dockB().get().pos();
            renderDockBox(ms, lineConsumer, mc.level, dockBPos, 0.2f, 1.0f, 0.35f);

            getOrBuildPreviewSpline(mc.level.dimension(), dockAPos, dockBPos, waypoints,
                    dockFacing(mc, dockAPos), dockFacing(mc, dockBPos));
            Vec3[] forward = cachedForwardPoints;
            Vec3[] reverse = cachedReversePoints;
            if (forward != null && reverse != null) {
                for (int i = 1; i < forward.length; i++) {
                    drawLine(ms, lineConsumer, forward[i - 1], forward[i], 1.0f, 0.8f, 0.15f, 0.9f);
                    drawLine(ms, lineConsumer, reverse[i - 1], reverse[i], 0.25f, 0.78f, 1.0f, 0.75f);
                }
            }
        }

        ms.popPose();
        bufferSource.endBatch(RenderType.lines());
    }

    private static void renderDockBox(PoseStack ms, VertexConsumer lineConsumer, Level level, BlockPos pos,
                                      float okR, float okG, float okB) {
        boolean missing = level.isLoaded(pos) && !(level.getBlockState(pos).getBlock() instanceof SkyDockBlock);
        float r = missing ? 1.0f : okR;
        float g = missing ? 0.2f : okG;
        float b = missing ? 0.2f : okB;
        LevelRenderer.renderLineBox(ms, lineConsumer, new AABB(pos).inflate(0.08), r, g, b, 0.95f);
    }

    private static SkyRoute getOrBuildPreviewSpline(ResourceKey<Level> dimension, BlockPos dockA, BlockPos dockB,
                                                    List<BlockPos> waypoints, @Nullable Direction facingA,
                                                    @Nullable Direction facingB) {
        if (cachedSpline != null
                && cachedForwardPoints != null
                && cachedReversePoints != null
                && Objects.equals(cachedDimension, dimension)
                && Objects.equals(cachedDockA, dockA)
                && Objects.equals(cachedDockB, dockB)
                && Objects.equals(cachedWaypoints, waypoints)
                && Objects.equals(cachedFacingA, facingA)
                && Objects.equals(cachedFacingB, facingB)) {
            return cachedSpline;
        }
        cachedDimension = dimension;
        cachedDockA = dockA.immutable();
        cachedDockB = dockB.immutable();
        cachedWaypoints = List.copyOf(waypoints);
        cachedFacingA = facingA;
        cachedFacingB = facingB;
        cachedSpline = new SkyRoute(PREVIEW_ID, cachedDockA, cachedDockB, cachedWaypoints, facingA, facingB);

        int segments = Math.min(MAX_PREVIEW_SEGMENTS,
                Math.max(64, waypoints.size() * 24 + (int) (cachedSpline.getTotalArcLength() / 2.0)));
        Vec3[] forward = new Vec3[segments + 1];
        Vec3[] reverse = new Vec3[segments + 1];
        for (int i = 0; i <= segments; i++) {
            double t = (double) i / segments;
            forward[i] = cachedSpline.getPosition(t, false).add(LIFT);
            reverse[i] = cachedSpline.getPosition(t, true).add(LIFT);
        }
        cachedForwardPoints = forward;
        cachedReversePoints = reverse;
        return cachedSpline;
    }

    public static void clearCache() {
        cachedDimension = null;
        cachedDockA = null;
        cachedDockB = null;
        cachedWaypoints = null;
        cachedFacingA = null;
        cachedFacingB = null;
        cachedSpline = null;
        cachedForwardPoints = null;
        cachedReversePoints = null;
    }

    private static Direction dockFacing(Minecraft mc, BlockPos dockPos) {
        if (mc.level == null || !mc.level.isLoaded(dockPos)) {
            return null;
        }
        BlockState state = mc.level.getBlockState(dockPos);
        return state.hasProperty(SkyDockBlock.FACING)
                ? state.getValue(SkyDockBlock.FACING)
                : null;
    }

    private static FlightRouteData findActiveRouteData(Minecraft mc, Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof FlightChartItem) {
            return main.getOrDefault(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY);
        }
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof FlightChartItem) {
            return off.getOrDefault(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY);
        }
        if (mc.hitResult instanceof BlockHitResult blockHit && mc.hitResult.getType() == HitResult.Type.BLOCK && mc.level != null) {
            if (mc.level.getBlockEntity(blockHit.getBlockPos()) instanceof PathfinderPropellerBlockEntity propBe) {
                FlightRouteData data = propBe.getRouteData();
                if (data.dockA().isPresent()) {
                    return data;
                }
            }
        }
        return null;
    }

    private static void drawLine(PoseStack ms, VertexConsumer consumer, Vec3 from, Vec3 to, float r, float g, float b, float a) {
        PoseStack.Pose pose = ms.last();
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double lenSqr = dx * dx + dy * dy + dz * dz;
        float nx;
        float ny;
        float nz;
        if (lenSqr < 1e-8) {
            nx = 0.0f;
            ny = 1.0f;
            nz = 0.0f;
        } else {
            double invLen = 1.0 / Math.sqrt(lenSqr);
            nx = (float) (dx * invLen);
            ny = (float) (dy * invLen);
            nz = (float) (dz * invLen);
        }
        consumer.addVertex(pose, (float) from.x, (float) from.y, (float) from.z)
                .setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, (float) to.x, (float) to.y, (float) to.z)
                .setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz);
    }
}
