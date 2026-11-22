package com.createairlogistics.content.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Create'in fiziksel raylara bağlı TrackGraph sisteminden bağımsız,
 * iki SkyDock ve aradaki 3B sanal geçiş noktalarından (Waypoints) oluşan hava koridoru.
 *
 * <p>Geometri, doğrudan {@code AirshipEntity} konum uzayında tanımlıdır (ShipPort bloğunun alt-orta noktası):
 * <ul>
 *     <li>Uç noktalar = limanın 2 blok önündeki ShipPort hizası (yanaşma konumuyla birebir aynı; ışınlanma yok).</li>
 *     <li>Her limandan önce dışarı + yukarı "kalkış/iniş bacağı" (UNDOCK) noktası eklenir.</li>
 *     <li>Eğri: centripetal Catmull-Rom (kıvrılma/ilmek yapmaz), yay uzunluğuna göre yeniden parametrelenir;
 *         böylece {@code progress} gerçek mesafeyle doğrusal ilerler ve hız segmentten segmente değişmez.</li>
 * </ul>
 */
public class SkyRoute {

    /** Limandan ayrılırken dikey yükselme (blok). */
    public static final double UNDOCK_RISE = 6.0;
    /** Limandan ayrılırken dışa doğru (liman yönünde) açılma (blok). */
    public static final double UNDOCK_OUT = 2.0;
    /** Doğrudan uçuşta kalkış bacağının üstüne eklenen seyir irtifası (blok). */
    public static final double CRUISE_EXTRA_ALTITUDE = 12.0;
    public static final int MAX_WAYPOINTS = 16;

    private static final double LANE_RAMP_DISTANCE = 12.0;

    // Uçuş koridoru engel taraması
    private static final double CORRIDOR_MIN_LENGTH = 8.0;
    private static final double CORRIDOR_SAMPLE_STEP = 1.5;
    private static final double CORRIDOR_LEG_MARGIN = 2.5;
    /** Liman yapısı / yanaşık gemi bu yatay yarıçap (7 blok) içinde asla engel sayılmaz. */
    private static final double CORRIDOR_DOCK_CLEARANCE_SQR = 49.0;

    private final UUID routeId;
    private final BlockPos dockA;
    private final BlockPos dockB;
    @Nullable
    private final Direction facingA;
    @Nullable
    private final Direction facingB;
    private final List<BlockPos> waypoints;
    private final List<Vec3> controlPoints;

    // Yay uzunluğu tablosu (arc-length LUT)
    private final double[] lutParam;
    private final double[] lutDistance;
    private final double[] controlPointDistance;
    private final double totalArcLength;

    public SkyRoute(UUID routeId, BlockPos dockA, BlockPos dockB, List<BlockPos> waypoints) {
        this(routeId, dockA, dockB, waypoints, null, null);
    }

    public SkyRoute(UUID routeId, BlockPos dockA, BlockPos dockB, List<BlockPos> waypoints,
                    @Nullable Direction facingA, @Nullable Direction facingB) {
        this.routeId = routeId;
        this.dockA = dockA.immutable();
        this.dockB = dockB.immutable();
        this.facingA = horizontalOrNull(facingA);
        this.facingB = horizontalOrNull(facingB);
        this.waypoints = (waypoints == null || waypoints.isEmpty())
                ? List.of()
                : List.copyOf(waypoints.subList(0, Math.min(waypoints.size(), MAX_WAYPOINTS)));
        this.controlPoints = buildControlPoints();

        int segments = controlPoints.size() - 1;
        int samples = Math.max(64, segments * 48);
        this.lutParam = new double[samples + 1];
        this.lutDistance = new double[samples + 1];
        this.controlPointDistance = new double[controlPoints.size()];

        Vec3 prev = sampleParam(0.0);
        double length = 0.0;
        int nextCp = 1;
        for (int i = 0; i <= samples; i++) {
            double g = (double) i / samples * segments;
            Vec3 curr = sampleParam(g);
            if (i > 0) {
                length += curr.distanceTo(prev);
            }
            lutParam[i] = g;
            lutDistance[i] = length;
            while (nextCp < controlPoints.size() && g >= nextCp - 1e-9) {
                controlPointDistance[nextCp] = length;
                nextCp++;
            }
            prev = curr;
        }
        this.totalArcLength = Math.max(1.0, length);
    }

    @Nullable
    private static Direction horizontalOrNull(@Nullable Direction dir) {
        return dir != null && dir.getAxis().isHorizontal() ? dir : null;
    }

    /** Yanaşık geminin {@code AirshipEntity} konumu (ShipPort bloğunun alt-orta noktası). */
    public static Vec3 dockAnchor(BlockPos dock, @Nullable Direction facing) {
        if (facing == null) {
            return new Vec3(dock.getX() + 0.5, dock.getY(), dock.getZ() + 0.5);
        }
        BlockPos port = dock.relative(facing, 2);
        return new Vec3(port.getX() + 0.5, port.getY(), port.getZ() + 0.5);
    }

    private static Vec3 undockPoint(Vec3 anchor, @Nullable Direction facing) {
        Vec3 out = facing == null ? Vec3.ZERO
                : new Vec3(facing.getStepX(), 0, facing.getStepZ()).scale(UNDOCK_OUT);
        return anchor.add(out).add(0, UNDOCK_RISE, 0);
    }

    private List<Vec3> buildControlPoints() {
        List<Vec3> pts = new ArrayList<>();
        Vec3 start = dockAnchor(dockA, facingA);
        Vec3 end = dockAnchor(dockB, facingB);
        Vec3 liftOff = undockPoint(start, facingA);
        Vec3 approach = undockPoint(end, facingB);

        pts.add(start);
        pts.add(liftOff);
        if (waypoints.isEmpty()) {
            double cruiseY = Math.max(liftOff.y, approach.y) + CRUISE_EXTRA_ALTITUDE;
            double horizontal = Math.hypot(approach.x - liftOff.x, approach.z - liftOff.z);
            if (horizontal > 16.0) {
                pts.add(liftOff.lerp(approach, 0.25).with(Direction.Axis.Y, cruiseY));
                pts.add(liftOff.lerp(approach, 0.75).with(Direction.Axis.Y, cruiseY));
            } else {
                pts.add(liftOff.lerp(approach, 0.5).with(Direction.Axis.Y, cruiseY));
            }
        } else {
            for (BlockPos wp : waypoints) {
                pts.add(new Vec3(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5));
            }
        }
        pts.add(approach);
        pts.add(end);
        return pts;
    }

    private Vec3 controlPoint(int i) {
        int n = controlPoints.size();
        if (i < 0) {
            return controlPoints.get(0).scale(2.0).subtract(controlPoints.get(1));
        }
        if (i >= n) {
            return controlPoints.get(n - 1).scale(2.0).subtract(controlPoints.get(n - 2));
        }
        return controlPoints.get(i);
    }

    /** Global parametre g ∈ [0, segmentCount] için centripetal Catmull-Rom noktası. */
    private Vec3 sampleParam(double g) {
        int segments = controlPoints.size() - 1;
        g = Mth.clamp(g, 0.0, segments);
        int seg = Math.min((int) Math.floor(g), segments - 1);
        double u = g - seg;

        Vec3 p0 = controlPoint(seg - 1);
        Vec3 p1 = controlPoint(seg);
        Vec3 p2 = controlPoint(seg + 1);
        Vec3 p3 = controlPoint(seg + 2);

        double t0 = 0.0;
        double t1 = t0 + knot(p0, p1);
        double t2 = t1 + knot(p1, p2);
        double t3 = t2 + knot(p2, p3);
        double t = Mth.lerp(u, t1, t2);

        Vec3 a1 = mix(p0, p1, t0, t1, t);
        Vec3 a2 = mix(p1, p2, t1, t2, t);
        Vec3 a3 = mix(p2, p3, t2, t3, t);
        Vec3 b1 = mix(a1, a2, t0, t2, t);
        Vec3 b2 = mix(a2, a3, t1, t3, t);
        return mix(b1, b2, t1, t2, t);
    }

    private static double knot(Vec3 a, Vec3 b) {
        return Math.max(1e-4, Math.sqrt(a.distanceTo(b)));
    }

    private static Vec3 mix(Vec3 a, Vec3 b, double ta, double tb, double t) {
        double span = tb - ta;
        if (Math.abs(span) < 1e-9) {
            return a;
        }
        double wb = (t - ta) / span;
        return a.scale(1.0 - wb).add(b.scale(wb));
    }

    /** Rota başından itibaren gerçek mesafe (blok) için eğri noktası. */
    private Vec3 positionAtDistance(double distance) {
        double d = Mth.clamp(distance, 0.0, lutDistance[lutDistance.length - 1]);
        int lo = 0;
        int hi = lutDistance.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (lutDistance[mid] < d) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = lutDistance[hi] - lutDistance[lo];
        double f = span < 1e-9 ? 0.0 : (d - lutDistance[lo]) / span;
        return sampleParam(Mth.lerp(f, lutParam[lo], lutParam[hi]));
    }

    private double rawDistance(double progress, boolean reverse) {
        double p = Mth.clamp(progress, 0.0, 1.0);
        return (reverse ? 1.0 - p : p) * totalArcLength;
    }

    /**
     * Gidiş (reverse=false) veya dönüş (reverse=true) bacağında, [0,1] ilerleme için gemi konumu.
     * Dönüş bacağında, karşıdan gelen gemilerle çakışmamak için seyir bölümünde sağ şerit ofseti uygulanır;
     * kalkış/iniş bacaklarında ofset sıfırdır ki gemi limana tam hizalansın.
     */
    public Vec3 getPosition(double progress, boolean reverse) {
        Vec3 base = positionAtDistance(rawDistance(progress, reverse));
        if (!reverse) {
            return base;
        }
        double envelope = laneEnvelope(progress, true);
        if (envelope <= 0.0) {
            return base;
        }
        Vec3 tangent = getTangent(progress, true);
        Vec3 right = new Vec3(-tangent.z, 0, tangent.x);
        if (right.lengthSqr() < 1e-6) {
            return base.add(0, 4.0 * envelope, 0);
        }
        return base.add(right.normalize().scale(3.0 * envelope)).add(0, 4.0 * envelope, 0);
    }

    private double laneEnvelope(double progress, boolean reverse) {
        double travelled = Mth.clamp(progress, 0.0, 1.0) * totalArcLength;
        double fromStart = travelled - legEndSegmentLength(reverse, true);
        double toEnd = (totalArcLength - travelled) - legEndSegmentLength(reverse, false);
        double x = Mth.clamp(Math.min(fromStart, toEnd) / LANE_RAMP_DISTANCE, 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    /** Bacağın ilk (kalkış) veya son (iniş) segmentinin uzunluğu. */
    private double legEndSegmentLength(boolean reverse, boolean departureSide) {
        int n = controlPoints.size();
        double firstSeg = controlPointDistance[1];
        double lastSeg = totalArcLength - controlPointDistance[n - 2];
        boolean useFirst = reverse != departureSide; // ileri: kalkış=ilk, iniş=son
        return useFirst ? firstSeg : lastSeg;
    }

    /** Son iniş (descent) bacağının başladığı ilerleme değeri; hedef liman doluysa gemi burada süzülerek bekler. */
    public double getApproachProgress(boolean reverse) {
        double descent = legEndSegmentLength(reverse, false);
        return Mth.clamp(1.0 - descent / totalArcLength, 0.0, 1.0);
    }

    public Vec3 getTangent(double progress, boolean reverse) {
        double d = rawDistance(progress, reverse);
        double eps = 0.5;
        double sign = reverse ? -1.0 : 1.0;
        Vec3 p1 = positionAtDistance(d - eps * sign);
        Vec3 p2 = positionAtDistance(d + eps * sign);
        Vec3 diff = p2.subtract(p1);
        return diff.lengthSqr() < 1e-8 ? new Vec3(0, 0, 1) : diff.normalize();
    }

    /**
     * Pathfinder Propeller'ın geminin döneceği yöne görsel olarak eğilmesi (Gimbal Yaw) için
     * mevcut teğet ile ~6 blok ilerideki teğet arasındaki yatay açı farkı [-25, +25] derece.
     */
    public float getSteerYawDegrees(double progress, boolean reverse) {
        Vec3 currentDir = getTangent(progress, reverse);
        Vec3 lookAheadDir = getTangent(Mth.clamp(progress + 6.0 / totalArcLength, 0.0, 1.0), reverse);
        if (Math.hypot(currentDir.x, currentDir.z) < 0.2 || Math.hypot(lookAheadDir.x, lookAheadDir.z) < 0.2) {
            return 0.0f;
        }
        double yawNow = Math.toDegrees(Math.atan2(-currentDir.x, currentDir.z));
        double yawAhead = Math.toDegrees(Math.atan2(-lookAheadDir.x, lookAheadDir.z));
        float delta = Mth.wrapDegrees((float) (yawAhead - yawNow)) * 1.8f;
        return Mth.clamp(delta, -25.0f, 25.0f);
    }

    /** Pathfinder Propeller'ın tırmanış/iniş yönüne görsel olarak eğilmesi (Gimbal Pitch) [-20, +20] derece. */
    public float getSteerPitchDegrees(double progress, boolean reverse) {
        Vec3 dir = getTangent(progress, reverse);
        float pitch = (float) Math.toDegrees(Math.asin(Mth.clamp(dir.y, -1.0, 1.0)));
        return Mth.clamp(pitch, -20.0f, 20.0f);
    }

    /**
     * Hafif uçuş koridoru taraması: yalnızca kalkış bacağının bitişi ile iniş bacağının başlangıcı arasındaki
     * seyir bölümünü {@value #CORRIDOR_SAMPLE_STEP} blokta bir örnekler ve her örnekte geminin 3x4x3 kesitine bakar.
     *
     * <p>Yanlış alarm koruması: liman, limanın zemini ve yanaşık gemi taranmaz — her iki liman ve yanaşma
     * noktasına yatayda 7 bloktan yakın örnekler atlanır, {@code ignoredBlocks} (ör. henüz birleştirilmemiş
     * geminin kendi blokları) yok sayılır, yüklenmemiş chunk'lar asla yüklenmez.
     *
     * @return koridoru kapatan ilk katı bloğun konumu; koridor temizse {@code null}
     */
    @Nullable
    public BlockPos findCorridorObstruction(Level level, boolean reverseLeg, @Nullable Set<BlockPos> ignoredBlocks) {
        if (totalArcLength < CORRIDOR_MIN_LENGTH) {
            return null;
        }
        int n = controlPoints.size();
        double start = controlPointDistance[1] + CORRIDOR_LEG_MARGIN;
        double end = controlPointDistance[n - 2] - CORRIDOR_LEG_MARGIN;
        if (end <= start) {
            return null;
        }

        Vec3[] clearZones = {getAnchorA(), getAnchorB(), Vec3.atCenterOf(dockA), Vec3.atCenterOf(dockB)};
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        double step = Math.max(CORRIDOR_SAMPLE_STEP, (end - start) / 512.0);
        for (double d = start; d <= end; d += step) {
            // d ham yay mesafesidir; dönüş bacağında aynı fiziksel noktaya (şerit ofsetiyle) karşılık gelen ilerleme
            double progress = d / totalArcLength;
            Vec3 center = getPosition(reverseLeg ? 1.0 - progress : progress, reverseLeg);
            if (isInsideClearZone(center, clearZones)) {
                continue;
            }
            for (int dy = 0; dy <= 3; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        cursor.set(Mth.floor(center.x + dx), Mth.floor(center.y + dy), Mth.floor(center.z + dz));
                        if (!level.isLoaded(cursor)) {
                            continue;
                        }
                        if (ignoredBlocks != null && ignoredBlocks.contains(cursor)) {
                            continue;
                        }
                        BlockState state = level.getBlockState(cursor);
                        if (!state.isAir() && !state.getCollisionShape(level, cursor).isEmpty()) {
                            return cursor.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean isInsideClearZone(Vec3 point, Vec3[] zones) {
        for (Vec3 zone : zones) {
            double dx = point.x - zone.x;
            double dz = point.z - zone.z;
            if (dx * dx + dz * dz < CORRIDOR_DOCK_CLEARANCE_SQR) {
                return true;
            }
        }
        return false;
    }

    public Vec3 getAnchorA() {
        return controlPoints.getFirst();
    }

    public Vec3 getAnchorB() {
        return controlPoints.getLast();
    }

    public boolean hasDockFacings() {
        return facingA != null && facingB != null;
    }

    public SkyRoute withFacings(@Nullable Direction newFacingA, @Nullable Direction newFacingB) {
        return new SkyRoute(routeId, dockA, dockB, waypoints, newFacingA, newFacingB);
    }

    public UUID getRouteId() {
        return routeId;
    }

    public BlockPos getDockA() {
        return dockA;
    }

    public BlockPos getDockB() {
        return dockB;
    }

    @Nullable
    public Direction getFacingA() {
        return facingA;
    }

    @Nullable
    public Direction getFacingB() {
        return facingB;
    }

    public List<BlockPos> getWaypoints() {
        return waypoints;
    }

    public double getTotalArcLength() {
        return totalArcLength;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("RouteId", routeId);
        tag.put("DockA", NbtUtils.writeBlockPos(dockA));
        tag.put("DockB", NbtUtils.writeBlockPos(dockB));
        if (facingA != null) {
            tag.putInt("FacingA", facingA.get3DDataValue());
        }
        if (facingB != null) {
            tag.putInt("FacingB", facingB.get3DDataValue());
        }
        ListTag wpList = new ListTag();
        for (BlockPos wp : waypoints) {
            wpList.add(NbtUtils.writeBlockPos(wp));
        }
        tag.put("Waypoints", wpList);
        return tag;
    }

    public static SkyRoute load(CompoundTag tag) {
        UUID id = tag.getUUID("RouteId");
        BlockPos a = NbtUtils.readBlockPos(tag, "DockA")
                .orElseThrow(() -> new IllegalArgumentException("Missing DockA in SkyRoute NBT"));
        BlockPos b = NbtUtils.readBlockPos(tag, "DockB")
                .orElseThrow(() -> new IllegalArgumentException("Missing DockB in SkyRoute NBT"));
        Direction fa = tag.contains("FacingA") ? Direction.from3DDataValue(tag.getInt("FacingA")) : null;
        Direction fb = tag.contains("FacingB") ? Direction.from3DDataValue(tag.getInt("FacingB")) : null;

        // NbtUtils.writeBlockPos, BlockPos'u IntArrayTag olarak yazar
        List<BlockPos> wps = new ArrayList<>();
        ListTag wpList = tag.getList("Waypoints", Tag.TAG_INT_ARRAY);
        int count = Math.min(wpList.size(), MAX_WAYPOINTS);
        for (int i = 0; i < count; i++) {
            int[] xyz = wpList.getIntArray(i);
            if (xyz.length == 3) {
                wps.add(new BlockPos(xyz[0], xyz[1], xyz[2]));
            }
        }
        return new SkyRoute(id, a, b, wps, fa, fb);
    }
}
